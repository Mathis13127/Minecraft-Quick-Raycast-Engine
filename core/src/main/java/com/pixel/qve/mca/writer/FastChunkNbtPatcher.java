package com.pixel.qve.mca.writer;

import com.pixel.qve.mca.FastNbtReader;
import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.state.BlockStatePaletteUnpacker;
import com.pixel.qve.world.VoxelSection;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * High-performance non-destructive NBT patcher for existing Minecraft 1.21.1 chunks.
 * Updates modified VoxelSections and recalculates heightmaps while strictly preserving
 * biomes, block entities, lighting data, carver masks, structures, ticks, and status tags.
 */
public final class FastChunkNbtPatcher {

    private static final byte[] SECTIONS_NAME = "sections".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEIGHTMAPS_NAME = "Heightmaps".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BLOCK_ENTITIES_NAME = "block_entities".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TILE_ENTITIES_NAME = "TileEntities".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BLOCK_STATES_NAME = "block_states".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] Y_NAME = "Y".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BIOMES_NAME = "biomes".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PALETTE_NAME = "palette".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DATA_NAME = "data".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NAME_NAME = "Name".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PROPERTIES_NAME = "Properties".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COORD_X_NAME = "x".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COORD_Y_NAME = "y".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COORD_Z_NAME = "z".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] X_POS_NAME = "xPos".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] Z_POS_NAME = "zPos".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] Y_POS_NAME = "yPos".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STATUS_NAME = "Status".getBytes(StandardCharsets.US_ASCII);
    private static final String FULL_STATUS = "minecraft:full";

    private static final ThreadLocal<VoxelSection> TEMP_SECTION = ThreadLocal.withInitial(VoxelSection::new);
    private static final ThreadLocal<int[]> PALETTE_REUSE_BUF = ThreadLocal.withInitial(() -> new int[4096]);
    private static final ThreadLocal<long[]> DATA_REUSE_BUF = ThreadLocal.withInitial(() -> new long[1024]);

    private FastChunkNbtPatcher() {}

    /**
     * Patches an existing decompressed chunk NBT compound with updated sections and block entities.
     *
     * @param existingNbt      Decompressed NBT buffer of the existing chunk
     * @param chunkX           World chunk X
     * @param chunkZ           World chunk Z
     * @param minSectionY      Minimum section Y for dimension
     * @param maxSectionY      Maximum section Y for dimension
     * @param registry         BlockIdRegistry for resolving block states
     * @param modifiedSections Map of section Y to VoxelSection to update
     * @param blockEntities    Map of packed coordinates to raw block entity NBT (or null)
     * @param targetWriter     Output FastNbtWriter receiving the patched compound
     */
    public static void patchChunk(ByteBuffer existingNbt, int chunkX, int chunkZ,
                                  int minSectionY, int maxSectionY,
                                  BlockIdRegistry registry,
                                  Map<Integer, VoxelSection> modifiedSections,
                                  Map<Long, byte[]> blockEntities,
                                  FastNbtWriter targetWriter) {
        patchChunk(existingNbt, chunkX, chunkZ, minSectionY, maxSectionY, registry, modifiedSections, (PrimitiveMutationBuffer) null, blockEntities, targetWriter);
    }

    public static void patchChunk(ByteBuffer existingNbt, int chunkX, int chunkZ,
                                  int minSectionY, int maxSectionY,
                                  BlockIdRegistry registry,
                                  Map<Integer, VoxelSection> wholeSections,
                                  List<McaWriteCoordinator.VoxelMutation> mutations,
                                  Map<Long, byte[]> blockEntities,
                                  FastNbtWriter targetWriter) {
        PrimitiveMutationBuffer buffer = null;
        if (mutations != null && !mutations.isEmpty()) {
            buffer = new PrimitiveMutationBuffer(mutations.size());
            for (int i = 0, size = mutations.size(); i < size; i++) {
                McaWriteCoordinator.VoxelMutation m = mutations.get(i);
                buffer.add(m.localX(), m.worldY(), m.localZ(), m.targetBlockId(), m.filterBlockId(), m.rawNbt());
            }
        }
        patchChunk(existingNbt, chunkX, chunkZ, minSectionY, maxSectionY, registry, wholeSections, buffer, blockEntities, targetWriter);
    }

    public static void patchChunk(ByteBuffer existingNbt, int chunkX, int chunkZ,
                                  int minSectionY, int maxSectionY,
                                  BlockIdRegistry registry,
                                  Map<Integer, VoxelSection> wholeSections,
                                  PrimitiveMutationBuffer mutations,
                                  Map<Long, byte[]> blockEntities,
                                  FastNbtWriter targetWriter) {
        Objects.requireNonNull(existingNbt, "existingNbt cannot be null");
        Objects.requireNonNull(targetWriter, "targetWriter cannot be null");
        Objects.requireNonNull(registry, "BlockIdRegistry cannot be null");

        ByteBuffer buf = existingNbt.duplicate();
        if (!buf.hasRemaining()) {
            throw new IllegalArgumentException("Existing NBT buffer is empty");
        }

        byte rootType = buf.get();
        if (rootType != FastNbtReader.TAG_COMPOUND) {
            throw new IllegalArgumentException("Expected root tag TAG_Compound (10), got: " + rootType);
        }

        int rootNameLen = buf.getShort() & 0xFFFF;
        int rootNamePos = buf.position();
        buf.position(rootNamePos + rootNameLen);
        String rootName = FastNbtReader.decodeStringDirect(buf, rootNamePos, rootNameLen);

        targetWriter.beginRootCompound(rootName);

        boolean wroteSections = false;
        boolean wroteHeightmaps = false;
        boolean wroteXPos = false;
        boolean wroteZPos = false;
        boolean wroteYPos = false;
        boolean wroteStatus = false;

        while (buf.hasRemaining()) {
            int tagStart = buf.position();
            byte tagType = buf.get();
            if (tagType == FastNbtReader.TAG_END) {
                break;
            }

            int nameLen = buf.getShort() & 0xFFFF;
            int namePos = buf.position();
            buf.position(namePos + nameLen);

            if (tagType == FastNbtReader.TAG_INT && FastNbtReader.matches(buf, namePos, nameLen, X_POS_NAME)) {
                FastNbtReader.skipTagPayload(buf, tagType);
                targetWriter.putInt(X_POS_NAME, chunkX);
                wroteXPos = true;
            } else if (tagType == FastNbtReader.TAG_INT && FastNbtReader.matches(buf, namePos, nameLen, Z_POS_NAME)) {
                FastNbtReader.skipTagPayload(buf, tagType);
                targetWriter.putInt(Z_POS_NAME, chunkZ);
                wroteZPos = true;
            } else if (tagType == FastNbtReader.TAG_INT && FastNbtReader.matches(buf, namePos, nameLen, Y_POS_NAME)) {
                FastNbtReader.skipTagPayload(buf, tagType);
                targetWriter.putInt(Y_POS_NAME, minSectionY);
                wroteYPos = true;
            } else if (tagType == FastNbtReader.TAG_STRING && FastNbtReader.matches(buf, namePos, nameLen, STATUS_NAME)) {
                FastNbtReader.skipTagPayload(buf, tagType);
                targetWriter.putString(STATUS_NAME, FULL_STATUS);
                wroteStatus = true;
            } else if (tagType == FastNbtReader.TAG_LIST && FastNbtReader.matches(buf, namePos, nameLen, SECTIONS_NAME)) {
                patchSectionsList(buf, targetWriter, wholeSections, mutations, minSectionY, maxSectionY, registry);
                wroteSections = true;
            } else if (tagType == FastNbtReader.TAG_COMPOUND && FastNbtReader.matches(buf, namePos, nameLen, HEIGHTMAPS_NAME)) {
                FastNbtReader.skipTagPayload(buf, tagType);
                FastChunkNbtWriter.writeHeightmaps(targetWriter, wholeSections, minSectionY, maxSectionY);
                wroteHeightmaps = true;
            } else if (tagType == FastNbtReader.TAG_LIST &&
                    (FastNbtReader.matches(buf, namePos, nameLen, BLOCK_ENTITIES_NAME) || FastNbtReader.matches(buf, namePos, nameLen, TILE_ENTITIES_NAME)) &&
                    blockEntities != null && !blockEntities.isEmpty()) {
                patchBlockEntitiesList(buf, targetWriter, blockEntities);
            } else {
                FastNbtReader.skipTagPayload(buf, tagType);
                int tagEnd = buf.position();
                ByteBuffer slice = buf.duplicate();
                slice.position(tagStart);
                slice.limit(tagEnd);
                targetWriter.putRawBytes(slice);
            }
        }

        if (!wroteXPos) {
            targetWriter.putInt(X_POS_NAME, chunkX);
        }
        if (!wroteZPos) {
            targetWriter.putInt(Z_POS_NAME, chunkZ);
        }
        if (!wroteYPos) {
            targetWriter.putInt(Y_POS_NAME, minSectionY);
        }
        if (!wroteStatus) {
            targetWriter.putString(STATUS_NAME, FULL_STATUS);
        }
        if (!wroteSections && wholeSections != null && !wholeSections.isEmpty()) {
            writeFallbackSections(targetWriter, wholeSections, minSectionY, maxSectionY, registry);
        }
        if (!wroteHeightmaps) {
            FastChunkNbtWriter.writeHeightmaps(targetWriter, wholeSections, minSectionY, maxSectionY);
        }

        targetWriter.endCompound();
    }

    private static final ThreadLocal<int[]> SECTION_Y_BUF = ThreadLocal.withInitial(() -> new int[64]);

    private static void patchSectionsList(ByteBuffer buf, FastNbtWriter writer,
                                          Map<Integer, VoxelSection> wholeSections,
                                          PrimitiveMutationBuffer mutations,
                                          int minSectionY, int maxSectionY,
                                          BlockIdRegistry registry) {
        byte elemType = buf.get();
        if (elemType != FastNbtReader.TAG_COMPOUND) {
            int count = buf.getInt();
            for (int i = 0; i < count; i++) {
                FastNbtReader.skipTagPayload(buf, elemType);
            }
            writeFallbackSections(writer, wholeSections, minSectionY, maxSectionY, registry);
            return;
        }

        int origCount = buf.getInt();
        int listStart = buf.position();
        int[] sectionYPerIndex = SECTION_Y_BUF.get();
        if (sectionYPerIndex.length < origCount) {
            sectionYPerIndex = new int[Math.max(origCount, sectionYPerIndex.length * 2)];
            SECTION_Y_BUF.set(sectionYPerIndex);
        }
        long existingYMask = 0L;

        // Pass 1: Pre-scan existing section Y levels
        for (int i = 0; i < origCount; i++) {
            int currentY = Integer.MIN_VALUE;
            while (buf.hasRemaining()) {
                byte childType = buf.get();
                if (childType == FastNbtReader.TAG_END) break;

                int nameLen = buf.getShort() & 0xFFFF;
                int namePos = buf.position();
                buf.position(namePos + nameLen);

                if (childType == FastNbtReader.TAG_BYTE && FastNbtReader.matches(buf, namePos, nameLen, Y_NAME)) {
                    currentY = buf.get();
                } else {
                    FastNbtReader.skipTagPayload(buf, childType);
                }
            }
            sectionYPerIndex[i] = currentY;
            if (currentY != Integer.MIN_VALUE) {
                int bit = currentY + 16;
                if (bit >= 0 && bit < 64) {
                    existingYMask |= (1L << bit);
                }
            }
        }

        int newlyAddedCount = 0;
        if (wholeSections != null) {
            for (int secY : wholeSections.keySet()) {
                int bit = secY + 16;
                boolean exists = (bit >= 0 && bit < 64) && ((existingYMask & (1L << bit)) != 0L);
                if (secY >= minSectionY && secY <= maxSectionY && !exists) {
                    newlyAddedCount++;
                    existingYMask |= (1L << bit);
                }
            }
        }
        int mutationsMask = (mutations != null) ? mutations.getModifiedSectionMask() : 0;
        if (mutations != null && !mutations.isEmpty()) {
            for (int i = 0, size = mutations.size(); i < size; i++) {
                int sMin = mutations.minSectionY(i);
                int sMax = mutations.maxSectionY(i);
                for (int secY = sMin; secY <= sMax; secY++) {
                    if (wholeSections != null && wholeSections.containsKey(secY)) continue;
                    int bit = secY + 16;
                    boolean exists = (bit >= 0 && bit < 64) && ((existingYMask & (1L << bit)) != 0L);
                    if (secY >= minSectionY && secY <= maxSectionY && !exists) {
                        newlyAddedCount++;
                        existingYMask |= (1L << bit);
                    }
                }
            }
        }

        int totalCount = origCount + newlyAddedCount;
        writer.beginList(SECTIONS_NAME, FastNbtReader.TAG_COMPOUND, totalCount);

        // Pass 2: Stream out patched sections preserving non-block tags
        buf.position(listStart);
        for (int i = 0; i < origCount; i++) {
            writer.beginListCompound();
            int secY = sectionYPerIndex[i];
            boolean hasBlockStates = false;
            boolean hasBiomes = false;

            while (buf.hasRemaining()) {
                int childStart = buf.position();
                byte childType = buf.get();
                if (childType == FastNbtReader.TAG_END) break;

                int nameLen = buf.getShort() & 0xFFFF;
                int namePos = buf.position();
                buf.position(namePos + nameLen);

                if (childType == FastNbtReader.TAG_BYTE && FastNbtReader.matches(buf, namePos, nameLen, Y_NAME)) {
                    buf.get(); // Consume Y byte from buffer
                    writer.putByte(Y_NAME, (byte) secY);
                } else if (FastNbtReader.matches(buf, namePos, nameLen, BLOCK_STATES_NAME)) {
                    hasBlockStates = true;
                    if (secY != Integer.MIN_VALUE && wholeSections != null && wholeSections.containsKey(secY)) {
                        FastNbtReader.skipTagPayload(buf, childType);
                        FastChunkNbtWriter.writeSectionBlockStates(writer, wholeSections.get(secY), registry);
                    } else if (secY != Integer.MIN_VALUE && mutations != null && (secY + 16 >= 0 && secY + 16 < 32) && ((mutationsMask & (1 << (secY + 16))) != 0)) {
                        // Section has mutations: check if a box covers it completely and unconditionally
                        int touchingCount = 0;
                        int lastTouchingIndex = -1;
                        for (int mi = 0, mSize = mutations.size(); mi < mSize; mi++) {
                            if (mutations.intersectsSection(mi, secY)) {
                                touchingCount++;
                                lastTouchingIndex = mi;
                            }
                        }

                        if (touchingCount == 1 && lastTouchingIndex >= 0
                                && mutations.coversSectionCompletely(lastTouchingIndex, secY)
                                && mutations.filterBlockId(lastTouchingIndex) < 0
                                && !mutations.hasNbt(lastTouchingIndex)) {
                            // O(1) homogeneous section replacement
                            FastNbtReader.skipTagPayload(buf, childType);
                            FastChunkNbtWriter.writeSectionBlockStates(writer, VoxelSection.createHomogeneous(mutations.targetBlockId(lastTouchingIndex), true), registry);
                        } else {
                            // Parse palette & data in-place using reusable pools, modify, and stream updated block_states
                            int pCount = 0;
                            int dCount = 0;
                            int[] paletteBuf = PALETTE_REUSE_BUF.get();
                            long[] dataBuf = DATA_REUSE_BUF.get();
                            while (true) {
                                byte bsType = buf.get();
                                if (bsType == FastNbtReader.TAG_END) break;
                                int bsNameLen = buf.getShort() & 0xFFFF;
                                int bsNamePos = buf.position();
                                buf.position(bsNamePos + bsNameLen);
                                if (FastNbtReader.matches(buf, bsNamePos, bsNameLen, PALETTE_NAME) && bsType == FastNbtReader.TAG_LIST) {
                                    buf.get(); // elemType
                                    pCount = buf.getInt();
                                    if (pCount > paletteBuf.length) {
                                        paletteBuf = new int[Math.max(pCount, paletteBuf.length * 2)];
                                        PALETTE_REUSE_BUF.set(paletteBuf);
                                    }
                                    for (int pi = 0; pi < pCount; pi++) {
                                        paletteBuf[pi] = parsePaletteEntry(buf, registry);
                                    }
                                } else if (FastNbtReader.matches(buf, bsNamePos, bsNameLen, DATA_NAME) && bsType == FastNbtReader.TAG_LONG_ARRAY) {
                                    dCount = FastNbtReader.readLongArrayInto(buf, dataBuf);
                                } else {
                                    FastNbtReader.skipTagPayload(buf, bsType);
                                }
                            }

                            VoxelSection tempSec = TEMP_SECTION.get();
                            if (pCount > 0) {
                                BlockStatePaletteUnpacker.unpackInto(paletteBuf, pCount, dataBuf, dCount, tempSec, null);
                            } else {
                                tempSec.clear();
                            }
                            int secMinY = secY << 4;
                            int secMaxY = secMinY + 15;
                            for (int mi = 0, mSize = mutations.size(); mi < mSize; mi++) {
                                if (mutations.intersectsSection(mi, secY)) {
                                    int bMinX = mutations.minX(mi);
                                    int bMaxX = mutations.maxX(mi);
                                    int bMinZ = mutations.minZ(mi);
                                    int bMaxZ = mutations.maxZ(mi);
                                    int bMinY = Math.max(secMinY, mutations.minY(mi));
                                    int bMaxY = Math.min(secMaxY, mutations.maxY(mi));
                                    int targetId = mutations.targetBlockId(mi);
                                    int filterId = mutations.filterBlockId(mi);
                                    int localMinY = bMinY & 15;
                                    int localMaxY = bMaxY & 15;

                                    for (int y = localMinY; y <= localMaxY; y++) {
                                        for (int z = bMinZ; z <= bMaxZ; z++) {
                                            for (int x = bMinX; x <= bMaxX; x++) {
                                                int curId = tempSec.getBlockId(x, y, z);
                                                if (filterId < 0 || curId == filterId) {
                                                    tempSec.setBlock(x, y, z, targetId);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            FastChunkNbtWriter.writeSectionBlockStates(writer, tempSec, registry);
                        }
                    } else {
                        // Untouched section: fast zero-copy raw slice!
                        FastNbtReader.skipTagPayload(buf, childType);
                        ByteBuffer slice = buf.duplicate();
                        slice.position(childStart);
                        slice.limit(buf.position());
                        writer.putRawBytes(slice);
                    }
                } else {
                    if (FastNbtReader.matches(buf, namePos, nameLen, BIOMES_NAME)) {
                        hasBiomes = true;
                    }
                    FastNbtReader.skipTagPayload(buf, childType);
                    int childEnd = buf.position();
                    ByteBuffer slice = buf.duplicate();
                    slice.position(childStart);
                    slice.limit(childEnd);
                    writer.putRawBytes(slice);
                }
            }

            if (!hasBlockStates && secY != Integer.MIN_VALUE) {
                if (wholeSections != null && wholeSections.containsKey(secY)) {
                    FastChunkNbtWriter.writeSectionBlockStates(writer, wholeSections.get(secY), registry);
                } else if (mutations != null && (secY + 16 >= 0 && secY + 16 < 32) && ((mutationsMask & (1 << (secY + 16))) != 0)) {
                    VoxelSection tempSec = TEMP_SECTION.get();
                    tempSec.clear();
                    int secMinY = secY << 4;
                    int secMaxY = secMinY + 15;
                    for (int mi = 0, mSize = mutations.size(); mi < mSize; mi++) {
                        if (mutations.intersectsSection(mi, secY)) {
                            int bMinX = mutations.minX(mi);
                            int bMaxX = mutations.maxX(mi);
                            int bMinZ = mutations.minZ(mi);
                            int bMaxZ = mutations.maxZ(mi);
                            int bMinY = Math.max(secMinY, mutations.minY(mi));
                            int bMaxY = Math.min(secMaxY, mutations.maxY(mi));
                            int targetId = mutations.targetBlockId(mi);
                            int filterId = mutations.filterBlockId(mi);
                            int localMinY = bMinY & 15;
                            int localMaxY = bMaxY & 15;

                            for (int y = localMinY; y <= localMaxY; y++) {
                                for (int z = bMinZ; z <= bMaxZ; z++) {
                                    for (int x = bMinX; x <= bMaxX; x++) {
                                        int curId = tempSec.getBlockId(x, y, z);
                                        if (filterId < 0 || curId == filterId) {
                                            tempSec.setBlock(x, y, z, targetId);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    FastChunkNbtWriter.writeSectionBlockStates(writer, tempSec, registry);
                }
            }
            if (!hasBiomes) {
                FastChunkNbtWriter.writeSectionBiomes(writer, "minecraft:plains");
            }

            writer.endCompound();
        }

        // Pass 3: Add brand new sections that did not previously exist
        if (wholeSections != null && newlyAddedCount > 0) {
            for (Map.Entry<Integer, VoxelSection> entry : wholeSections.entrySet()) {
                int secY = entry.getKey();
                int bit = secY + 16;
                boolean exists = (bit >= 0 && bit < 64) && ((existingYMask & (1L << bit)) != 0L);
                if (secY >= minSectionY && secY <= maxSectionY && !exists) {
                    writer.beginListCompound();
                    writer.putByte(Y_NAME, (byte) secY);
                    FastChunkNbtWriter.writeSectionBlockStates(writer, entry.getValue(), registry);
                    FastChunkNbtWriter.writeSectionBiomes(writer, "minecraft:plains");
                    writer.endCompound();
                }
            }
        }
        if (mutations != null && newlyAddedCount > 0) {
            long handledNewSecs = 0L;
            for (int i = 0, size = mutations.size(); i < size; i++) {
                int sMin = mutations.minSectionY(i);
                int sMax = mutations.maxSectionY(i);
                for (int secY = sMin; secY <= sMax; secY++) {
                    if (wholeSections != null && wholeSections.containsKey(secY)) continue;
                    int bit = secY + 16;
                    boolean exists = (bit >= 0 && bit < 64) && ((existingYMask & (1L << bit)) != 0L);
                    if (secY >= minSectionY && secY <= maxSectionY && !exists) {
                        if ((handledNewSecs & (1L << bit)) == 0L) {
                            handledNewSecs |= (1L << bit);
                            writer.beginListCompound();
                            writer.putByte(Y_NAME, (byte) secY);

                            int secMinY = secY << 4;
                            int secMaxY = secMinY + 15;
                            if (mutations.coversSectionCompletely(i, secY) && mutations.filterBlockId(i) < 0 && !mutations.hasNbt(i)) {
                                FastChunkNbtWriter.writeSectionBlockStates(writer, VoxelSection.createHomogeneous(mutations.targetBlockId(i), true), registry);
                            } else {
                                VoxelSection tempSec = TEMP_SECTION.get();
                                tempSec.clear();
                                for (int mi = 0; mi < size; mi++) {
                                    if (mutations.intersectsSection(mi, secY)) {
                                        int bMinX = mutations.minX(mi);
                                        int bMaxX = mutations.maxX(mi);
                                        int bMinZ = mutations.minZ(mi);
                                        int bMaxZ = mutations.maxZ(mi);
                                        int bMinY = Math.max(secMinY, mutations.minY(mi));
                                        int bMaxY = Math.min(secMaxY, mutations.maxY(mi));
                                        int targetId = mutations.targetBlockId(mi);
                                        int filterId = mutations.filterBlockId(mi);
                                        int localMinY = bMinY & 15;
                                        int localMaxY = bMaxY & 15;

                                        for (int y = localMinY; y <= localMaxY; y++) {
                                            for (int z = bMinZ; z <= bMaxZ; z++) {
                                                for (int x = bMinX; x <= bMaxX; x++) {
                                                    int curId = tempSec.getBlockId(x, y, z);
                                                    if (filterId < 0 || curId == filterId) {
                                                        tempSec.setBlock(x, y, z, targetId);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                FastChunkNbtWriter.writeSectionBlockStates(writer, tempSec, registry);
                            }
                            FastChunkNbtWriter.writeSectionBiomes(writer, "minecraft:plains");
                            writer.endCompound();
                        }
                    }
                }
            }
        }
    }

    private static int parsePaletteEntry(ByteBuffer buf, BlockIdRegistry registry) {
        int strPos = -1;
        int strLen = 0;
        int propsPos = -1;
        int propsCompoundLen = 0;

        while (true) {
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

    private static void writeFallbackSections(FastNbtWriter writer, Map<Integer, VoxelSection> sections,
                                              int minSectionY, int maxSectionY, BlockIdRegistry registry) {
        int count = maxSectionY - minSectionY + 1;
        writer.beginList(SECTIONS_NAME, FastNbtReader.TAG_COMPOUND, count);
        for (int secY = minSectionY; secY <= maxSectionY; secY++) {
            writer.beginListCompound();
            writer.putByte(Y_NAME, (byte) secY);
            VoxelSection s = (sections != null) ? sections.get(secY) : null;
            FastChunkNbtWriter.writeSectionBlockStates(writer, s, registry);
            FastChunkNbtWriter.writeSectionBiomes(writer, "minecraft:plains");
            writer.endCompound();
        }
    }

    private static void patchBlockEntitiesList(ByteBuffer buf, FastNbtWriter writer, Map<Long, byte[]> blockEntities) {
        byte elemType = buf.get();
        int count = buf.getInt();
        if (elemType != FastNbtReader.TAG_COMPOUND) {
            for (int i = 0; i < count; i++) {
                FastNbtReader.skipTagPayload(buf, elemType);
            }
            writeBlockEntities(writer, blockEntities);
            return;
        }

        Map<Long, byte[]> remainingNew = new HashMap<>(blockEntities);
        List<ByteBuffer> preservedCompounds = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            int compStart = buf.position();
            int x = Integer.MIN_VALUE, y = Integer.MIN_VALUE, z = Integer.MIN_VALUE;

            while (buf.hasRemaining()) {
                byte childType = buf.get();
                if (childType == FastNbtReader.TAG_END) break;

                int nameLen = buf.getShort() & 0xFFFF;
                int namePos = buf.position();
                buf.position(namePos + nameLen);

                if (childType == FastNbtReader.TAG_INT) {
                    if (FastNbtReader.matches(buf, namePos, nameLen, COORD_X_NAME)) {
                        x = buf.getInt();
                        continue;
                    } else if (FastNbtReader.matches(buf, namePos, nameLen, COORD_Y_NAME)) {
                        y = buf.getInt();
                        continue;
                    } else if (FastNbtReader.matches(buf, namePos, nameLen, COORD_Z_NAME)) {
                        z = buf.getInt();
                        continue;
                    }
                }
                FastNbtReader.skipTagPayload(buf, childType);
            }

            int compEnd = buf.position();
            long key = packKey(x & 15, y, z & 15);
            if (remainingNew.containsKey(key)) {
                byte[] replacement = remainingNew.remove(key);
                if (replacement != null && replacement.length > 0) {
                    preservedCompounds.add(ByteBuffer.wrap(replacement));
                }
            } else {
                ByteBuffer slice = buf.duplicate();
                slice.position(compStart);
                slice.limit(compEnd);
                preservedCompounds.add(slice);
            }
        }

        for (byte[] newBe : remainingNew.values()) {
            if (newBe != null && newBe.length > 0) {
                preservedCompounds.add(ByteBuffer.wrap(newBe));
            }
        }

        writer.beginList(BLOCK_ENTITIES_NAME, FastNbtReader.TAG_COMPOUND, preservedCompounds.size());
        for (ByteBuffer slice : preservedCompounds) {
            writer.putRawBytes(slice);
        }
    }

    private static void writeBlockEntities(FastNbtWriter writer, Map<Long, byte[]> blockEntities) {
        int count = (blockEntities != null) ? blockEntities.size() : 0;
        writer.beginList(BLOCK_ENTITIES_NAME, FastNbtReader.TAG_COMPOUND, count);
        if (count > 0) {
            for (byte[] rawBe : blockEntities.values()) {
                if (rawBe != null && rawBe.length > 0) {
                    writer.putRawBytes(rawBe);
                }
            }
        }
    }

    private static long packKey(int x, int y, int z) {
        return (((long) (y & 0xFFFF)) << 8) | ((z & 0xF) << 4) | (x & 0xF);
    }
}
