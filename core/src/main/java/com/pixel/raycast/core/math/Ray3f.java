package com.pixel.raycast.core.math;

/**
 * 3D Ray structure with precomputed inverse directions for ultra-fast slab bounding box tests.
 */
public final class Ray3f {

    public float ox, oy, oz;
    public float dx, dy, dz;
    public float invDx, invDy, invDz;
    public float maxDistance;

    public Ray3f() {
        this(0, 0, 0, 0, 0, 1, Float.MAX_VALUE);
    }

    public Ray3f(float ox, float oy, float oz, float dx, float dy, float dz, float maxDistance) {
        set(ox, oy, oz, dx, dy, dz, maxDistance);
    }

    public Ray3f set(float ox, float oy, float oz, float dx, float dy, float dz, float maxDistance) {
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;

        float lenSq = dx * dx + dy * dy + dz * dz;
        if (lenSq > 1e-12f) {
            float invLen = 1.0f / (float) Math.sqrt(lenSq);
            this.dx = dx * invLen;
            this.dy = dy * invLen;
            this.dz = dz * invLen;
        } else {
            this.dx = 0.0f;
            this.dy = 0.0f;
            this.dz = 1.0f;
        }

        this.invDx = (Math.abs(this.dx) > 1e-9f) ? (1.0f / this.dx) : (this.dx >= 0 ? 1e9f : -1e9f);
        this.invDy = (Math.abs(this.dy) > 1e-9f) ? (1.0f / this.dy) : (this.dy >= 0 ? 1e9f : -1e9f);
        this.invDz = (Math.abs(this.dz) > 1e-9f) ? (1.0f / this.dz) : (this.dz >= 0 ? 1e9f : -1e9f);

        this.maxDistance = maxDistance;
        return this;
    }

    public Ray3f setFromPoints(float startX, float startY, float startZ, float endX, float endY, float endZ) {
        float dirX = endX - startX;
        float dirY = endY - startY;
        float dirZ = endZ - startZ;
        float dist = (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        return set(startX, startY, startZ, dirX, dirY, dirZ, dist);
    }
}
