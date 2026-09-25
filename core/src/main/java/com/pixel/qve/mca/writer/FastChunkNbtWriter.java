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
     * Serializes a complete 1.21.1 Chunk NBT structure into the target FastNbtWriter.
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
            writeSectionBiomes(writer);

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

    private static void writeSectionBlockStates(FastNbtWriter writer, VoxelSection section, BlockIdRegistry registry) {
        writer.beginCompound(BLOCK_STATES_NAME);

        if (section == null || section.isEmpty()) {
            // Homogeneous single-entry air palette
            writer.beginList(PALETTE_NAME, FastNbtReader.TAG_COMPOUND, 1);
            writer.beginListCompound();
            writer.putString(NAME_NAME, AIR_NAME);
            writer.endCompound();
            writer.endCompound();
            return;
        }

        int[] voxelIds = section.getBlockIds();
        // Determine unique block IDs in this 4096-voxel section
        int[] uniqueIds = new int[256];
        int uniqueCount = 0;

        for (int i = 0; i < VoxelSection.VOXEL_COUNT; i++) {
            int id = voxelIds[i];
            boolean found = false;
            for (int u = 0; u < uniqueCount; u++) {
                if (uniqueIds[u] == id) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                if (uniqueCount >= uniqueIds.length) {
                    uniqueIds = Arrays.copyOf(uniqueIds, uniqueIds.length * 2);
                }
                uniqueIds[uniqueCount++] = id;
            }
        }

        if (uniqueCount == 1) {
            // Single-entry homogeneous palette (no data array needed)
            writer.beginList(PALETTE_NAME, FastNbtReader.TAG_COMPOUND, 1);
            writePaletteEntry(writer, uniqueIds[0], registry);
            writer.endCompound();
            return;
        }

        // Multiple entries: write palette + bit-packed data long array
        writer.beginList(PALETTE_NAME, FastNbtReader.TAG_COMPOUND, uniqueCount);
        for (int u = 0; u < uniqueCount; u++) {
            writePaletteEntry(writer, uniqueIds[u], registry);
        }

        // Bit-packing
        int bitsPerBlock = Math.max(4, 32 - Integer.numberOfLeadingZeros(uniqueCount - 1));
        int entriesPerLong = 64 / bitsPerBlock;
        int longsCount = (VoxelSection.VOXEL_COUNT + entriesPerLong - 1) / entriesPerLong;
        long[] data = new long[longsCount];

        // Direct palette index mapping
        int voxelIdx = 0;
        for (int l = 0; l < longsCount; l++) {
            long word = 0L;
            int countInWord = Math.min(entriesPerLong, VoxelSection.VOXEL_COUNT - voxelIdx);
            for (int e = 0; e < countInWord; e++) {
                int blockId = voxelIds[voxelIdx++];
                int palIdx = 0;
                for (int u = 0; u < uniqueCount; u++) {
                    if (uniqueIds[u] == blockId) {
                        palIdx = u;
                        break;
                    }
                }
                word |= ((long) palIdx) << (e * bitsPerBlock);
            }
            data[l] = word;
        }

        writer.putLongArray(DATA_NAME, data);
        writer.endCompound();
    }

    private static void writePaletteEntry(FastNbtWriter writer, int blockId, BlockIdRegistry registry) {
        writer.beginListCompound();

        if (blockId == BlockIdRegistry.AIR_ID) {
            writer.putString(NAME_NAME, AIR_NAME);
            writer.endCompound();
            return;
        }

        BlockStateDictionary dictionary = registry.getStateDictionary();
        String canonical = dictionary.getCanonicalState(blockId);
        if (canonical == null || canonical.isEmpty()) {
            canonical = registry.getName(blockId);
        }
        if (canonical == null || canonical.isEmpty()) {
            canonical = "minecraft:air";
        }

        // Defensive cleaning: strip any vanilla "Block{...}" wrapper if present
        if (canonical.startsWith("Block{")) {
            int closeBrace = canonical.indexOf('}');
            if (closeBrace > 6) {
                String inner = canonical.substring(6, closeBrace);
                String rest = canonical.substring(closeBrace + 1);
                canonical = inner + rest;
            }
        }

        int bracketIdx = canonical.indexOf('[');
        if (bracketIdx == -1) {
            writer.putString(NAME_NAME, canonical);
        } else {
            String name = canonical.substring(0, bracketIdx);
            String props = canonical.substring(bracketIdx + 1, canonical.length() - 1);
            writer.putString(NAME_NAME, name);
            writer.beginCompound(PROPERTIES_NAME);

            String[] pairs = props.split(",");
            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String k = pair.substring(0, eq).trim();
                    String v = pair.substring(eq + 1).trim();
                    writer.putString(k, v);
                }
            }
            writer.endCompound();
        }

        writer.endCompound();
    }

    private static void writeSectionBiomes(FastNbtWriter writer) {
        writer.beginCompound(BIOMES_NAME);
        writer.beginList(PALETTE_NAME, FastNbtReader.TAG_STRING, 1);
        writer.putListString("minecraft:plains");
        writer.endCompound();
    }

    private static void writeHeightmaps(FastNbtWriter writer, Map<Integer, VoxelSection> sections,
                                       int minSectionY, int maxSectionY) {
        writer.beginCompound(HEIGHTMAPS_NAME);

        // Compute highest solid block per column (256 columns: x=0..15, z=0..15)
        short[] heights = new short[256];
        short minWorldY = (short) (minSectionY << 4);
        Arrays.fill(heights, minWorldY);

        if (sections != null) {
            for (int secY = maxSectionY; secY >= minSectionY; secY--) {
                VoxelSection sec = sections.get(secY);
                if (sec == null || sec.isEmpty()) continue;

                int baseWorldY = secY << 4;
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

        // Pack 256 9-bit height values into 37 longs (64 / 9 = 7 entries per long, 256 / 7 = 37 longs)
        long[] packedHeights = packHeightmap(heights, minWorldY);
        writer.putLongArray(MOTION_BLOCKING_NAME, packedHeights);
        writer.putLongArray(WORLD_SURFACE_NAME, packedHeights);

        writer.endCompound();
    }

    private static long[] packHeightmap(short[] heights, short minWorldY) {
        final int bits = 9;
        final int entriesPerLong = 64 / bits; // 7
        final int longCount = (256 + entriesPerLong - 1) / entriesPerLong; // 37
        long[] packed = new long[longCount];

        int idx = 0;
        for (int l = 0; l < longCount; l++) {
            long word = 0L;
            int count = Math.min(entriesPerLong, 256 - idx);
            for (int e = 0; e < count; e++) {
                int relH = Math.max(0, heights[idx++] - minWorldY);
                word |= ((long) (relH & 0x1FF)) << (e * bits);
            }
            packed[l] = word;
        }
        return packed;
    }
}
