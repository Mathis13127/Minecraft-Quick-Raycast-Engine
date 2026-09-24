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
    private short[] blockIds;
    private int solidCount;
    private final boolean isHomogeneous;
    private final short singleBlockId;
    private boolean allSolidAreFullCubes;

    /**
     * Constructs an empty VoxelSection.
     */
    public VoxelSection() {
        this.bitmask = new long[MASK_WORDS];
        this.blockIds = new short[VOXEL_COUNT];
        this.solidCount = 0;
        this.isHomogeneous = false;
        this.singleBlockId = 0;
        this.allSolidAreFullCubes = false;
    }

    /**
     * Constructs a VoxelSection with precomputed bitmask, block ID array, and solid count.
     *
     * @param mask       Occupancy bitmask (exactly 64 longs)
     * @param blockIds   Block identifier array (exactly 4096 shorts)
     * @param solidCount Number of non-air voxels
     */
    public VoxelSection(long[] mask, short[] blockIds, int solidCount) {
        this(mask, blockIds, solidCount, false);
    }

    /**
     * Constructs a VoxelSection with precomputed bitmask, block ID array, solid count, and full-cube flag.
     *
     * @param mask                 Occupancy bitmask (exactly 64 longs)
     * @param blockIds             Block identifier array (exactly 4096 shorts)
     * @param solidCount           Number of non-air voxels
     * @param allSolidAreFullCubes True if all solid voxels in this section are 1x1x1 full cubes
     */
    public VoxelSection(long[] mask, short[] blockIds, int solidCount, boolean allSolidAreFullCubes) {
        this.bitmask = Objects.requireNonNull(mask, "Bitmask cannot be null");
        if (mask.length != MASK_WORDS) {
            throw new IllegalArgumentException("Bitmask must be exactly 64 longs (512 bytes), got: " + mask.length);
        }
        this.blockIds = Objects.requireNonNull(blockIds, "BlockIds array cannot be null");
        if (blockIds.length != VOXEL_COUNT) {
            throw new IllegalArgumentException("BlockIds array must be exactly 4096 shorts, got: " + blockIds.length);
        }
        this.solidCount = solidCount;
        this.isHomogeneous = false;
        this.singleBlockId = 0;
        this.allSolidAreFullCubes = allSolidAreFullCubes;
    }

    /**
     * Constructs a homogeneous VoxelSection (100% full of a single block ID, with zero blockIds array allocated).
     *
     * @param mask                 Occupancy bitmask (64 longs)
     * @param singleBlockId        Single uniform block ID
     * @param solidCount           Solid voxel count (usually 4096)
     * @param allSolidAreFullCubes True if uniform block is full cube
     */
    public VoxelSection(long[] mask, short singleBlockId, int solidCount, boolean allSolidAreFullCubes) {
        this.bitmask = Objects.requireNonNull(mask, "Bitmask cannot be null");
        this.blockIds = null;
        this.isHomogeneous = true;
        this.singleBlockId = singleBlockId;
        this.solidCount = solidCount;
        this.allSolidAreFullCubes = allSolidAreFullCubes;
    }

    /**
     * Creates an ultra-compact homogeneous section packed with a single block ID (zero heap allocation for blockIds array).
     *
     * @param blockId              Uniform block ID
     * @param allSolidAreFullCubes True if block shape is a full cube
     * @return Compact VoxelSection
     */
    public static VoxelSection createHomogeneous(short blockId, boolean allSolidAreFullCubes) {
        if (blockId == BlockIdRegistry.AIR_ID) {
            return EMPTY;
        }
        long[] mask = new long[MASK_WORDS];
        Arrays.fill(mask, ~0L);
        return new VoxelSection(mask, blockId, VOXEL_COUNT, allSolidAreFullCubes);
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
        if (isHomogeneous) {
            return singleBlockId;
        }
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
        if (isHomogeneous) {
            throw new UnsupportedOperationException("Cannot modify an immutable homogeneous VoxelSection");
        }
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
     * Checks if this section is homogeneous (filled uniformly with a single block ID).
     *
     * @return True if homogeneous
     */
    public boolean isHomogeneous() {
        return isHomogeneous;
    }

    /**
     * Gets the uniform single block ID if this section is homogeneous.
     *
     * @return Single uniform block ID, or 0
     */
    public short getSingleBlockId() {
        return singleBlockId;
    }

    /**
     * Returns true if all solid voxels in this section are guaranteed to be 1x1x1 full cubes.
     *
     * @return True if only full cubes are present
     */
    public boolean allSolidAreFullCubes() {
        return allSolidAreFullCubes;
    }

    /**
     * Sets whether all solid voxels in this section are 1x1x1 full cubes.
     *
     * @param allSolidAreFullCubes True if all solid voxels are full cubes
     */
    public void setAllSolidAreFullCubes(boolean allSolidAreFullCubes) {
        this.allSolidAreFullCubes = allSolidAreFullCubes;
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
     * If this section is homogeneous, lazily expands the compact singleBlockId into an array.
     *
     * @return Array of 4096 shorts
     */
    public short[] getBlockIds() {
        if (isHomogeneous) {
            if (blockIds == null) {
                short[] ids = new short[VOXEL_COUNT];
                Arrays.fill(ids, singleBlockId);
                this.blockIds = ids;
            }
        }
        return blockIds;
    }

    /**
     * Resets this section to pure air.
     */
    public void clear() {
        if (isHomogeneous) {
            throw new UnsupportedOperationException("Cannot clear an immutable homogeneous VoxelSection");
        }
        Arrays.fill(bitmask, 0L);
        Arrays.fill(blockIds, (short) 0);
        this.solidCount = 0;
        this.allSolidAreFullCubes = true;
    }

    /**
     * Bulk populates this section with precomputed bitmask, block IDs, and solid count.
     *
     * @param mask       Occupancy bitmask (64 longs)
     * @param blockIds   Block identifier array (4096 shorts)
     * @param solidCount Number of solid voxels
     */
    public void populate(long[] mask, short[] blockIds, int solidCount) {
        if (isHomogeneous) {
            throw new UnsupportedOperationException("Cannot populate an immutable homogeneous VoxelSection");
        }
        System.arraycopy(mask, 0, this.bitmask, 0, MASK_WORDS);
        System.arraycopy(blockIds, 0, this.blockIds, 0, VOXEL_COUNT);
        this.solidCount = solidCount;
    }

    /**
     * Recalculates solidCount by counting set bits in the occupancy bitmask.
     */
    public void recalculateSolidCount() {
        int count = 0;
        for (long word : bitmask) {
            count += Long.bitCount(word);
        }
        this.solidCount = count;
    }

    /**
     * Creates an independent deep copy of this VoxelSection.
     *
     * @return Cloned VoxelSection
     */
    public VoxelSection copy() {
        long[] maskCopy = Arrays.copyOf(bitmask, bitmask.length);
        if (isHomogeneous) {
            return new VoxelSection(maskCopy, singleBlockId, solidCount, allSolidAreFullCubes);
        }
        short[] idsCopy = Arrays.copyOf(blockIds, blockIds.length);
        return new VoxelSection(maskCopy, idsCopy, solidCount, allSolidAreFullCubes);
    }
}
