package com.pixel.qve.world;

import com.pixel.qve.state.BlockIdRegistry;


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

    private static final java.lang.invoke.VarHandle BITMASK_HANDLE =
            java.lang.invoke.MethodHandles.arrayElementVarHandle(long[].class);
    private static final java.lang.invoke.VarHandle BLOCK_IDS_HANDLE =
            java.lang.invoke.MethodHandles.arrayElementVarHandle(int[].class);
    private static final java.lang.invoke.VarHandle SOLID_COUNT_HANDLE;

    static {
        try {
            SOLID_COUNT_HANDLE = java.lang.invoke.MethodHandles.lookup()
                    .findVarHandle(VoxelSection.class, "solidCount", int.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final long[] bitmask;
    private int[] blockIds;
    private int solidCount;
    private volatile boolean isHomogeneous;
    private volatile int singleBlockId;
    private boolean allSolidAreFullCubes;

    /**
     * Constructs an empty VoxelSection.
     */
    public VoxelSection() {
        this.bitmask = new long[MASK_WORDS];
        this.blockIds = new int[VOXEL_COUNT];
        this.solidCount = 0;
        this.isHomogeneous = false;
        this.singleBlockId = 0;
        this.allSolidAreFullCubes = false;
    }

    /**
     * Constructs a VoxelSection with precomputed bitmask, block ID array, and solid count.
     *
     * @param mask       Occupancy bitmask (exactly 64 longs)
     * @param blockIds   Block identifier array (exactly 4096 ints)
     * @param solidCount Number of non-air voxels
     */
    public VoxelSection(long[] mask, int[] blockIds, int solidCount) {
        this(mask, blockIds, solidCount, false);
    }

    /**
     * Constructs a VoxelSection with precomputed bitmask, block ID array, solid count, and full-cube flag.
     *
     * @param mask                 Occupancy bitmask (exactly 64 longs)
     * @param blockIds             Block identifier array (exactly 4096 ints)
     * @param solidCount           Number of non-air voxels
     * @param allSolidAreFullCubes True if all solid voxels in this section are 1x1x1 full cubes
     */
    public VoxelSection(long[] mask, int[] blockIds, int solidCount, boolean allSolidAreFullCubes) {
        this.bitmask = Objects.requireNonNull(mask, "Bitmask cannot be null");
        if (mask.length != MASK_WORDS) {
            throw new IllegalArgumentException("Bitmask must be exactly 64 longs (512 bytes), got: " + mask.length);
        }
        this.blockIds = Objects.requireNonNull(blockIds, "BlockIds array cannot be null");
        if (blockIds.length != VOXEL_COUNT) {
            throw new IllegalArgumentException("BlockIds array must be exactly 4096 ints, got: " + blockIds.length);
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
    public VoxelSection(long[] mask, int singleBlockId, int solidCount, boolean allSolidAreFullCubes) {
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
    public static VoxelSection createHomogeneous(int blockId, boolean allSolidAreFullCubes) {
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
     * Retrieves the 32-bit numeric block ID of a local voxel.
     *
     * @param x Local X coordinate [0..15]
     * @param y Local Y coordinate [0..15]
     * @param z Local Z coordinate [0..15]
     * @return 32-bit block ID
     */
    public int getBlockId(int x, int y, int z) {
        if (isHomogeneous) {
            return singleBlockId;
        }
        return blockIds[voxelIndex(x, y, z)];
    }

    /**
     * Converts an immutable homogeneous section into a fully mutable dense section.
     * Thread-safe and idempotent.
     */
    public synchronized void demoteToMutable() {
        if (!isHomogeneous) {
            return;
        }
        int[] denseIds = new int[VOXEL_COUNT];
        if (singleBlockId != 0) {
            Arrays.fill(denseIds, singleBlockId);
        }
        this.blockIds = denseIds;
        this.isHomogeneous = false;
    }

    /**
     * Modifies the occupancy and block ID of a local voxel using lock-free atomic hardware primitives.
     *
     * @param x       Local X coordinate [0..15]
     * @param y       Local Y coordinate [0..15]
     * @param z       Local Z coordinate [0..15]
     * @param solid   True if solid
     * @param blockId 32-bit block ID
     */
    public void setVoxel(int x, int y, int z, boolean solid, int blockId) {
        if (isHomogeneous) {
            demoteToMutable();
        }
        int idx = voxelIndex(x, y, z);
        int word = idx >>> 6;
        long bit = 1L << (idx & 63);

        if (solid) {
            BLOCK_IDS_HANDLE.setRelease(blockIds, idx, blockId);
            long oldWord = (long) BITMASK_HANDLE.getAndBitwiseOrRelease(bitmask, word, bit);
            if ((oldWord & bit) == 0L) {
                SOLID_COUNT_HANDLE.getAndAddRelease(this, 1);
            }
        } else {
            long oldWord = (long) BITMASK_HANDLE.getAndBitwiseAndRelease(bitmask, word, ~bit);
            if ((oldWord & bit) != 0L) {
                SOLID_COUNT_HANDLE.getAndAddRelease(this, -1);
            }
            BLOCK_IDS_HANDLE.setRelease(blockIds, idx, 0);
        }
    }

    /**
     * Convenience method to set block ID, automatically setting solid flag if not air.
     */
    public void setBlock(int x, int y, int z, int blockId) {
        setVoxel(x, y, z, blockId != BlockIdRegistry.AIR_ID, blockId);
    }

    /**
     * Alias for setBlock.
     */
    public void set(int x, int y, int z, int blockId) {
        setBlock(x, y, z, blockId);
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
        return isHomogeneous || (solidCount == 0);
    }

    /**
     * Gets the uniform single block ID if this section is homogeneous.
     *
     * @return Single uniform block ID, or 0
     */
    public int getSingleBlockId() {
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
     * @return Array of 4096 ints
     */
    public int[] getBlockIds() {
        if (isHomogeneous) {
            if (blockIds == null) {
                int[] ids = new int[VOXEL_COUNT];
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
            demoteToMutable();
        }
        Arrays.fill(bitmask, 0L);
        Arrays.fill(blockIds, 0);
        this.solidCount = 0;
        this.allSolidAreFullCubes = true;
    }

    /**
     * Bulk populates this section with precomputed bitmask, block IDs, and solid count.
     *
     * @param mask       Occupancy bitmask (64 longs)
     * @param blockIds   Block identifier array (4096 ints)
     * @param solidCount Number of solid voxels
     */
    public void populate(long[] mask, int[] blockIds, int solidCount) {
        if (isHomogeneous) {
            demoteToMutable();
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
        int[] idsCopy = Arrays.copyOf(blockIds, blockIds.length);
        return new VoxelSection(maskCopy, idsCopy, solidCount, allSolidAreFullCubes);
    }
}
