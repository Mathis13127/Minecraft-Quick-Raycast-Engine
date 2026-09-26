package com.pixel.qve.mca.writer;

import java.util.Arrays;
import java.util.Objects;

/**
 * Ultra-high-performance zero-allocation primitive mutation and AABB box buffer (Struct-of-Arrays).
 * Stores sparse block changes and volumetric bounding boxes using contiguous primitive arrays without object headers.
 * <p>
 * Bit-packing for boundsAndFlags (64 bits):
 * <ul>
 *   <li>Bits 0..3   (4 bits) : minLocalX [0..15]</li>
 *   <li>Bits 4..7   (4 bits) : maxLocalX [0..15]</li>
 *   <li>Bits 8..11  (4 bits) : minLocalZ [0..15]</li>
 *   <li>Bits 12..15 (4 bits) : maxLocalZ [0..15]</li>
 *   <li>Bits 16..31 (16 bits): minWorldY signed [-32768..32767]</li>
 *   <li>Bits 32..47 (16 bits): maxWorldY signed [-32768..32767]</li>
 *   <li>Bits 48..63 (16 bits): flags (hasNbt = 1, isSingleVoxel = 2)</li>
 * </ul>
 */
public final class PrimitiveMutationBuffer {

    private static final int INITIAL_CAPACITY = 4;
    public static final int FLAG_HAS_NBT = 1;
    public static final int FLAG_IS_SINGLE_VOXEL = 2;

    private long[] boundsAndFlags;
    private int[] targetBlockIds;
    private int[] filterBlockIds;
    private byte[][] rawNbts;
    private int size;
    private int modifiedSectionMask;

    public PrimitiveMutationBuffer() {
        this(INITIAL_CAPACITY);
    }

    public PrimitiveMutationBuffer(int initialCapacity) {
        int cap = Math.max(INITIAL_CAPACITY, initialCapacity);
        this.boundsAndFlags = new long[cap];
        this.targetBlockIds = new int[cap];
        this.filterBlockIds = new int[cap];
        this.rawNbts = null; // Allocated lazily only if block entity NBT is provided
        this.size = 0;
        this.modifiedSectionMask = 0;
    }

    public static long packBoundsAndFlags(int minX, int maxX, int minZ, int maxZ, int minY, int maxY, int flags) {
        return ((long) (minX & 0xF))
                | (((long) (maxX & 0xF)) << 4)
                | (((long) (minZ & 0xF)) << 8)
                | (((long) (maxZ & 0xF)) << 12)
                | (((long) (minY & 0xFFFF)) << 16)
                | (((long) (maxY & 0xFFFF)) << 32)
                | (((long) (flags & 0xFFFF)) << 48);
    }

    /**
     * Appends a single block mutation directly into the primitive buffer.
     *
     * @param localX        Local X in chunk [0..15]
     * @param worldY        World build height Y
     * @param localZ        Local Z in chunk [0..15]
     * @param targetBlockId Compact 32-bit target block ID
     * @param filterBlockId Filter block ID (-1 if unconditional)
     * @param rawNbt        Optional raw BlockEntity NBT compound (may be null)
     */
    public void add(int localX, int worldY, int localZ, int targetBlockId, int filterBlockId, byte[] rawNbt) {
        addBox(localX, worldY, localZ, localX, worldY, localZ, targetBlockId, filterBlockId, rawNbt);
    }

