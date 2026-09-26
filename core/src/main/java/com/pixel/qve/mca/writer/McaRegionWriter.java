package com.pixel.qve.mca.writer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Ultra-high-performance writer for Minecraft Anvil (.mca) region files.
 * Streams compressed chunk NBT payloads directly to 4096-byte sectors on disk,
 * maintaining header sector tables and Unix timestamps with zero garbage allocations.
 */
public final class McaRegionWriter implements Closeable {

    private static final System.Logger LOGGER = System.getLogger(McaRegionWriter.class.getName());
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private static volatile int configuredCompressionLevel = Deflater.BEST_SPEED;
    private static final ThreadLocal<Deflater> DEFLATER_CACHE = ThreadLocal.withInitial(() -> new Deflater(configuredCompressionLevel, false));
    private static final ThreadLocal<byte[]> COMPRESS_TEMP_BUF = ThreadLocal.withInitial(() -> new byte[256 * 1024]);
    private static final ThreadLocal<ByteBuffer> DEFLATED_PAYLOAD_BUF = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(256 * 1024));
    private static final ThreadLocal<ByteBuffer> SECTOR_WRITE_BUF = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(256 * 1024));
    private static final byte[] ZERO_SECTOR_PAD = new byte[4096];

    private final Path filePath;
    private final int regionX;
    private final int regionZ;
    private final RandomAccessFile raf;
    private final FileChannel channel;
    private final SectorAllocator allocator = new SectorAllocator();
    private final ByteBuffer headerBuffer = ByteBuffer.allocateDirect(8192);

    /**
     * Metrics recorded after a chunk write operation.
     */
    public record WriteMetrics(int sectorOffset, int sectorCount, int compressedBytes, boolean isRelocated, boolean isVerified) {
        public WriteMetrics(int sectorOffset, int sectorCount, int compressedBytes, boolean isRelocated) {
            this(sectorOffset, sectorCount, compressedBytes, isRelocated, false);
        }

        public WriteMetrics withVerified(boolean verified) {
            return new WriteMetrics(sectorOffset, sectorCount, compressedBytes, isRelocated, verified);
        }
    }

    /**
     * Opens or creates an Anvil MCA region file for writing.
     *
     * @param path Path to the r.X.Z.mca file
     * @throws IOException If file cannot be created or opened
     */
    public McaRegionWriter(Path path) throws IOException {
        this.filePath = Objects.requireNonNull(path, "path cannot be null");

        String fileName = path.getFileName().toString();
        Matcher matcher = REGION_FILE_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid region file name (expected 'r.X.Z.mca'): " + fileName);
        }
        this.regionX = Integer.parseInt(matcher.group(1));
        this.regionZ = Integer.parseInt(matcher.group(2));

        File file = path.toFile();
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        this.raf = new RandomAccessFile(file, "rw");
        this.channel = raf.getChannel();

        initHeader();
    }

    private void initHeader() throws IOException {
        long size = channel.size();
        if (size >= 8192) {
            headerBuffer.clear();
            channel.read(headerBuffer, 0);
            headerBuffer.flip();
            allocator.loadHeader(headerBuffer);
        } else {
            // New or truncated file: write blank 8192-byte header
            headerBuffer.clear();
            allocator.writeHeader(headerBuffer);
            headerBuffer.flip();
            channel.write(headerBuffer, 0);
            channel.force(true);
        }
    }

    /**
     * Gets the region X coordinate.
     *
     * @return Region X
     */
    public int getRegionX() {
        return regionX;
    }

    /**
     * Gets the region Z coordinate.
     *
     * @return Region Z
     */
    public int getRegionZ() {
        return regionZ;
    }

    /**
     * Sets the global ZLIB compression level for Anvil chunk deflation (1 = BEST_SPEED, 9 = BEST_COMPRESSION).
     *
     * @param level Compression level [1..9]
     */
    public static void setCompressionLevel(int level) {
        if (level < 1 || level > 9) {
            throw new IllegalArgumentException("Compression level must be between 1 and 9, got: " + level);
        }
        configuredCompressionLevel = level;
    }

    /**
     * Gets the active global ZLIB compression level.
     *
     * @return Compression level [1..9]
     */
    public static int getCompressionLevel() {
        return configuredCompressionLevel;
    }

    /**
     * Checks if a local chunk exists and has allocated sectors in the header.
     *
     * @param localChunkX Local chunk X [0..31]
     * @param localChunkZ Local chunk Z [0..31]
     * @return True if chunk exists
     */
    public synchronized boolean hasChunk(int localChunkX, int localChunkZ) {
        if (localChunkX < 0 || localChunkX >= 32 || localChunkZ < 0 || localChunkZ >= 32) {
            return false;
        }
        return allocator.hasChunk(localChunkX + localChunkZ * 32);
    }

    private static final ThreadLocal<ByteBuffer> HEADER_READ_BUF = ThreadLocal.withInitial(() -> ByteBuffer.allocate(5));
    private static final ThreadLocal<ByteBuffer> COMPRESS_READ_BUF = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(256 * 1024));
    private static final ThreadLocal<Inflater> INFLATER_CACHE = ThreadLocal.withInitial(() -> new Inflater(false));
    private static final ThreadLocal<ByteBuffer> DECOMPRESS_BUFFER = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(1024 * 1024));

    /**
     * Reads and decompresses the raw NBT payload for the given local chunk from this region file.
     *
     * @param localChunkX Local chunk X [0..31]
     * @param localChunkZ Local chunk Z [0..31]
     * @return ByteBuffer containing decompressed NBT root compound, or null if chunk not present or corrupted
     */
    public synchronized ByteBuffer readChunkPayload(int localChunkX, int localChunkZ) {
        if (!hasChunk(localChunkX, localChunkZ)) {
            return null;
        }
        int localIndex = localChunkX + localChunkZ * 32;
        int loc = allocator.getLocation(localIndex);
        int sectorOffset = (loc >>> 8) & 0xFFFFFF;
        int sectorCount = loc & 0xFF;
        if (sectorOffset < 2 || sectorCount <= 0) {
            return null;
        }

        long filePos = (long) sectorOffset * 4096L;
        try {
            if (filePos + 5 > channel.size()) {
                return null;
            }
            ByteBuffer header = HEADER_READ_BUF.get();
            header.clear();
            channel.read(header, filePos);
            header.flip();
            int length = header.getInt();
            byte rawCompressionType = header.get();
            int compressionType = rawCompressionType & 0x7F;

            if (length <= 1 || (filePos + 4 + length) > channel.size()) {
                return null;
            }

            int payloadLength = length - 1;
            ByteBuffer compressed = COMPRESS_READ_BUF.get();
            if (compressed.capacity() < payloadLength) {
                compressed = ByteBuffer.allocateDirect(Math.max(payloadLength, compressed.capacity() * 2));
                COMPRESS_READ_BUF.set(compressed);
            }
            compressed.clear();
            compressed.limit(payloadLength);
            channel.read(compressed, filePos + 5);
            compressed.flip();

            if (compressionType == 2) { // ZLIB
                ByteBuffer target = DECOMPRESS_BUFFER.get();
                target.clear();
                Inflater inflater = INFLATER_CACHE.get();
                inflater.reset();
                inflater.setInput(compressed);
                while (!inflater.finished()) {
                    if (!target.hasRemaining()) {
                        ByteBuffer expanded = ByteBuffer.allocateDirect(target.capacity() * 2);
                        target.flip();
                        expanded.put(target);
                        DECOMPRESS_BUFFER.set(expanded);
                        target = expanded;
                    }
                    int written = inflater.inflate(target);
                    if (written == 0 && inflater.needsInput()) break;
                }
                target.flip();
                return target.slice();
            } else if (compressionType == 1) { // GZIP
                byte[] raw = new byte[payloadLength];
                compressed.get(raw);
                try (ByteArrayInputStream bais = new ByteArrayInputStream(raw);
                     java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(bais)) {
                    return ByteBuffer.wrap(gzip.readAllBytes());
                }
            } else if (compressionType == 3) { // Uncompressed
                return compressed.slice();
            }
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Failed to read chunk payload ({0}, {1}) from region r.{2}.{3}.mca: {4}",
                    localChunkX, localChunkZ, regionX, regionZ, e.getMessage());
        }
        return null;
    }

    /**
     * Gets the path to the region file.
     *
     * @return File path
     */
    public Path getFilePath() {
        return filePath;
    }

    /**
     * Compresses and writes an uncompressed chunk NBT payload directly to the region file.
     *
     * @param localChunkX  Local chunk X [0..31]
     * @param localChunkZ  Local chunk Z [0..31]
     * @param uncompressed Uncompressed NBT bytes
     * @return WriteMetrics
     * @throws IOException If write or compression fails
     */
    public synchronized WriteMetrics writeChunk(int localChunkX, int localChunkZ, byte[] uncompressed) throws IOException {
        return writeChunk(localChunkX, localChunkZ, ByteBuffer.wrap(uncompressed), true);
    }

    public synchronized WriteMetrics writeChunk(int localChunkX, int localChunkZ, byte[] uncompressed, boolean syncHeader) throws IOException {
        return writeChunk(localChunkX, localChunkZ, ByteBuffer.wrap(uncompressed), syncHeader);
    }

    /**
     * Compresses and writes an uncompressed chunk NBT payload directly from a ByteBuffer to the region file.
     *
     * @param localChunkX  Local chunk X [0..31]
     * @param localChunkZ  Local chunk Z [0..31]
     * @param uncompressed Uncompressed NBT bytes
     * @return WriteMetrics
     * @throws IOException If write or compression fails
     */
    public synchronized WriteMetrics writeChunk(int localChunkX, int localChunkZ, ByteBuffer uncompressed) throws IOException {
        return writeChunk(localChunkX, localChunkZ, uncompressed, true);
    }

    /**
     * Writes an uncompressed chunk into this region file directly from a ByteBuffer, optionally deferring header sync.
     * Zero heap allocation: deflates directly from the ByteBuffer and streams via DirectByteBuffer DMA.
     *
     * @param localChunkX  Local chunk X within region [0..31]
     * @param localChunkZ  Local chunk Z within region [0..31]
     * @param uncompressed Uncompressed raw NBT byte buffer of the chunk
     * @param syncHeader   True to immediately flush the 8KB header to disk; false to defer for batching
     * @return WriteMetrics detailing sector allocation and compression
     * @throws IOException If write or compression fails
     */
    public synchronized WriteMetrics writeChunk(int localChunkX, int localChunkZ, ByteBuffer uncompressed, boolean syncHeader) throws IOException {
        if (localChunkX < 0 || localChunkX >= 32 || localChunkZ < 0 || localChunkZ >= 32) {
            throw new IndexOutOfBoundsException("Local chunk coords must be in 0..31: (" + localChunkX + ", " + localChunkZ + ")");
        }
        Objects.requireNonNull(uncompressed, "uncompressed NBT cannot be null");

        // 1. Zlib Deflation directly from ByteBuffer
        Deflater deflater = DEFLATER_CACHE.get();
        deflater.reset();
        deflater.setLevel(configuredCompressionLevel);
        deflater.setInput(uncompressed.duplicate());
        deflater.finish();

        byte[] temp = COMPRESS_TEMP_BUF.get();
        int compressedLen = 0;
        ByteBuffer deflatedBuffer = DEFLATED_PAYLOAD_BUF.get();
        deflatedBuffer.clear();

        while (!deflater.finished()) {
            int count = deflater.deflate(temp, 0, temp.length);
            if (count > 0) {
                if (deflatedBuffer.remaining() < count) {
                    ByteBuffer expanded = ByteBuffer.allocateDirect(Math.max(deflatedBuffer.capacity() * 2, deflatedBuffer.capacity() + count));
                    deflatedBuffer.flip();
                    expanded.put(deflatedBuffer);
                    DEFLATED_PAYLOAD_BUF.set(expanded);
                    deflatedBuffer = expanded;
                }
                deflatedBuffer.put(temp, 0, count);
                compressedLen += count;
            }
        }
        deflatedBuffer.flip();

        int localIndex = localChunkX + localChunkZ * 32;
        int neededSectors = SectorAllocator.calculateNeededSectors(compressedLen);

        // 2. External chunk file (.mcc) handling if chunk >= 256 sectors (> 1MB)
        if (neededSectors >= 256) {
            return writeExternalChunk(localChunkX, localChunkZ, localIndex, deflatedBuffer, compressedLen, syncHeader);
        }

        // 3. Allocate sectors in .mca file
        SectorAllocator.AllocationResult alloc = allocator.allocate(localIndex, neededSectors);
        long filePos = (long) alloc.sectorOffset() * 4096L;

        // 4. Assemble chunk payload: 4 bytes length + 1 byte compressionType (2) + payload + zero padding
        int totalPayloadBytes = alloc.sectorCount() * 4096;
        ByteBuffer writeBuffer = SECTOR_WRITE_BUF.get();
        if (writeBuffer.capacity() < totalPayloadBytes) {
            writeBuffer = ByteBuffer.allocateDirect(totalPayloadBytes);
            SECTOR_WRITE_BUF.set(writeBuffer);
        }
        writeBuffer.clear();
        writeBuffer.putInt(compressedLen + 1); // length prefix
        writeBuffer.put((byte) 2);             // Zlib compression type
        writeBuffer.put(deflatedBuffer);

        // Remaining bytes up to sector boundary remain zero-padded
        int pad = totalPayloadBytes - writeBuffer.position();
        while (pad > 0) {
            int toPad = Math.min(pad, ZERO_SECTOR_PAD.length);
            writeBuffer.put(ZERO_SECTOR_PAD, 0, toPad);
            pad -= toPad;
        }
        writeBuffer.flip();

        // 5. Write to FileChannel
        channel.write(writeBuffer, filePos);

        // 6. Update 8KB header on disk if requested
        if (syncHeader) {
            syncHeader();
        }

        return new WriteMetrics(alloc.sectorOffset(), alloc.sectorCount(), compressedLen, alloc.isRelocated());
    }

    private WriteMetrics writeExternalChunk(int localChunkX, int localChunkZ, int localIndex,
                                           ByteBuffer deflatedBuffer, int compressedLen, boolean syncHeader) throws IOException {
        int worldChunkX = (regionX << 5) | localChunkX;
        int worldChunkZ = (regionZ << 5) | localChunkZ;
        Path parent = filePath.getParent();
        Path mccPath = (parent != null) ? parent.resolve("c." + worldChunkX + "." + worldChunkZ + ".mcc") : null;
        if (mccPath == null) {
            throw new IOException("Cannot resolve parent directory for external chunk file: " + filePath);
        }

        byte[] bytes = new byte[deflatedBuffer.remaining()];
        deflatedBuffer.get(bytes);
        Files.write(mccPath, bytes);

        // External header entry: sectorOffset = 0, sectorCount = 1, compressionType with flag 128
        // Allocator reserves 1 sector placeholder or external marker
        SectorAllocator.AllocationResult alloc = allocator.allocate(localIndex, 1);
        long filePos = (long) alloc.sectorOffset() * 4096L;

        ByteBuffer markerBuf = ByteBuffer.allocate(4096);
        markerBuf.putInt(1);                   // length
        markerBuf.put((byte) (2 | 128));       // Zlib + External flag
        markerBuf.position(0);
        channel.write(markerBuf, filePos);

        if (syncHeader) {
            syncHeader();
        }
        return new WriteMetrics(alloc.sectorOffset(), 1, compressedLen, true);
    }

    /**
     * Flushes the current in-memory 8KB allocation header to persistent storage.
     *
     * @throws IOException If disk sync fails
     */
    public synchronized void syncHeaderOnly() throws IOException {
        syncHeader();
    }

    private void syncHeader() throws IOException {
        headerBuffer.clear();
        allocator.writeHeader(headerBuffer);
        headerBuffer.flip();
        channel.write(headerBuffer, 0);
        channel.force(false);
        com.pixel.qve.mca.storage.McaFileChannelManager.getGlobal().notifyRegionModified(regionX, regionZ);
    }

    /**
     * Forces all buffered writes to persistent storage.
     *
     * @param metaData True to flush file metadata as well
     * @throws IOException If sync fails
     */
    public synchronized void flush(boolean metaData) throws IOException {
        channel.force(metaData);
    }

    /**
     * Creates an atomic snapshot of the current sector allocation state.
     *
     * @return SectorAllocator.Snapshot
     */
    public synchronized SectorAllocator.Snapshot snapshotAllocator() {
        return allocator.createSnapshot();
    }

    /**
     * Restores the sector allocation state from a previously captured snapshot.
     *
     * @param snapshot Snapshot to restore
     */
    public synchronized void restoreAllocator(SectorAllocator.Snapshot snapshot) {
        allocator.restoreSnapshot(snapshot);
    }

    /**
     * Reads raw allocated sector bytes for a local chunk index without decompression.
     * Used for transaction rollback staging.
     *
     * @param localIndex Local chunk index [0..1023]
     * @return Raw sector bytes, or null if chunk not present or corrupted
     */
    public synchronized byte[] readChunkRaw(int localIndex) {
        if (localIndex < 0 || localIndex >= SectorAllocator.CHUNKS_PER_REGION) {
            return null;
        }
        int loc = allocator.getLocation(localIndex);
        int sectorOffset = (loc >>> 8) & 0xFFFFFF;
        int sectorCount = loc & 0xFF;
        if (sectorOffset < 2 || sectorCount <= 0) {
            return null;
        }

        long filePos = (long) sectorOffset * 4096L;
        int byteCount = sectorCount * 4096;
        try {
            if (filePos + 5 > channel.size()) {
                return null;
            }
            int toRead = (int) Math.min(byteCount, channel.size() - filePos);
            ByteBuffer buf = ByteBuffer.allocate(toRead);
            channel.read(buf, filePos);
            return buf.array();
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Failed to read raw chunk ({0}) for rollback staging: {1}", localIndex, e.getMessage());
            return null;
        }
    }

    /**
     * Writes raw bytes back to the specified sector offset on disk.
     * Used during transaction rollback to restore original chunk sector data.
     *
     * @param sectorOffset Target sector offset
     * @param rawBytes     Raw sector bytes to write
     * @throws IOException If write fails
     */
    public synchronized void writeChunkRaw(int sectorOffset, byte[] rawBytes) throws IOException {
        if (sectorOffset < 2 || rawBytes == null || rawBytes.length == 0) {
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(rawBytes);
        channel.write(buf, (long) sectorOffset * 4096L);
    }

    /**
     * Atomically rolls back this region file to a previous snapshot state, restoring any overwritten
     * sector payloads, restoring the 8KB header, and flushing to persistent storage.
     *
     * @param snapshot         Allocation snapshot to restore
     * @param rollbackPayloads Map of localIndex to original raw sector bytes
     * @throws IOException If rollback write fails
     */
    public synchronized void rollback(SectorAllocator.Snapshot snapshot, java.util.Map<Integer, byte[]> rollbackPayloads) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        if (rollbackPayloads != null) {
            for (java.util.Map.Entry<Integer, byte[]> entry : rollbackPayloads.entrySet()) {
                int localIndex = entry.getKey();
                byte[] rawBytes = entry.getValue();
                if (rawBytes != null && localIndex >= 0 && localIndex < SectorAllocator.CHUNKS_PER_REGION) {
                    int oldLoc = snapshot.locations()[localIndex];
                    int oldOffset = (oldLoc >>> 8) & 0xFFFFFF;
                    if (oldOffset >= 2) {
                        writeChunkRaw(oldOffset, rawBytes);
                    }
                }
            }
        }
        allocator.restoreSnapshot(snapshot);
        syncHeader();
        channel.force(true);
        LOGGER.log(System.Logger.Level.INFO, "Rolled back region r.{0}.{1}.mca to pre-transaction state", regionX, regionZ);
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            syncHeader();
            channel.force(true);
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.WARNING, "Error syncing header on close for {0}: {1}", filePath, t.getMessage());
        } finally {
            try {
                channel.close();
            } finally {
                try {
                    raf.close();
                } finally {
                    com.pixel.qve.mca.storage.NativeBufferCleaner.clean(headerBuffer);
                }
            }
        }
    }
}
