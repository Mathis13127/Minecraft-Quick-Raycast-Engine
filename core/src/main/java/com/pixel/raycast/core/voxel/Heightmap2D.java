package com.pixel.raycast.core.voxel;

import java.util.Arrays;

/**
 * Ultra-compact 2D heightmap for a 16x16 chunk column (256 entries).
 * Tracks the maximum solid block Y coordinate for instant O(1) sky-ray culling.
 */
public final class Heightmap2D {

    public static final int CHUNK_WIDTH = 16;
    public static final int ENTRIES = CHUNK_WIDTH * CHUNK_WIDTH; // 256
    public static final short MIN_Y = -64;
    public static final short VOID_Y = -999;

    private final short[] heights;
    private short highestY;

    public Heightmap2D() {
        this.heights = new short[ENTRIES];
        Arrays.fill(heights, VOID_Y);
        this.highestY = VOID_Y;
    }

    public static int columnKey(int localX, int localZ) {
        return ((localZ & 15) << 4) | (localX & 15);
    }

    public short getHeight(int localX, int localZ) {
        return heights[columnKey(localX, localZ)];
    }

    public short getHighestY() {
        return highestY;
    }

    public void setHeight(int localX, int localZ, short y) {
        int key = columnKey(localX, localZ);
        short prev = heights[key];
        heights[key] = y;
        if (y > highestY) {
            highestY = y;
        } else if (prev == highestY && y < highestY) {
            recomputeHighest();
        }
    }

    /**
     * Scans all 256 column entries to find the true maximum solid altitude in this chunk.
     */
    public void recomputeHighest() {
        short max = VOID_Y;
        for (int i = 0; i < ENTRIES; i++) {
            if (heights[i] > max) {
                max = heights[i];
            }
        }
        this.highestY = max;
    }

    /**
     * Updates height for a column if the new Y is greater than existing recorded height.
     */
    public void updateMax(int localX, int localZ, short y) {
        int key = columnKey(localX, localZ);
        if (y > heights[key]) {
            heights[key] = y;
            if (y > highestY) {
                highestY = y;
            }
        }
    }

    /**
     * Updates the heightmap from the voxels in a VoxelSection at the specified section Y.
     */
    public void updateFromSection(int sy, VoxelSection section) {
        if (section == null || section.isEmpty()) {
            return;
        }

        int baseY = sy << 4;
        for (int z = 0; z < CHUNK_WIDTH; z++) {
            for (int x = 0; x < CHUNK_WIDTH; x++) {
                for (int y = 15; y >= 0; y--) {
                    if (section.isSolid(x, y, z)) {
                        updateMax(x, z, (short) (baseY + y));
                        break;
                    }
                }
            }
        }
    }

    /**
     * Checks if a ray bounded by a minimum altitude traverses completely above the highest solid block in this column.
     *
     * @param minAltitude Lowest altitude reached along the ray segment
     * @return True if the ray is strictly above any solid matter
     */
    public boolean isAboveTerrain(double minAltitude) {
        return minAltitude >= (highestY + 1.0);
    }
}
