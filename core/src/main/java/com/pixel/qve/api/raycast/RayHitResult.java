package com.pixel.qve.api.raycast;

import com.pixel.qve.api.raycast.RayHitResult;


import com.pixel.qve.world.VoxelFace;

/**
 * Mutable, zero-allocation container storing raycast intersection results.
 * Can be reused across consecutive queries without garbage collector allocation.
 */
public final class RayHitResult {

    /** True if ray collided with solid matter, false on miss. */
    public boolean hit;
    /** Exact world X intersection coordinate. */
    public double hitX;
    /** Exact world Y intersection coordinate. */
    public double hitY;
    /** Exact world Z intersection coordinate. */
    public double hitZ;
    /** Integer world block X coordinate struck. */
    public int blockX;
    /** Integer world block Y coordinate struck. */
    public int blockY;
    /** Integer world block Z coordinate struck. */
    public int blockZ;
    /** Struck voxel face boundary. */
    public VoxelFace face;
    /** Numeric block identifier struck. */
    public int blockId;
    /** Distance traveled along the ray to the impact point. */
    public double distance;

    /**
     * Constructs a new RayHitResult initialized to a miss state.
     */
    public RayHitResult() {
        reset();
    }

    /**
     * Resets this result instance to a default miss state.
     */
    public void reset() {
        this.hit = false;
        this.hitX = 0.0;
        this.hitY = 0.0;
        this.hitZ = 0.0;
        this.blockX = 0;
        this.blockY = 0;
        this.blockZ = 0;
        this.face = VoxelFace.NONE;
        this.blockId = 0;
        this.distance = Double.MAX_VALUE;
    }

    /**
     * Populates all collision fields with impact data.
     *
     * @param hit      True if solid collision occurred
     * @param hitX     World X impact coordinate
     * @param hitY     World Y impact coordinate
     * @param hitZ     World Z impact coordinate
     * @param blockX   Struck block integer X
     * @param blockY   Struck block integer Y
     * @param blockZ   Struck block integer Z
     * @param face     Struck voxel boundary face
     * @param blockId  32-bit block type identifier
     * @param distance Distance traveled along the ray
     */
    public void set(boolean hit, double hitX, double hitY, double hitZ,
                    int blockX, int blockY, int blockZ,
                    VoxelFace face, int blockId, double distance) {
        this.hit = hit;
        this.hitX = hitX;
        this.hitY = hitY;
        this.hitZ = hitZ;
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
        this.face = face;
        this.blockId = blockId;
        this.distance = distance;
    }

    /**
     * Returns whether an impact was registered.
     *
     * @return True if hit, false on miss
     */
    public boolean isHit() {
        return hit;
    }

    /**
     * Gets the exact world X impact coordinate.
     *
     * @return World X coordinate
     */
    public double getHitX() {
        return hitX;
    }

    /**
     * Gets the exact world Y impact coordinate.
     *
     * @return World Y coordinate
     */
    public double getHitY() {
        return hitY;
    }

    /**
     * Gets the exact world Z impact coordinate.
     *
     * @return World Z coordinate
     */
    public double getHitZ() {
        return hitZ;
    }

    /**
     * Gets the integer block X coordinate of the struck voxel.
     *
     * @return Block integer X
     */
    public int getBlockX() {
        return blockX;
    }

    /**
     * Gets the integer block Y coordinate of the struck voxel.
     *
     * @return Block integer Y
     */
    public int getBlockY() {
        return blockY;
    }

    /**
     * Gets the integer block Z coordinate of the struck voxel.
     *
     * @return Block integer Z
     */
    public int getBlockZ() {
        return blockZ;
    }

    /**
     * Gets the voxel boundary face struck by the ray.
     *
     * @return VoxelFace enum value
     */
    public VoxelFace getFace() {
        return face;
    }

    /**
     * Gets the numeric block identifier of the struck voxel.
     *
     * @return 32-bit numeric block ID
     */
    public int getBlockId() {
        return blockId;
    }

    /**
     * Gets the distance traveled along the ray to the point of impact.
     *
     * @return Impact distance
     */
    public double getDistance() {
        return distance;
    }

    /**
     * Copies all impact state from another RayHitResult instance.
     *
     * @param other Source result container
     */
    public void copyFrom(RayHitResult other) {
        this.hit = other.hit;
        this.hitX = other.hitX;
        this.hitY = other.hitY;
        this.hitZ = other.hitZ;
        this.blockX = other.blockX;
        this.blockY = other.blockY;
        this.blockZ = other.blockZ;
        this.face = other.face;
        this.blockId = other.blockId;
        this.distance = other.distance;
    }

    @Override
    public String toString() {
        if (!hit) {
            return "RayHitResult{MISS}";
        }
        return String.format("RayHitResult{HIT at (%.3f, %.3f, %.3f), block=(%d, %d, %d), face=%s, id=%d, dist=%.3f}",
            hitX, hitY, hitZ, blockX, blockY, blockZ, face, blockId, distance);
    }
}
