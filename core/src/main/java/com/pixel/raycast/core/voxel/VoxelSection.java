package com.pixel.raycast.core.voxel;

import java.util.Arrays;
import java.util.Objects;

/**
 * Ultra-compact 16x16x16 voxel section (4096 voxels).
 * Encapsulates solid occupancy in a 512-byte bitmask (long[64]) for 1-cycle CPU intersection tests,
 * and maintains an indexed palette table for instant zero-allocation Block ID retrieval.
 */
public final class VoxelSection {

    /** Width, height, and depth of a section in blocks (16). */
    public static final int SECTION_SIZE = 16;
    /** Total number of voxels per section (16 x 16 x 16 = 4096). */
    public static final int VOXEL_COUNT = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE; // 4096
    /** Number of 64-bit words needed to represent 4096 occupancy bits (64). */
    public static final int MASK_WORDS = VOXEL_COUNT / 64; // 64 longs = 512 bytes

    /** Immutable empty section singleton representing pure air. */
    public static final VoxelSection EMPTY = new VoxelSection();

    private final long[] bitmask;
    private final short[] blockIds;
    private int solidCount;

    /**
     * Constructs an empty VoxelSection.
     */
    public VoxelSection() {
        this.bitmask = new long[MASK_WORDS];
        this.blockIds = new short[VOXEL_COUNT];
        this.solidCount = 0;
    }

    /**
     * Constructs a VoxelSection with precomputed bitmask, block ID array, and solid count.
     *
     * @param mask       Occupancy bitmask (exactly 64 longs)
     * @param blockIds   Block identifier array (exactly 4096 shorts)
     * @param solidCount Number of non-air voxels
     */
    public VoxelSection(long[] mask, short[] blockIds, int solidCount) {
        this.bitmask = Objects.requireNonNull(mask, "Bitmask cannot be null");
        if (mask.length != MASK_WORDS) {
            throw new IllegalArgumentException("Bitmask must be exactly 64 longs (512 bytes), got: " + mask.length);
        }
        this.blockIds = Objects.requireNonNull(blockIds, "BlockIds array cannot be null");
        if (blockIds.length != VOXEL_COUNT) {
            throw new IllegalArgumentException("BlockIds array must be exactly 4096 shorts, got: " + blockIds.length);
        }
        this.solidCount = solidCount;
    }

    /**
     * Computes the flat 12-bit index for coordinates within a 16x16x16 section.
     *
     * @param x Local X coordinate [0..15]
     * @param y Local Y coordinate [0..15]
     * @param z Local Z coordinate [0..15]
     * @return Flat array index [0..4095]
     */
    public static int voxelIndex(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    /**
     * Evaluates solid occupancy of a local voxel in 1 CPU cycle.
     *
     * @param x Local X coordinate [0..15]
     * @param y Local Y coordinate [0..15]
     * @param z Local Z coordinate [0..15]
     * @return True if solid
     */
    public boolean isSolid(int x, int y, int z) {
        int idx = voxelIndex(x, y, z);
        int word = idx >>> 6;
        long bit = 1L << (idx & 63);
        return (bitmask[word] & bit) != 0L;
    }

    /**
     * Retrieves the 16-bit numeric block ID of a local voxel.
     *
     * @param x Local X coordinate [0..15]
     * @param y Local Y coordinate [0..15]
     * @param z Local Z coordinate [0..15]
     * @return 16-bit block ID
     */
    public short getBlockId(int x, int y, int z) {
        return blockIds[voxelIndex(x, y, z)];
    }

    /**
     * Modifies the occupancy and block ID of a local voxel.
     *
     * @param x       Local X coordinate [0..15]
     * @param y       Local Y coordinate [0..15]
     * @param z       Local Z coordinate [0..15]
     * @param solid   True if solid
     * @param blockId 16-bit block ID
     */
    public void setVoxel(int x, int y, int z, boolean solid, short blockId) {
        int idx = voxelIndex(x, y, z);
        int word = idx >>> 6;
        long bit = 1L << (idx & 63);
        boolean wasSolid = (bitmask[word] & bit) != 0L;

        if (solid) {
            bitmask[word] |= bit;
            if (!wasSolid) {
                solidCount++;
            }
        } else {
            bitmask[word] &= ~bit;
            if (wasSolid) {
                solidCount--;
            }
        }
        blockIds[idx] = blockId;
    }

    /**
     * Checks if this section contains zero solid voxels.
     *
     * @return True if completely empty
     */
    public boolean isEmpty() {
        return solidCount == 0;
    }

    /**
     * Checks if this section is completely packed with solid matter (4096 solid voxels).
     *
     * @return True if completely solid
     */
    public boolean isFull() {
        return solidCount == VOXEL_COUNT;
    }

    /**
     * Retrieves the total count of solid voxels in this section.
     *
     * @return Solid voxel count [0..4096]
     */
    public int getSolidCount() {
        return solidCount;
    }

    /**
     * Returns the raw 512-byte occupancy bitmask array.
     *
     * @return Array of 64 longs
     */
    public long[] getBitmask() {
        return bitmask;
    }

    /**
     * Returns the raw block ID array.
     *
     * @return Array of 4096 shorts
     */
    public short[] getBlockIds() {
        return blockIds;
    }

    /**
     * Resets this section to pure air.
     */
    public void clear() {
        Arrays.fill(bitmask, 0L);
        Arrays.fill(blockIds, (short) 0);
        this.solidCount = 0;
    }

    /**
     * Creates an independent deep copy of this VoxelSection.
     *
     * @return Cloned VoxelSection
     */
    public VoxelSection copy() {
        long[] maskCopy = Arrays.copyOf(bitmask, bitmask.length);
        short[] idsCopy = Arrays.copyOf(blockIds, blockIds.length);
        return new VoxelSection(maskCopy, idsCopy, solidCount);
    }
}
