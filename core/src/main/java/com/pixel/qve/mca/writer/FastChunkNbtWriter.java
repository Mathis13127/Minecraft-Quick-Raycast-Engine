package com.pixel.qve.mca.writer;

import com.pixel.qve.mca.FastNbtReader;
import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.state.BlockStateDictionary;
import com.pixel.qve.world.VoxelSection;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Ultra-fast zero-garbage serializer for Minecraft 1.21.1 chunk NBT payloads.
 * Compiles VoxelSections into standard Anvil bit-packed long arrays, block state palettes,
 * biomes, heightmaps, and tile entities.
 */
public final class FastChunkNbtWriter {

    /** Minecraft 1.21.1 Anvil DataVersion. */
    public static final int DATA_VERSION_1_21_1 = 3955;

    private static final byte[] DATA_VERSION_NAME = "DataVersion".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] X_POS_NAME = "xPos".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] Y_POS_NAME = "yPos".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] Z_POS_NAME = "zPos".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STATUS_NAME = "Status".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STATUS_FULL = "minecraft:full".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SECTIONS_NAME = "sections".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] Y_NAME = "Y".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BLOCK_STATES_NAME = "block_states".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PALETTE_NAME = "palette".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DATA_NAME = "data".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NAME_NAME = "Name".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PROPERTIES_NAME = "Properties".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BIOMES_NAME = "biomes".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BLOCK_ENTITIES_NAME = "block_entities".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] AIR_NAME = "minecraft:air".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DEFAULT_BIOME = "minecraft:plains".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEIGHTMAPS_NAME = "Heightmaps".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MOTION_BLOCKING_NAME = "MOTION_BLOCKING".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WORLD_SURFACE_NAME = "WORLD_SURFACE".getBytes(StandardCharsets.US_ASCII);

    private FastChunkNbtWriter() {}

    /**
     * Serializes a complete 1.21.1 Chunk NBT structure into the target FastNbtWriter using default biome ("minecraft:plains").
     * <p>
     * <b>========================================================================</b><br>
     * <b>CRITICAL MINECRAFT WORLD GENERATION WARNING (STATUS: minecraft:full):</b><br>
     * Ex-nihilo creation of a chunk NBT writes {@code Status: "minecraft:full"}.<br>
     * When Minecraft loads a chunk marked with {@code Status: "minecraft:full"}, the internal<br>
     * ChunkStatus pipeline considers terrain generation, biomes, carvers, surface, features,<br>
     * and structure generation to be 100% COMPLETE.<br>
     * <b>Minecraft's world generation will NEVER generate terrain or structures over this chunk from the world seed!</b><br>
     * This chunk is permanently locked to whatever blocks and state were written here.<br>
     * <b>========================================================================</b>
     * </p>
     *
     * @param writer        Target FastNbtWriter
     * @param chunkX        World chunk X
     * @param chunkZ        World chunk Z
     * @param minSectionY   Minimum section Y (e.g. -4 for overworld)
     * @param maxSectionY   Maximum section Y (e.g. 19 for overworld)
     * @param registry      BlockIdRegistry for state resolution
     * @param sections      Map of section Y to VoxelSection
     * @param blockEntities Map of packed coordinate to raw BlockEntity NBT compounds
     */
    public static void writeChunk(FastNbtWriter writer, int chunkX, int chunkZ,
                                  int minSectionY, int maxSectionY,
                                  BlockIdRegistry registry,
                                  Map<Integer, VoxelSection> sections,
                                  Map<Long, byte[]> blockEntities) {
        writeChunk(writer, chunkX, chunkZ, minSectionY, maxSectionY, registry, sections, blockEntities, "minecraft:plains");
    }

    /**
     * Serializes a complete 1.21.1 Chunk NBT structure into the target FastNbtWriter with a configurable default biome.
     * <p>
     * <b>========================================================================</b><br>
     * <b>CRITICAL MINECRAFT WORLD GENERATION WARNING (STATUS: minecraft:full):</b><br>
     * Ex-nihilo creation of a chunk NBT writes {@code Status: "minecraft:full"}.<br>
     * When Minecraft loads a chunk marked with {@code Status: "minecraft:full"}, the internal<br>
     * ChunkStatus pipeline considers terrain generation, biomes, carvers, surface, features,<br>
     * and structure generation to be 100% COMPLETE.<br>
     * <b>Minecraft's world generation will NEVER generate terrain or structures over this chunk from the world seed!</b><br>
     * This chunk is permanently locked to whatever blocks and state were written here.<br>
     * <b>========================================================================</b>
     * </p>
     *
     * @param writer        Target FastNbtWriter
     * @param chunkX        World chunk X
     * @param chunkZ        World chunk Z
     * @param minSectionY   Minimum section Y (e.g. -4 for overworld)
     * @param maxSectionY   Maximum section Y (e.g. 19 for overworld)
     * @param registry      BlockIdRegistry for state resolution
     * @param sections      Map of section Y to VoxelSection
     * @param blockEntities Map of packed coordinate to raw BlockEntity NBT compounds
     * @param defaultBiome  Default biome identifier (e.g. "minecraft:plains")
     */
    public static void writeChunk(FastNbtWriter writer, int chunkX, int chunkZ,
                                  int minSectionY, int maxSectionY,
                                  BlockIdRegistry registry,
                                  Map<Integer, VoxelSection> sections,
                                  Map<Long, byte[]> blockEntities,
                                  String defaultBiome) {
        Objects.requireNonNull(writer, "FastNbtWriter cannot be null");
        Objects.requireNonNull(registry, "BlockIdRegistry cannot be null");

        writer.beginRootCompound("");

        writer.putInt(DATA_VERSION_NAME, DATA_VERSION_1_21_1);
        writer.putInt(X_POS_NAME, chunkX);
        writer.putInt(Y_POS_NAME, minSectionY);
        writer.putInt(Z_POS_NAME, chunkZ);
        writer.putString(STATUS_NAME, STATUS_FULL);

        // 1. Sections list
        int sectionCount = (maxSectionY - minSectionY + 1);
        writer.beginList(SECTIONS_NAME, FastNbtReader.TAG_COMPOUND, sectionCount);

        for (int secY = minSectionY; secY <= maxSectionY; secY++) {
            writer.beginListCompound();
            writer.putByte(Y_NAME, (byte) secY);

            VoxelSection section = (sections != null) ? sections.get(secY) : null;
            writeSectionBlockStates(writer, section, registry);
            writeSectionBiomes(writer, defaultBiome);

            writer.endCompound();
        }

        // 2. Block entities list
        int beCount = (blockEntities != null) ? blockEntities.size() : 0;
        writer.beginList(BLOCK_ENTITIES_NAME, FastNbtReader.TAG_COMPOUND, beCount);
        if (beCount > 0) {
            for (byte[] rawBe : blockEntities.values()) {
                if (rawBe != null && rawBe.length > 0) {
                    writer.putRawBytes(rawBe);
                }
            }
        }

        // 3. Heightmaps
        writeHeightmaps(writer, sections, minSectionY, maxSectionY);

        // End root compound
        writer.endCompound();
    }

    private static final ThreadLocal<int[]> PALETTE_LOOKUP_BUF = ThreadLocal.withInitial(() -> {
        int[] arr = new int[65536];
        Arrays.fill(arr, -1);
        return arr;
    });
    private static final ThreadLocal<int[]> UNIQUE_IDS_BUF = ThreadLocal.withInitial(() -> new int[256]);
    private static final ThreadLocal<short[]> HEIGHTS_BUF = ThreadLocal.withInitial(() -> new short[256]);

    static void writeSectionBlockStates(FastNbtWriter writer, VoxelSection section, BlockIdRegistry registry) {
        writer.beginCompound(BLOCK_STATES_NAME);

        if (section == null || section.isEmpty()) {
            // Homogeneous single-entry air palette
            writer.beginList(PALETTE_NAME, FastNbtReader.TAG_COMPOUND, 1);
            writer.putRawBytes(registry.getStateDictionary().getPrecompiledPaletteNbt(BlockIdRegistry.AIR_ID));
            writer.endCompound();
            return;
        }

        if (section.isHomogeneous()) {
            // Fast-path: homogeneous single-entry palette with 0 array allocations and 0 loops (1.21.1)
            writer.beginList(PALETTE_NAME, FastNbtReader.TAG_COMPOUND, 1);
            writer.putRawBytes(registry.getStateDictionary().getPrecompiledPaletteNbt(section.getSingleBlockId()));
            writer.endCompound();
            return;
        }

        int[] voxelIds = section.getBlockIds();
        int[] lookup = PALETTE_LOOKUP_BUF.get();
        int[] uniqueIds = UNIQUE_IDS_BUF.get();
        int uniqueCount = 0;

        for (int i = 0; i < VoxelSection.VOXEL_COUNT; i++) {
            int id = voxelIds[i];
            if (id >= lookup.length) {
                int oldLen = lookup.length;
                int newLen = Math.max(oldLen * 2, id + 1024);
                lookup = Arrays.copyOf(lookup, newLen);
                Arrays.fill(lookup, oldLen, newLen, -1);
                PALETTE_LOOKUP_BUF.set(lookup);
            }
            if (lookup[id] == -1) {
                lookup[id] = uniqueCount;
                if (uniqueCount >= uniqueIds.length) {
                    uniqueIds = Arrays.copyOf(uniqueIds, uniqueIds.length * 2);
                    UNIQUE_IDS_BUF.set(uniqueIds);
                }
                uniqueIds[uniqueCount++] = id;
            }
        }

        if (uniqueCount == 1) {
            lookup[uniqueIds[0]] = -1;
            writer.beginList(PALETTE_NAME, FastNbtReader.TAG_COMPOUND, 1);
            writer.putRawBytes(registry.getStateDictionary().getPrecompiledPaletteNbt(uniqueIds[0]));
            writer.endCompound();
            return;
        }

        // Multiple entries: write palette + bit-packed data long array
        writer.beginList(PALETTE_NAME, FastNbtReader.TAG_COMPOUND, uniqueCount);
        BlockStateDictionary dict = registry.getStateDictionary();
        for (int u = 0; u < uniqueCount; u++) {
            writer.putRawBytes(dict.getPrecompiledPaletteNbt(uniqueIds[u]));
        }

        // Bit-packing
        int bitsPerBlock = Math.max(4, 32 - Integer.numberOfLeadingZeros(uniqueCount - 1));
        int entriesPerLong = 64 / bitsPerBlock;
        int longsCount = (VoxelSection.VOXEL_COUNT + entriesPerLong - 1) / entriesPerLong;

        writer.beginLongArray(DATA_NAME, longsCount);

        // Direct palette index mapping with zero-allocation streaming
        int voxelIdx = 0;
        for (int l = 0; l < longsCount; l++) {
            long word = 0L;
            int countInWord = Math.min(entriesPerLong, VoxelSection.VOXEL_COUNT - voxelIdx);
            for (int e = 0; e < countInWord; e++) {
                int blockId = voxelIds[voxelIdx++];
                int palIdx = lookup[blockId];
                word |= ((long) palIdx) << (e * bitsPerBlock);
            }
            writer.putRawLong(word);
        }

        // Reset only the slots we touched: O(uniqueCount)
        for (int u = 0; u < uniqueCount; u++) {
            lookup[uniqueIds[u]] = -1;
        }

        writer.endCompound();
    }

    private static void writePaletteEntry(FastNbtWriter writer, int blockId, BlockIdRegistry registry) {
        writer.putRawBytes(registry.getStateDictionary().getPrecompiledPaletteNbt(blockId));
    }

    static void writeSectionBiomes(FastNbtWriter writer, String biome) {
        writer.beginCompound(BIOMES_NAME);
        writer.beginList(PALETTE_NAME, FastNbtReader.TAG_STRING, 1);
        writer.putListString((biome != null && !biome.isEmpty()) ? biome : "minecraft:plains");
        writer.endCompound();
    }

    static void writeHeightmaps(FastNbtWriter writer, Map<Integer, VoxelSection> sections,
                                int minSectionY, int maxSectionY) {
        writer.beginCompound(HEIGHTMAPS_NAME);

        short[] heights = HEIGHTS_BUF.get();
        short minWorldY = (short) (minSectionY << 4);
        Arrays.fill(heights, minWorldY);

        if (sections != null) {
            for (int secY = maxSectionY; secY >= minSectionY; secY--) {
                VoxelSection sec = sections.get(secY);
                if (sec == null || sec.isEmpty()) continue;

                int baseWorldY = secY << 4;
                if (sec.isHomogeneous() && sec.getSingleBlockId() != BlockIdRegistry.AIR_ID) {
                    short topY = (short) (baseWorldY + 16);
                    for (int i = 0; i < 256; i++) {
                        if (heights[i] == minWorldY) {
                            heights[i] = topY;
                        }
                    }
                    continue;
                }

                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int colIdx = x | (z << 4);
                        if (heights[colIdx] > minWorldY) continue; // Already found highest for this column

                        for (int y = 15; y >= 0; y--) {
                            if (sec.isSolid(x, y, z)) {
                                heights[colIdx] = (short) (baseWorldY + y + 1);
                                break;
                            }
                        }
                    }
                }
            }
        }

        // Dynamically compute bit depth: ceil(log2(totalHeight + 1))
        int totalHeight = (maxSectionY - minSectionY + 1) * 16;
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(totalHeight));
        final int entriesPerLong = 64 / bits;
        final int longCount = (256 + entriesPerLong - 1) / entriesPerLong;
        final long mask = (1L << bits) - 1;

        writer.beginLongArray(MOTION_BLOCKING_NAME, longCount);
        int idx = 0;
        for (int l = 0; l < longCount; l++) {
            long word = 0L;
            int count = Math.min(entriesPerLong, 256 - idx);
            for (int e = 0; e < count; e++) {
                int relH = Math.max(0, heights[idx++] - minWorldY);
                word |= ((long) (relH & mask)) << (e * bits);
            }
            writer.putRawLong(word);
        }

        writer.beginLongArray(WORLD_SURFACE_NAME, longCount);
        idx = 0;
        for (int l = 0; l < longCount; l++) {
            long word = 0L;
            int count = Math.min(entriesPerLong, 256 - idx);
            for (int e = 0; e < count; e++) {
                int relH = Math.max(0, heights[idx++] - minWorldY);
                word |= ((long) (relH & mask)) << (e * bits);
            }
            writer.putRawLong(word);
        }

        writer.endCompound();
    }
}
