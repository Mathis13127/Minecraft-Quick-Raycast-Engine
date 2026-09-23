package com.pixel.raycast.core.voxel;

import java.util.Arrays;
import java.util.Objects;

/**
 * Ultra-compact 16x16x16 voxel section (4096 voxels).
 * Encapsulates solid occupancy in a 512-byte bitmask (long[64]) for 1-cycle CPU intersection tests,
 * and maintains an indexed palette table for instant zero-allocation Block ID retrieval.
 */
public final class VoxelSection {

    public static final int SECTION_SIZE = 16;
    public static final int VOXEL_COUNT = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE; // 4096
    public static final int MASK_WORDS = VOXEL_COUNT / 64; // 64 longs = 512 bytes

    private final long[] bitmask;
    private final short[] blockIds;
    private int solidCount;

    public VoxelSection() {
        this.bitmask = new long[MASK_WORDS];
        this.blockIds = new short[VOXEL_COUNT];
        this.solidCount = 0;
    }

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

    public static int voxelIndex(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    public boolean isSolid(int x, int y, int z) {
        int idx = voxelIndex(x, y, z);
        int word = idx >>> 6;
        long bit = 1L << (idx & 63);
        return (bitmask[word] & bit) != 0L;
    }

    public short getBlockId(int x, int y, int z) {
        return blockIds[voxelIndex(x, y, z)];
    }

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

    public boolean isEmpty() {
        return solidCount == 0;
    }

    public boolean isFull() {
        return solidCount == VOXEL_COUNT;
    }

    public int getSolidCount() {
        return solidCount;
    }

    public long[] getBitmask() {
        return bitmask;
    }

    public short[] getBlockIds() {
        return blockIds;
    }

    public void clear() {
        Arrays.fill(bitmask, 0L);
        Arrays.fill(blockIds, (short) 0);
        this.solidCount = 0;
    }

    public VoxelSection copy() {
        long[] maskCopy = Arrays.copyOf(bitmask, bitmask.length);
        short[] idsCopy = Arrays.copyOf(blockIds, blockIds.length);
        return new VoxelSection(maskCopy, idsCopy, solidCount);
    }
}
