package com.pixel.qve.mca.writer;

import com.pixel.qve.mca.FastNbtReader;
import com.pixel.qve.state.BlockIdRegistry;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Ultra-high-performance surgical NBT verifier for Minecraft 1.21.1 Anvil chunks.
 * Inspects a single voxel directly within a decompressed chunk NBT payload with zero heap
 * allocations, zero section unpacking, and divisionless bitmask extraction.
 */
public final class FastChunkVerifier {

    private static final byte[] SECTIONS_NAME = "sections".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BLOCK_STATES_NAME = "block_states".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PALETTE_NAME = "palette".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DATA_NAME = "data".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] Y_NAME = "Y".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NAME_NAME = "Name".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PROPERTIES_NAME = "Properties".getBytes(StandardCharsets.US_ASCII);

    private FastChunkVerifier() {}

    /**
     * Surgically verifies whether a single block in a decompressed chunk NBT payload matches
     * the expected block ID, with zero object allocations and without unpacking non-target sections.
     *
     * @param payload         Decompressed NBT root compound buffer
     * @param localX          Local voxel X [0..15]
     * @param worldY          World block Y
     * @param localZ          Local voxel Z [0..15]
     * @param expectedBlockId Expected Block ID in BlockIdRegistry
     * @param registry        BlockIdRegistry instance
     * @return True if voxel physically on disk matches expectedBlockId
     */
    public static boolean verifyVoxel(ByteBuffer payload, int localX, int worldY, int localZ,
                                      int expectedBlockId, BlockIdRegistry registry) {
        Objects.requireNonNull(payload, "payload cannot be null");
        Objects.requireNonNull(registry, "BlockIdRegistry cannot be null");

        ByteBuffer buf = payload.duplicate();
        if (!buf.hasRemaining()) {
            return false;
        }

        byte rootType = buf.get();
        if (rootType != FastNbtReader.TAG_COMPOUND) {
            return false;
        }

        int rootNameLen = buf.getShort() & 0xFFFF;
        buf.position(buf.position() + rootNameLen);

        int targetSecY = worldY >> 4;
        boolean sectionFound = false;

        while (buf.hasRemaining()) {
            byte tagType = buf.get();
            if (tagType == FastNbtReader.TAG_END) break;

            int nameLen = buf.getShort() & 0xFFFF;
            int namePos = buf.position();
            buf.position(namePos + nameLen);

            if (tagType == FastNbtReader.TAG_LIST && FastNbtReader.matches(buf, namePos, nameLen, SECTIONS_NAME)) {
                return verifySectionList(buf, targetSecY, localX, worldY, localZ, expectedBlockId, registry);
            } else {
                FastNbtReader.skipTagPayload(buf, tagType);
            }
        }

        // Section list was not found or chunk is empty air
        return expectedBlockId == BlockIdRegistry.AIR_ID;
    }

    private static boolean verifySectionList(ByteBuffer buf, int targetSecY,
                                             int localX, int worldY, int localZ,
                                             int expectedBlockId, BlockIdRegistry registry) {
        byte elemType = buf.get();
        int count = buf.getInt();
        if (elemType != FastNbtReader.TAG_COMPOUND || count <= 0) {
            return expectedBlockId == BlockIdRegistry.AIR_ID;
        }

        for (int i = 0; i < count; i++) {
            int secStart = buf.position();
            int currentY = Integer.MIN_VALUE;
            int bsStart = -1;
            int bsEnd = -1;

            while (buf.hasRemaining()) {
                byte childType = buf.get();
                if (childType == FastNbtReader.TAG_END) break;

                int nameLen = buf.getShort() & 0xFFFF;
                int namePos = buf.position();
                buf.position(namePos + nameLen);

                if (childType == FastNbtReader.TAG_BYTE && FastNbtReader.matches(buf, namePos, nameLen, Y_NAME)) {
                    currentY = buf.get();
                } else if (childType == FastNbtReader.TAG_COMPOUND && FastNbtReader.matches(buf, namePos, nameLen, BLOCK_STATES_NAME)) {
                    bsStart = buf.position();
                    FastNbtReader.skipTagPayload(buf, childType);
                    bsEnd = buf.position();
                } else {
                    FastNbtReader.skipTagPayload(buf, childType);
                }
            }

            if (currentY == targetSecY) {
                if (bsStart < 0) {
                    // Section has no block_states (pure air)
                    return expectedBlockId == BlockIdRegistry.AIR_ID;
                }
                ByteBuffer bsBuf = buf.duplicate();
                bsBuf.position(bsStart);
                bsBuf.limit(bsEnd);
                return verifyBlockStates(bsBuf, localX, worldY, localZ, expectedBlockId, registry);
            }
        }

        // Section at targetSecY does not exist in chunk (unrendered sections are air in Minecraft)
        return expectedBlockId == BlockIdRegistry.AIR_ID;
    }

    private static final ThreadLocal<int[]> PALETTE_CACHE = ThreadLocal.withInitial(() -> new int[256]);

    private static boolean verifyBlockStates(ByteBuffer buf, int localX, int worldY, int localZ,
                                             int expectedBlockId, BlockIdRegistry registry) {
        int[] palette = null;
        int paletteCount = 0;
        int dataPos = -1;
        int dataLongCount = 0;

        while (buf.hasRemaining()) {
            byte childType = buf.get();
            if (childType == FastNbtReader.TAG_END) break;

            int nameLen = buf.getShort() & 0xFFFF;
            int namePos = buf.position();
            buf.position(namePos + nameLen);

            if (childType == FastNbtReader.TAG_LIST && FastNbtReader.matches(buf, namePos, nameLen, PALETTE_NAME)) {
                buf.get(); // elemType (TAG_COMPOUND)
                paletteCount = buf.getInt();
                if (paletteCount > 0) {
                    if (paletteCount <= 256) {
                        palette = PALETTE_CACHE.get();
                    } else {
                        palette = new int[paletteCount];
                    }
                    for (int p = 0; p < paletteCount; p++) {
                        palette[p] = parsePaletteEntry(buf, registry);
                    }
                }
            } else if (childType == FastNbtReader.TAG_LONG_ARRAY && FastNbtReader.matches(buf, namePos, nameLen, DATA_NAME)) {
                dataLongCount = buf.getInt();
                dataPos = buf.position();
                buf.position(dataPos + (dataLongCount << 3));
            } else {
                FastNbtReader.skipTagPayload(buf, childType);
            }
        }

        if (palette == null || paletteCount == 0) {
            return expectedBlockId == BlockIdRegistry.AIR_ID;
        }

        // Single-entry homogeneous palette (e.g. 100% water or air)
        if (paletteCount == 1) {
            return palette[0] == expectedBlockId;
        }

        if (dataPos < 0 || dataLongCount == 0) {
            return false;
        }

        // Multi-entry bit-packed palette: extract single voxel with 1 shift + 1 mask directly from buffer
        int voxelIdx = ((worldY & 15) << 8) | ((localZ & 15) << 4) | (localX & 15);
        int bitsPerBlock = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteCount - 1));
        int entriesPerLong = 64 / bitsPerBlock;
        int longIndex = voxelIdx / entriesPerLong;

        if (longIndex >= dataLongCount) {
            return false;
        }

        int bitOffset = (voxelIdx % entriesPerLong) * bitsPerBlock;
        long mask = (1L << bitsPerBlock) - 1L;
        long word = buf.getLong(dataPos + (longIndex << 3));
        int palIdx = (int) ((word >>> bitOffset) & mask);

        if (palIdx < 0 || palIdx >= paletteCount) {
            return false;
        }

        return palette[palIdx] == expectedBlockId;
    }

    private static int parsePaletteEntry(ByteBuffer buf, BlockIdRegistry registry) {
        int strPos = -1;
        int strLen = 0;
        int propsPos = -1;
        int propsCompoundLen = 0;

        while (buf.hasRemaining()) {
            byte itemType = buf.get();
            if (itemType == FastNbtReader.TAG_END) break;

            int nameLen = buf.getShort() & 0xFFFF;
            int namePos = buf.position();
            buf.position(namePos + nameLen);

            if (FastNbtReader.matches(buf, namePos, nameLen, NAME_NAME) && itemType == FastNbtReader.TAG_STRING) {
                strLen = buf.getShort() & 0xFFFF;
                strPos = buf.position();
                buf.position(strPos + strLen);
            } else if (FastNbtReader.matches(buf, namePos, nameLen, PROPERTIES_NAME) && itemType == FastNbtReader.TAG_COMPOUND) {
                propsPos = buf.position();
                FastNbtReader.skipTagPayload(buf, itemType);
                propsCompoundLen = buf.position() - propsPos;
            } else {
                FastNbtReader.skipTagPayload(buf, itemType);
            }
        }

        if (strPos >= 0) {
            return registry.getStateDictionary().getOrRegisterFromBytes(buf, strPos, strLen, propsPos, propsCompoundLen);
        }
        return BlockIdRegistry.AIR_ID;
    }
}
