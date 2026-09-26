package com.pixel.qve.mca.writer;

import java.util.Arrays;
import java.util.Objects;

/**
 * Ultra-high-performance zero-allocation primitive mutation buffer (Struct-of-Arrays).
 * Stores sparse block changes using contiguous primitive arrays without object headers.
 * <p>
 * Bit-packing for packedCoordsAndFilter (64 bits):
 * <ul>
 *   <li>Bits 0..3   (4 bits) : localX [0..15]</li>
 *   <li>Bits 4..7   (4 bits) : localZ [0..15]</li>
 *   <li>Bits 8..23  (16 bits): worldY signed [-32768..32767]</li>
 *   <li>Bits 24..47 (24 bits): filterBlockId signed (-1 if unconditional)</li>
 *   <li>Bits 48..63 (16 bits): flags (hasNbt = 1, etc.)</li>
 * </ul>
 */
public final class PrimitiveMutationBuffer {

    private static final int INITIAL_CAPACITY = 16;
    public static final int FLAG_HAS_NBT = 1;

    private long[] coordsAndFilter;
    private int[] targetBlockIds;
    private byte[][] rawNbts;
    private int size;
    private int modifiedSectionMask;

    public PrimitiveMutationBuffer() {
        this(INITIAL_CAPACITY);
    }

    public PrimitiveMutationBuffer(int initialCapacity) {
        int cap = Math.max(INITIAL_CAPACITY, initialCapacity);
        this.coordsAndFilter = new long[cap];
        this.targetBlockIds = new int[cap];
        this.rawNbts = null; // Allocated lazily only if block entity NBT is provided
        this.size = 0;
        this.modifiedSectionMask = 0;
    }

    /**
     * Appends a block mutation directly into the primitive buffer.
     *
     * @param localX        Local X in chunk [0..15]
     * @param worldY        World build height Y
     * @param localZ        Local Z in chunk [0..15]
     * @param targetBlockId Compact 32-bit target block ID
     * @param filterBlockId Filter block ID (-1 if unconditional)
     * @param rawNbt        Optional raw BlockEntity NBT compound (may be null)
     */
    public void add(int localX, int worldY, int localZ, int targetBlockId, int filterBlockId, byte[] rawNbt) {
        ensureCapacity(size + 1);

        int flags = (rawNbt != null) ? FLAG_HAS_NBT : 0;
        long packed = ((long) (localX & 0xF))
                | (((long) (localZ & 0xF)) << 4)
                | (((long) (worldY & 0xFFFF)) << 8)
                | ((((long) filterBlockId) & 0xFFFFFFL) << 24)
                | (((long) (flags & 0xFFFF)) << 48);

        coordsAndFilter[size] = packed;
        targetBlockIds[size] = targetBlockId;

        if (rawNbt != null) {
            if (rawNbts == null) {
                rawNbts = new byte[coordsAndFilter.length][];
            }
            rawNbts[size] = rawNbt;
        }

        int secY = worldY >> 4;
        int bit = secY + 16;
        if (bit >= 0 && bit < 32) {
            modifiedSectionMask |= (1 << bit);
        }

        size++;
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity > coordsAndFilter.length) {
            int newCap = Math.max(coordsAndFilter.length * 2, minCapacity);
            coordsAndFilter = Arrays.copyOf(coordsAndFilter, newCap);
            targetBlockIds = Arrays.copyOf(targetBlockIds, newCap);
            if (rawNbts != null) {
                rawNbts = Arrays.copyOf(rawNbts, newCap);
            }
        }
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int localX(int index) {
        return (int) (coordsAndFilter[index] & 0xFL);
    }

    public int localZ(int index) {
        return (int) ((coordsAndFilter[index] >>> 4) & 0xFL);
    }

    public int worldY(int index) {
        return (short) ((coordsAndFilter[index] >>> 8) & 0xFFFFL);
    }

    public int sectionY(int index) {
        return worldY(index) >> 4;
    }

    public int filterBlockId(int index) {
        // Sign-extend 24-bit value to 32-bit int
        return (int) ((coordsAndFilter[index] << 16) >> 40);
    }

    public int targetBlockId(int index) {
        return targetBlockIds[index];
    }

    public byte[] rawNbt(int index) {
        return (rawNbts != null) ? rawNbts[index] : null;
    }

    public boolean hasNbt(int index) {
        return ((coordsAndFilter[index] >>> 48) & FLAG_HAS_NBT) != 0L;
    }

    public boolean matchesFilter(int index, int currentBlockId) {
        int filterId = filterBlockId(index);
        return filterId < 0 || currentBlockId == filterId;
    }

    public int getLocalX(int index) {
        return localX(index);
    }

    public int getLocalZ(int index) {
        return localZ(index);
    }

    public int getWorldY(int index) {
        return worldY(index);
    }

    public int getSectionY(int index) {
        return sectionY(index);
    }

    public int getFilterBlockId(int index) {
        return filterBlockId(index);
    }

    public int getTargetBlockId(int index) {
        return targetBlockId(index);
    }

    public byte[] getRawNbt(int index) {
        return rawNbt(index);
    }

    public boolean hasRawNbts() {
        return rawNbts != null;
    }

    /**
     * Bitmask of modified vertical section indices (bit = secY + 16).
     */
    public int getModifiedSectionMask() {
        return modifiedSectionMask;
    }

    /**
     * Returns a 32-bit bitmask shifted by minSectionY.
     */
    public int getModifiedSectionMask(int minSectionY) {
        int mask = 0;
        for (int i = 0; i < size; i++) {
            int secY = sectionY(i);
            int bit = secY - minSectionY;
            if (bit >= 0 && bit < 32) {
                mask |= (1 << bit);
            }
        }
        return mask;
    }

    /**
     * Returns a 32-bit bitmask shifted by minSectionY, bounded by maxSectionY.
     */
    public int getModifiedSectionMask(int minSectionY, int maxSectionY) {
        int mask = 0;
        for (int i = 0; i < size; i++) {
            int secY = sectionY(i);
            if (secY >= minSectionY && secY <= maxSectionY) {
                mask |= (1 << (secY - minSectionY));
            }
        }
        return mask;
    }

    /**
     * Clears all stored mutations, retaining allocated buffers for zero-allocation reuse.
     */
    public void clear() {
        if (rawNbts != null) {
            Arrays.fill(rawNbts, 0, size, null);
        }
        size = 0;
        modifiedSectionMask = 0;
    }
}
