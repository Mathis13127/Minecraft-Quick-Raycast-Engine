package com.pixel.raycast.core.mca;

import com.pixel.raycast.core.voxel.BlockIdRegistry;
import com.pixel.raycast.core.voxel.BlockStatePaletteUnpacker;
import com.pixel.raycast.core.voxel.VoxelSection;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;

/**
 * High-performance reader for Minecraft Anvil (.mca) region files in pure Java.
 * Loads 16x16x16 voxel sections directly from disk without initializing Minecraft or NeoForge.
 */
public final class McaRegionReader implements Closeable {

    private static final System.Logger LOGGER = System.getLogger(McaRegionReader.class.getName());
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private static final byte[] SECTIONS_NAME = "sections".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] Y_NAME = "Y".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BLOCK_STATES_NAME = "block_states".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PALETTE_NAME = "palette".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DATA_NAME = "data".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NAME_NAME = "Name".getBytes(StandardCharsets.US_ASCII);

    private final Path filePath;
    private final RandomAccessFile raf;
    private final FileChannel channel;
    private final int regionX;
    private final int regionZ;
    private final int[] sectorOffsets = new int[1024];
    private final BlockIdRegistry registry;

    /**
     * Functional callback for consuming parsed chunk sections.
     */
    @FunctionalInterface
    public interface ChunkSectionConsumer {
        /**
         * Accepts a parsed VoxelSection at the given vertical section index.
         *
         * @param sectionY Vertical section index (e.g. -4 to 19 in 1.21)
         * @param section  Parsed VoxelSection
         */
        void accept(int sectionY, VoxelSection section);
    }

    /**
     * Opens an Anvil MCA region file and reads the 4KB chunk offset table.
     *
     * @param path     Path to the r.X.Z.mca file
     * @param registry BlockIdRegistry for registering block types
     * @throws IOException If file does not exist or cannot be read
     */
    public McaRegionReader(Path path, BlockIdRegistry registry) throws IOException {
        this.filePath = Objects.requireNonNull(path, "Path cannot be null");
        this.registry = Objects.requireNonNull(registry, "BlockIdRegistry cannot be null");

        String fileName = path.getFileName().toString();
        Matcher matcher = REGION_FILE_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid region file name (expected 'r.X.Z.mca'): " + fileName);
        }
        this.regionX = Integer.parseInt(matcher.group(1));
        this.regionZ = Integer.parseInt(matcher.group(2));

        File file = path.toFile();
        if (!file.exists() || !file.isFile()) {
            throw new IOException("Region file does not exist: " + path);
        }

        this.raf = new RandomAccessFile(file, "r");
        this.channel = raf.getChannel();

        // Read 4096-byte sector offset header
        ByteBuffer header = ByteBuffer.allocate(4096);
        channel.read(header, 0);
        header.flip();

        for (int i = 0; i < 1024; i++) {
            int b0 = header.get() & 0xFF;
            int b1 = header.get() & 0xFF;
            int b2 = header.get() & 0xFF;
            int sectorCount = header.get() & 0xFF;
            if (sectorCount > 0) {
                sectorOffsets[i] = (b0 << 16) | (b1 << 8) | b2;
            } else {
                sectorOffsets[i] = 0;
            }
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
     * Gets the path to the underlying MCA file.
     *
     * @return File path
     */
    public Path getFilePath() {
        return filePath;
    }

    /**
     * Checks if the given local chunk (0..31, 0..31) is generated in this region.
     *
     * @param localChunkX Local chunk X [0..31]
     * @param localChunkZ Local chunk Z [0..31]
     * @return True if chunk offset is non-zero
     */
    public boolean hasChunk(int localChunkX, int localChunkZ) {
        if (localChunkX < 0 || localChunkX >= 32 || localChunkZ < 0 || localChunkZ >= 32) {
            return false;
        }
        return sectorOffsets[localChunkX + localChunkZ * 32] != 0;
    }

    /**
     * Reads all sections of the specified local chunk into the consumer callback.
     *
     * @param localChunkX Chunk X relative to region (0..31)
     * @param localChunkZ Chunk Z relative to region (0..31)
     * @param consumer    Callback receiving each populated section
     * @return Number of sections parsed
     * @throws IOException If decompression or I/O error occurs
     */
    public int readChunk(int localChunkX, int localChunkZ, ChunkSectionConsumer consumer) throws IOException {
        if (localChunkX < 0 || localChunkX >= 32 || localChunkZ < 0 || localChunkZ >= 32) {
            throw new IndexOutOfBoundsException("Local chunk coords must be in 0..31: (" + localChunkX + ", " + localChunkZ + ")");
        }

        int sectorOffset = sectorOffsets[localChunkX + localChunkZ * 32];
        if (sectorOffset == 0) {
            return 0; // Chunk is not generated in this region
        }

        ByteBuffer chunkHeader = ByteBuffer.allocate(5);
        int headerRead = channel.read(chunkHeader, (long) sectorOffset * 4096L);
        if (headerRead < 5) {
            return 0; // Truncated or empty sector
        }
        chunkHeader.flip();

        int length = chunkHeader.getInt();
        if (length <= 0) {
            return 0; // Unallocated or unwritten chunk
        }

        int rawCompressionType = chunkHeader.get() & 0xFF;
        boolean isExternal = (rawCompressionType & 128) != 0;
        int compressionType = rawCompressionType & 0x7F;

        byte[] compressed;

        if (isExternal) {
            int worldChunkX = (regionX << 5) | localChunkX;
            int worldChunkZ = (regionZ << 5) | localChunkZ;
            Path parentDir = filePath.getParent();
            Path mccPath = (parentDir != null) ? parentDir.resolve("c." + worldChunkX + "." + worldChunkZ + ".mcc") : null;
            if (mccPath == null || !java.nio.file.Files.isRegularFile(mccPath)) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "External chunk file for chunk ({0}, {1}) in region r.{2}.{3}.mca not found: {4}",
                        localChunkX, localChunkZ, regionX, regionZ, mccPath);
                return 0;
            }
            try {
                compressed = java.nio.file.Files.readAllBytes(mccPath);
            } catch (IOException e) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Failed to read external chunk file {0}: {1}",
                        mccPath, e.getMessage());
                return 0;
            }
        } else {
            int payloadLength = length - 1;
            if (payloadLength <= 0) {
                return 0;
            }
            ByteBuffer payload = ByteBuffer.allocate(payloadLength);
            int payloadRead = channel.read(payload, (long) sectorOffset * 4096L + 5L);
            if (payloadRead < payloadLength) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Truncated payload in chunk ({0}, {1}) in region r.{2}.{3}.mca: expected {4}, read {5}",
                        localChunkX, localChunkZ, regionX, regionZ, payloadLength, payloadRead);
                return 0;
            }
            payload.flip();
            compressed = new byte[payload.remaining()];
            payload.get(compressed);
        }

        byte[] decompressed;
        try {
            switch (compressionType) {
                case 1 -> { // GZIP
                    try (java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(compressed))) {
                        decompressed = gzip.readAllBytes();
                    }
                }
                case 2 -> { // DEFLATE (Zlib)
                    try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
                        decompressed = inflater.readAllBytes();
                    }
                }
                case 3 -> { // Uncompressed
                    decompressed = compressed;
                }
                default -> {
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Unsupported compression type {0} in chunk ({1}, {2}) in region r.{3}.{4}.mca, skipping",
                            rawCompressionType, localChunkX, localChunkZ, regionX, regionZ);
                    return 0;
                }
            }
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Failed to decompress chunk ({0}, {1}) in region r.{2}.{3}.mca: {4}",
                    localChunkX, localChunkZ, regionX, regionZ, e.getMessage());
            return 0;
        }

        ByteBuffer buf = ByteBuffer.wrap(decompressed);
        if (buf.remaining() < 1) {
            return 0;
        }
        byte rootType = buf.get();
        if (rootType != FastNbtReader.TAG_COMPOUND) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Corrupted NBT root tag in chunk ({0}, {1}) in region r.{2}.{3}.mca: expected 10, got {4}",
                    localChunkX, localChunkZ, regionX, regionZ, rootType);
            return 0;
        }
        int rootNameLen = buf.getShort() & 0xFFFF;
        if (buf.remaining() < rootNameLen) {
            return 0;
        }
        buf.position(buf.position() + rootNameLen);

        int parsedSections = 0;

        while (buf.hasRemaining()) {
            byte tagType = buf.get();
            if (tagType == FastNbtReader.TAG_END) break;

            int nameLen = buf.getShort() & 0xFFFF;
            int namePos = buf.position();
            buf.position(namePos + nameLen);

            if (FastNbtReader.matches(buf, namePos, nameLen, SECTIONS_NAME) && tagType == FastNbtReader.TAG_LIST) {
                byte elemType = buf.get();
                if (elemType != FastNbtReader.TAG_COMPOUND) {
                    throw new IllegalStateException("Expected sections list elements to be TAG_Compound (10), got: " + elemType);
                }
                int count = buf.getInt();
                for (int s = 0; s < count; s++) {
                    parsedSections += parseSection(buf, consumer);
                }
            } else {
                FastNbtReader.skipTagPayload(buf, tagType);
            }
        }

        return parsedSections;
    }

    private int parseSection(ByteBuffer buf, ChunkSectionConsumer consumer) {
        int sectionY = Integer.MIN_VALUE;
        short[] paletteIds = null;
        long[] data = null;

        while (true) {
            byte childType = buf.get();
            if (childType == FastNbtReader.TAG_END) break;

            int nameLen = buf.getShort() & 0xFFFF;
            int namePos = buf.position();
            buf.position(namePos + nameLen);

            if (FastNbtReader.matches(buf, namePos, nameLen, Y_NAME) && childType == FastNbtReader.TAG_BYTE) {
                sectionY = buf.get();
            } else if (FastNbtReader.matches(buf, namePos, nameLen, BLOCK_STATES_NAME) && childType == FastNbtReader.TAG_COMPOUND) {
                // Parse block_states compound
                while (true) {
                    byte bsType = buf.get();
                    if (bsType == FastNbtReader.TAG_END) break;

                    int bsNameLen = buf.getShort() & 0xFFFF;
                    int bsNamePos = buf.position();
                    buf.position(bsNamePos + bsNameLen);

                    if (FastNbtReader.matches(buf, bsNamePos, bsNameLen, PALETTE_NAME) && bsType == FastNbtReader.TAG_LIST) {
                        buf.get(); // elemType (10)
                        int pCount = buf.getInt();
                        List<String> names = new ArrayList<>(pCount);
                        for (int pi = 0; pi < pCount; pi++) {
                            names.add(parsePaletteEntry(buf));
                        }
                        paletteIds = new short[names.size()];
                        for (int i = 0; i < names.size(); i++) {
                            paletteIds[i] = registry.getOrRegister(names.get(i));
                        }
                    } else if (FastNbtReader.matches(buf, bsNamePos, bsNameLen, DATA_NAME) && bsType == FastNbtReader.TAG_LONG_ARRAY) {
                        data = FastNbtReader.readLongArray(buf);
                    } else {
                        FastNbtReader.skipTagPayload(buf, bsType);
                    }
                }
            } else {
                FastNbtReader.skipTagPayload(buf, childType);
            }
        }

        if (sectionY != Integer.MIN_VALUE && paletteIds != null) {
            VoxelSection section = BlockStatePaletteUnpacker.unpackDirect(paletteIds, data);
            consumer.accept(sectionY, section);
            return 1;
        }

        return 0;
    }

    private String parsePaletteEntry(ByteBuffer buf) {
        String name = "";
        while (true) {
            byte itemType = buf.get();
            if (itemType == FastNbtReader.TAG_END) break;

            int nameLen = buf.getShort() & 0xFFFF;
            int namePos = buf.position();
            buf.position(namePos + nameLen);

            if (FastNbtReader.matches(buf, namePos, nameLen, NAME_NAME) && itemType == FastNbtReader.TAG_STRING) {
                name = FastNbtReader.readString(buf);
            } else {
                FastNbtReader.skipTagPayload(buf, itemType);
            }
        }
        return name;
    }

    @Override
    public void close() throws IOException {
        channel.close();
        raf.close();
    }
}
