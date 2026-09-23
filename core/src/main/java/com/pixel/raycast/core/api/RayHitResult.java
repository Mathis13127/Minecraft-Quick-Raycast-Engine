package com.pixel.raycast.core.api;

import com.pixel.raycast.core.voxel.VoxelFace;

/**
 * Mutable, zero-allocation container storing raycast intersection results.
 * Can be reused across consecutive queries without garbage collector allocation.
 */
public final class RayHitResult {

    public boolean hit;
    public double hitX;
    public double hitY;
    public double hitZ;
    public int blockX;
    public int blockY;
    public int blockZ;
    public VoxelFace face;
    public short blockId;
    public double distance;

    public RayHitResult() {
        reset();
    }

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

    public void set(boolean hit, double hitX, double hitY, double hitZ,
                    int blockX, int blockY, int blockZ,
                    VoxelFace face, short blockId, double distance) {
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
