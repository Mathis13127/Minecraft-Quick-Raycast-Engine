package com.pixel.raycast.core.voxel;

/**
 * Standard directional faces for voxel boundaries.
 */
public enum VoxelFace {
    DOWN(0, -1, 0),
    UP(0, 1, 0),
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    WEST(-1, 0, 0),
    EAST(1, 0, 0),
    NONE(0, 0, 0);

    public final int stepX;
    public final int stepY;
    public final int stepZ;

    VoxelFace(int stepX, int stepY, int stepZ) {
        this.stepX = stepX;
        this.stepY = stepY;
        this.stepZ = stepZ;
    }

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
