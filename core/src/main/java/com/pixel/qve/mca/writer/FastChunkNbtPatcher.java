package com.pixel.qve.mca.writer;

import com.pixel.qve.mca.FastNbtReader;
import com.pixel.qve.state.BlockIdRegistry;
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
    private static final byte[] COORD_X_NAME = "x".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COORD_Y_NAME = "y".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COORD_Z_NAME = "z".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] X_POS_NAME = "xPos".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] Z_POS_NAME = "zPos".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] Y_POS_NAME = "yPos".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STATUS_NAME = "Status".getBytes(StandardCharsets.US_ASCII);
    private static final String FULL_STATUS = "minecraft:full";

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
                patchSectionsList(buf, targetWriter, modifiedSections, minSectionY, maxSectionY, registry);
                wroteSections = true;
            } else if (tagType == FastNbtReader.TAG_COMPOUND && FastNbtReader.matches(buf, namePos, nameLen, HEIGHTMAPS_NAME)) {
                FastNbtReader.skipTagPayload(buf, tagType);
                FastChunkNbtWriter.writeHeightmaps(targetWriter, modifiedSections, minSectionY, maxSectionY);
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

        if (!wroteSections && modifiedSections != null && !modifiedSections.isEmpty()) {
            int sectionCount = (maxSectionY - minSectionY + 1);
            targetWriter.beginList(SECTIONS_NAME, FastNbtReader.TAG_COMPOUND, sectionCount);
            for (int secY = minSectionY; secY <= maxSectionY; secY++) {
                targetWriter.beginListCompound();
                targetWriter.putByte(Y_NAME, (byte) secY);
                VoxelSection sec = modifiedSections.get(secY);
                FastChunkNbtWriter.writeSectionBlockStates(targetWriter, sec, registry);
                FastChunkNbtWriter.writeSectionBiomes(targetWriter, "minecraft:plains");
                targetWriter.endCompound();
            }
        }

        if (!wroteHeightmaps) {
            FastChunkNbtWriter.writeHeightmaps(targetWriter, modifiedSections, minSectionY, maxSectionY);
        }

        targetWriter.endCompound();
    }

    private static void patchSectionsList(ByteBuffer buf, FastNbtWriter writer,
                                          Map<Integer, VoxelSection> modifiedSections,
                                          int minSectionY, int maxSectionY,
                                          BlockIdRegistry registry) {
        byte elemType = buf.get();
        if (elemType != FastNbtReader.TAG_COMPOUND) {
            int count = buf.getInt();
            for (int i = 0; i < count; i++) {
                FastNbtReader.skipTagPayload(buf, elemType);
            }
            writeFallbackSections(writer, modifiedSections, minSectionY, maxSectionY, registry);
            return;
        }

        int origCount = buf.getInt();
        int listStart = buf.position();
        int[] sectionYPerIndex = new int[origCount];
        Arrays.fill(sectionYPerIndex, Integer.MIN_VALUE);
        Set<Integer> existingYLevels = new HashSet<>();

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
                existingYLevels.add(currentY);
            }
        }

        int newlyAddedCount = 0;
        if (modifiedSections != null) {
            for (int secY : modifiedSections.keySet()) {
                if (secY >= minSectionY && secY <= maxSectionY && !existingYLevels.contains(secY)) {
                    newlyAddedCount++;
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
                    FastNbtReader.skipTagPayload(buf, childType);
                    VoxelSection modSec = (secY != Integer.MIN_VALUE && modifiedSections != null) ? modifiedSections.get(secY) : null;
                    if (modSec != null) {
                        FastChunkNbtWriter.writeSectionBlockStates(writer, modSec, registry);
                    } else {
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

            if (!hasBlockStates && secY != Integer.MIN_VALUE && modifiedSections != null && modifiedSections.containsKey(secY)) {
                FastChunkNbtWriter.writeSectionBlockStates(writer, modifiedSections.get(secY), registry);
            }
            if (!hasBiomes) {
                FastChunkNbtWriter.writeSectionBiomes(writer, "minecraft:plains");
            }

            writer.endCompound();
        }

        // Add brand new sections that did not previously exist
        if (modifiedSections != null && newlyAddedCount > 0) {
            for (Map.Entry<Integer, VoxelSection> entry : modifiedSections.entrySet()) {
                int secY = entry.getKey();
                if (secY >= minSectionY && secY <= maxSectionY && !existingYLevels.contains(secY)) {
                    writer.beginListCompound();
                    writer.putByte(Y_NAME, (byte) secY);
                    FastChunkNbtWriter.writeSectionBlockStates(writer, entry.getValue(), registry);
                    FastChunkNbtWriter.writeSectionBiomes(writer, "minecraft:plains");
                    writer.endCompound();
                }
            }
        }
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