    /**
     * Appends a 3D bounding box edit directly into the primitive buffer.
     *
     * @param minX          Local min X in chunk [0..15]
     * @param minY          World min Y
     * @param minZ          Local min Z in chunk [0..15]
     * @param maxX          Local max X in chunk [0..15]
     * @param maxY          World max Y
     * @param maxZ          Local max Z in chunk [0..15]
     * @param targetBlockId Target block ID
     * @param filterBlockId Filter block ID (-1 if unconditional)
     * @param rawNbt        Optional raw BlockEntity NBT
     */
    public void addBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int targetBlockId, int filterBlockId, byte[] rawNbt) {
        ensureCapacity(size + 1);

        int flags = 0;
        if (rawNbt != null) {
            flags |= FLAG_HAS_NBT;
        }
        if (minX == maxX && minY == maxY && minZ == maxZ) {
            flags |= FLAG_IS_SINGLE_VOXEL;
        }

        boundsAndFlags[size] = packBoundsAndFlags(minX, maxX, minZ, maxZ, minY, maxY, flags);
        targetBlockIds[size] = targetBlockId;
        filterBlockIds[size] = filterBlockId;

        if (rawNbt != null) {
            if (rawNbts == null) {
                rawNbts = new byte[boundsAndFlags.length][];
            }
            rawNbts[size] = rawNbt;
        }

        int sMin = minY >> 4;
        int sMax = maxY >> 4;
        for (int s = sMin; s <= sMax; s++) {
            int bit = s + 16;
            if (bit >= 0 && bit < 32) {
                modifiedSectionMask |= (1 << bit);
            }
        }

        size++;
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity > boundsAndFlags.length) {
            int newCap = Math.max(boundsAndFlags.length * 2, minCapacity);
            boundsAndFlags = Arrays.copyOf(boundsAndFlags, newCap);
            targetBlockIds = Arrays.copyOf(targetBlockIds, newCap);
            filterBlockIds = Arrays.copyOf(filterBlockIds, newCap);
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

    public int minX(int index) {
        return (int) (boundsAndFlags[index] & 0xFL);
    }

    public int maxX(int index) {
        return (int) ((boundsAndFlags[index] >>> 4) & 0xFL);
    }

    public int minZ(int index) {
        return (int) ((boundsAndFlags[index] >>> 8) & 0xFL);
    }

    public int maxZ(int index) {
        return (int) ((boundsAndFlags[index] >>> 12) & 0xFL);
    }

    public int minY(int index) {
        return (short) ((boundsAndFlags[index] >>> 16) & 0xFFFFL);
    }

    public int maxY(int index) {
        return (short) ((boundsAndFlags[index] >>> 32) & 0xFFFFL);
    }

    public int flags(int index) {
        return (int) ((boundsAndFlags[index] >>> 48) & 0xFFFFL);
    }

    public boolean hasNbt(int index) {
        return (flags(index) & FLAG_HAS_NBT) != 0;
    }

    public boolean isSingleVoxel(int index) {
        return (flags(index) & FLAG_IS_SINGLE_VOXEL) != 0;
    }

    public int localX(int index) {
        return minX(index);
    }

    public int localZ(int index) {
        return minZ(index);
    }

    public int worldY(int index) {
        return minY(index);
    }

    public int sectionY(int index) {
        return minY(index) >> 4;
    }

    public int minSectionY(int index) {
        return minY(index) >> 4;
    }

    public int maxSectionY(int index) {
        return maxY(index) >> 4;
    }

    public int filterBlockId(int index) {
        return filterBlockIds[index];
    }

    public int targetBlockId(int index) {
        return targetBlockIds[index];
    }

    public byte[] rawNbt(int index) {
        return (rawNbts != null) ? rawNbts[index] : null;
    }

    public boolean matchesFilter(int index, int currentBlockId) {
        int filterId = filterBlockIds[index];
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

    public boolean contains(int index, int lx, int wy, int lz) {
        return lx >= minX(index) && lx <= maxX(index)
                && lz >= minZ(index) && lz <= maxZ(index)
                && wy >= minY(index) && wy <= maxY(index);
    }

    public boolean intersectsSection(int index, int secY) {
        int secMinY = secY << 4;
        int secMaxY = secMinY + 15;
        return minY(index) <= secMaxY && maxY(index) >= secMinY;
    }

    public boolean coversSectionCompletely(int index, int secY) {
        int secMinY = secY << 4;
        int secMaxY = secMinY + 15;
        return minX(index) == 0 && maxX(index) == 15
                && minZ(index) == 0 && maxZ(index) == 15
                && minY(index) <= secMinY && maxY(index) >= secMaxY;
    }

    public int voxelCount(int index) {
        return (maxX(index) - minX(index) + 1)
                * (maxZ(index) - minZ(index) + 1)
                * (maxY(index) - minY(index) + 1);
    }

    public int totalVoxelCount() {
        int count = 0;
        for (int i = 0; i < size; i++) {
            count += voxelCount(i);
        }
        return count;
    }

    public void addAll(PrimitiveMutationBuffer other) {
        if (other == null || other.isEmpty()) return;
        ensureCapacity(size + other.size);
        for (int i = 0; i < other.size; i++) {
            addBox(other.minX(i), other.minY(i), other.minZ(i),
                    other.maxX(i), other.maxY(i), other.maxZ(i),
                    other.targetBlockId(i), other.filterBlockId(i), other.rawNbt(i));
        }
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
            int sMin = minSectionY(i);
            int sMax = maxSectionY(i);
            for (int s = sMin; s <= sMax; s++) {
                int bit = s - minSectionY;
                if (bit >= 0 && bit < 32) {
                    mask |= (1 << bit);
                }
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
            int sMin = minSectionY(i);
            int sMax = maxSectionY(i);
            for (int s = sMin; s <= sMax; s++) {
                if (s >= minSectionY && s <= maxSectionY) {
                    mask |= (1 << (s - minSectionY));
                }
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
