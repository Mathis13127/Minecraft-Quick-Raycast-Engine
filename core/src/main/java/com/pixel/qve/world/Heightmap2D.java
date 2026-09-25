package com.pixel.qve.world;

import java.util.Arrays;

/**
 * Ultra-compact 2D heightmap for a 16x16 chunk column (256 entries).
 * Tracks the maximum solid block Y coordinate for instant O(1) sky-ray culling.
 */
public final class Heightmap2D {

    /** Width of a chunk in blocks (16). */
    public static final int CHUNK_WIDTH = 16;
    /** Total number of column entries (16 x 16 = 256). */
    public static final int ENTRIES = CHUNK_WIDTH * CHUNK_WIDTH; // 256
    /** Minimum world build altitude (-64). */
    public static final short MIN_Y = -64;
    /** Void altitude constant representing an unpopulated or empty column (-999). */
    public static final short VOID_Y = -999;

    private final short[] heights;
    private volatile short highestY;

    /**
     * Constructs an empty 2D heightmap initialized to VOID_Y.
     */
    public Heightmap2D() {
        this.heights = new short[ENTRIES];
        Arrays.fill(heights, VOID_Y);
        this.highestY = VOID_Y;
    }

    /**
     * Computes the flat array index for a local column (0..15, 0..15).
     *
     * @param localX Local column X [0..15]
     * @param localZ Local column Z [0..15]
     * @return Flat index [0..255]
     */
    public static int columnKey(int localX, int localZ) {
        return ((localZ & 15) << 4) | (localX & 15);
    }

    /**
     * Retrieves the highest solid block Y coordinate for the specified local column.
     *
     * @param localX Local column X [0..15]
     * @param localZ Local column Z [0..15]
     * @return Highest solid block Y, or VOID_Y if empty
     */
    public short getHeight(int localX, int localZ) {
        return heights[columnKey(localX, localZ)];
    }

    /**
     * Retrieves the highest solid block Y coordinate across the entire 16x16 chunk.
     *
     * @return Maximum Y altitude
     */
    public short getHighestY() {
        return highestY;
    }

    /**
     * Sets the highest solid block Y coordinate for the specified local column.
     *
     * @param localX Local column X [0..15]
     * @param localZ Local column Z [0..15]
     * @param y      New solid Y altitude
     */
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
            short val = heights[i];
            if (val > max) {
                max = val;
            }
        }
        this.highestY = max;
    }

    /**
     * Updates height for a column if the new Y is greater than existing recorded height.
     *
     * @param localX Local column X [0..15]
     * @param localZ Local column Z [0..15]
     * @param y      Candidate solid Y altitude
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
     *
     * @param sy      Vertical section index
     * @param section VoxelSection containing solid blocks
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
        if (highestY == VOID_Y) {
            return false;
        }
        return minAltitude >= (highestY + 1.0);
    }
}
