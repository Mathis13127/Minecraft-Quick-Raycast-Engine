package com.pixel.qve.world;

import java.util.Arrays;

/**
 * 2D Heightmap for a 512x512 block region (32x32 chunk columns = 1,024 entries).
 * Tracks maximum solid block Y coordinates across chunks to enable macroscopic O(1)
 * ray skipping of entire 512x512 block regions when rays fly above the local terrain.
 */
public final class RegionHeightmap2D {

    /** Number of chunks along one horizontal dimension of a region (32). */
    public static final int REGION_CHUNKS = 32;
    /** Total number of chunk entries in a region (32 x 32 = 1,024). */
    public static final int ENTRIES = REGION_CHUNKS * REGION_CHUNKS;
    /** Constant representing an empty or void altitude (-999). */
    public static final short VOID_Y = Heightmap2D.VOID_Y;

    private final short[] chunkHeights;
    private short regionMaxY;

    /**
     * Constructs a new RegionHeightmap2D initialized to {@link #VOID_Y}.
     */
    public RegionHeightmap2D() {
        this.chunkHeights = new short[ENTRIES];
        Arrays.fill(this.chunkHeights, VOID_Y);
        this.regionMaxY = VOID_Y;
    }

    /**
     * Computes the flat array index for a local chunk coordinate (0..31, 0..31).
     *
     * @param localChunkX Local chunk X [0..31]
     * @param localChunkZ Local chunk Z [0..31]
     * @return Flat index in [0..1023]
     */
    public static int chunkIndex(int localChunkX, int localChunkZ) {
        return ((localChunkZ & 31) << 5) | (localChunkX & 31);
    }

    /**
     * Retrieves the highest solid block Y coordinate across the entire 512x512 region.
     *
     * @return Maximum solid Y altitude, or {@link #VOID_Y} if region is empty
     */
    public short getRegionMaxY() {
        return regionMaxY;
    }

    /**
     * Retrieves the highest solid block Y coordinate for the specified local chunk.
     *
     * @param localChunkX Local chunk X [0..31]
     * @param localChunkZ Local chunk Z [0..31]
     * @return Highest solid block Y in chunk, or {@link #VOID_Y} if empty
     */
    public short getChunkMaxY(int localChunkX, int localChunkZ) {
        return chunkHeights[chunkIndex(localChunkX, localChunkZ)];
    }

    /**
     * Sets the highest solid block Y coordinate for the specified local chunk.
     *
     * @param localChunkX Local chunk X [0..31]
     * @param localChunkZ Local chunk Z [0..31]
     * @param y          New solid Y altitude
     */
    public void setChunkMaxY(int localChunkX, int localChunkZ, short y) {
        int idx = chunkIndex(localChunkX, localChunkZ);
        short prev = chunkHeights[idx];
        chunkHeights[idx] = y;
        if (y > regionMaxY) {
            regionMaxY = y;
        } else if (prev == regionMaxY && y < regionMaxY) {
            recomputeRegionMax();
        }
    }

    /**
     * Updates the height for a local chunk if the new Y is greater than the recorded height.
     *
     * @param localChunkX Local chunk X [0..31]
     * @param localChunkZ Local chunk Z [0..31]
     * @param y          Candidate solid Y altitude
     */
    public void updateMax(int localChunkX, int localChunkZ, short y) {
        int idx = chunkIndex(localChunkX, localChunkZ);
        if (y > chunkHeights[idx]) {
            chunkHeights[idx] = y;
            if (y > regionMaxY) {
                regionMaxY = y;
            }
        }
    }

    /**
     * Recomputes the maximum solid altitude across all 1,024 chunk entries in this region.
     */
    public void recomputeRegionMax() {
        short max = VOID_Y;
        for (int i = 0; i < ENTRIES; i++) {
            if (chunkHeights[i] > max) {
                max = chunkHeights[i];
            }
        }
        this.regionMaxY = max;
    }

    /**
     * Checks if a ray segment whose lowest altitude in this region is {@code minAltitude}
     * traverses strictly above all solid geometry in this entire 512x512 region.
     *
     * @param minAltitude Lowest altitude reached along the ray segment within this region
     * @return True if the ray is strictly above any solid matter in the region
     */
    public boolean isAboveRegion(double minAltitude) {
        if (regionMaxY == VOID_Y) {
            return true;
        }
        return minAltitude >= (regionMaxY + 1.0);
    }
}
