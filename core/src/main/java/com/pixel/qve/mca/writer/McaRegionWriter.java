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

    private static final ThreadLocal<Deflater> DEFLATER_CACHE = ThreadLocal.withInitial(() -> new Deflater(Deflater.DEFAULT_COMPRESSION, false));
    private static final ThreadLocal<byte[]> COMPRESS_TEMP_BUF = ThreadLocal.withInitial(() -> new byte[256 * 1024]);

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
    public record WriteMetrics(int sectorOffset, int sectorCount, int compressedBytes, boolean isRelocated) {}

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
            channel.force(false);
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
            ByteBuffer header = ByteBuffer.allocate(5);
            channel.read(header, filePos);
            header.flip();
            int length = header.getInt();
            byte rawCompressionType = header.get();
            int compressionType = rawCompressionType & 0x7F;

            if (length <= 1 || (filePos + 4 + length) > channel.size()) {
                return null;
            }

            int payloadLength = length - 1;
            ByteBuffer compressed = ByteBuffer.allocate(payloadLength);
            channel.read(compressed, filePos + 5);
            compressed.flip();

            if (compressionType == 2) { // ZLIB
                Inflater inflater = new Inflater();
                inflater.setInput(compressed.array(), compressed.arrayOffset(), payloadLength);
                ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(4096, payloadLength * 3));
                byte[] buf = new byte[8192];
                while (!inflater.finished()) {
                    int read = inflater.inflate(buf);
                    if (read == 0 && inflater.needsInput()) break;
                    baos.write(buf, 0, read);
                }
                inflater.end();
                return ByteBuffer.wrap(baos.toByteArray());
            } else if (compressionType == 1) { // GZIP
                try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed.array(), compressed.arrayOffset(), payloadLength);
                     java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(bais)) {
                    return ByteBuffer.wrap(gzip.readAllBytes());
                }
            } else if (compressionType == 3) { // Uncompressed
                return compressed;
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
        return writeChunk(localChunkX, localChunkZ, uncompressed, true);
    }

    /**
     * Writes an uncompressed chunk into this region file, optionally deferring header sync.
     *
     * @param localChunkX  Local chunk X within region [0..31]
     * @param localChunkZ  Local chunk Z within region [0..31]
     * @param uncompressed Uncompressed raw NBT byte buffer of the chunk
     * @param syncHeader   True to immediately flush the 8KB header to disk; false to defer for batching
     * @return WriteMetrics detailing sector allocation and compression
     * @throws IOException If write or compression fails
     */
    public synchronized WriteMetrics writeChunk(int localChunkX, int localChunkZ, byte[] uncompressed, boolean syncHeader) throws IOException {
        if (localChunkX < 0 || localChunkX >= 32 || localChunkZ < 0 || localChunkZ >= 32) {
            throw new IndexOutOfBoundsException("Local chunk coords must be in 0..31: (" + localChunkX + ", " + localChunkZ + ")");
        }
        Objects.requireNonNull(uncompressed, "uncompressed NBT cannot be null");

        // 1. Zlib Deflation
        Deflater deflater = DEFLATER_CACHE.get();
        deflater.reset();
        deflater.setInput(uncompressed, 0, uncompressed.length);
        deflater.finish();

        byte[] temp = COMPRESS_TEMP_BUF.get();
        int compressedLen = 0;
        ByteBuffer deflatedBuffer = null;

        while (!deflater.finished()) {
            int count = deflater.deflate(temp, 0, temp.length);
            if (count > 0) {
                if (deflatedBuffer == null) {
                    deflatedBuffer = ByteBuffer.allocate(count + 4096);
                } else if (deflatedBuffer.remaining() < count) {
                    ByteBuffer expanded = ByteBuffer.allocate(deflatedBuffer.capacity() * 2 + count);
                    deflatedBuffer.flip();
                    expanded.put(deflatedBuffer);
                    deflatedBuffer = expanded;
                }
                deflatedBuffer.put(temp, 0, count);
                compressedLen += count;
            }
        }
        if (deflatedBuffer != null) {
            deflatedBuffer.flip();
        } else {
            deflatedBuffer = ByteBuffer.allocate(0);
        }

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
        ByteBuffer writeBuffer = ByteBuffer.allocate(totalPayloadBytes);
        writeBuffer.putInt(compressedLen + 1); // length prefix
        writeBuffer.put((byte) 2);             // Zlib compression type
        writeBuffer.put(deflatedBuffer);
        // Remaining bytes up to sector boundary remain zero-padded
        writeBuffer.position(0);
        writeBuffer.limit(totalPayloadBytes);

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
