package com.pixel.qve.world;

/**
 * Standard directional faces for voxel boundaries.
 */
public enum VoxelFace {
    /** Bottom face (-Y). */
    DOWN(0, -1, 0),
    /** Top face (+Y). */
    UP(0, 1, 0),
    /** North face (-Z). */
    NORTH(0, 0, -1),
    /** South face (+Z). */
    SOUTH(0, 0, 1),
    /** West face (-X). */
    WEST(-1, 0, 0),
    /** East face (+X). */
    EAST(1, 0, 0),
    /** No face (internal hit or point-blank). */
    NONE(0, 0, 0);

    /** Step delta X. */
    public final int stepX;
    /** Step delta Y. */
    public final int stepY;
    /** Step delta Z. */
    public final int stepZ;

    VoxelFace(int stepX, int stepY, int stepZ) {
        this.stepX = stepX;
        this.stepY = stepY;
        this.stepZ = stepZ;
    }

    /**
     * Resolves the hit face from the DDA traversal step direction.
     *
     * @param dx Delta X step (-1, 0, 1)
     * @param dy Delta Y step (-1, 0, 1)
     * @param dz Delta Z step (-1, 0, 1)
     * @return Corresponding VoxelFace
     */
    public static VoxelFace fromStep(int dx, int dy, int dz) {
        if (dx > 0) return EAST;
        if (dx < 0) return WEST;
        if (dy > 0) return UP;
        if (dy < 0) return DOWN;
        if (dz > 0) return SOUTH;
        if (dz < 0) return NORTH;
        return NONE;
    }
}
