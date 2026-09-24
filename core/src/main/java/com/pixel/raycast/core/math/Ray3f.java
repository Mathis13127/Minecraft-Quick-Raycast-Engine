package com.pixel.raycast.core.math;

/**
 * 3D Ray structure with precomputed inverse directions for ultra-fast slab bounding box tests.
 */
public final class Ray3f {

    /** Ray origin X, Y, Z coordinates. */
    public float ox, oy, oz;
    /** Normalized ray direction components. */
    public float dx, dy, dz;
    /** Precomputed inverses of direction components for branchless slab clipping. */
    public float invDx, invDy, invDz;
    /** Maximum travel distance along this ray. */
    public float maxDistance;

    /**
     * Constructs a default ray pointing along positive Z axis.
     */
    public Ray3f() {
        this(0, 0, 0, 0, 0, 1, Float.MAX_VALUE);
    }

    /**
     * Constructs a ray with explicit origin, direction, and maximum distance.
     *
     * @param ox          Origin X
     * @param oy          Origin Y
     * @param oz          Origin Z
     * @param dx          Direction X
     * @param dy          Direction Y
     * @param dz          Direction Z
     * @param maxDistance Maximum travel distance
     */
    public Ray3f(float ox, float oy, float oz, float dx, float dy, float dz, float maxDistance) {
        set(ox, oy, oz, dx, dy, dz, maxDistance);
    }

    /**
     * Sets ray origin, normalizes direction, precomputes reciprocal slopes, and updates max distance.
     *
     * @param ox          Origin X
     * @param oy          Origin Y
     * @param oz          Origin Z
     * @param dx          Direction X
     * @param dy          Direction Y
     * @param dz          Direction Z
     * @param maxDistance Maximum travel distance
     * @return This Ray3f instance for chaining
     */
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
            this.invDx = (Math.abs(this.dx) > 1e-9f) ? (1.0f / this.dx) : (this.dx >= 0 ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY);
            this.invDy = (Math.abs(this.dy) > 1e-9f) ? (1.0f / this.dy) : (this.dy >= 0 ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY);
            this.invDz = (Math.abs(this.dz) > 1e-9f) ? (1.0f / this.dz) : (this.dz >= 0 ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY);
        } else {
            this.dx = 0.0f;
            this.dy = 0.0f;
            this.dz = 0.0f;
            this.invDx = Float.POSITIVE_INFINITY;
            this.invDy = Float.POSITIVE_INFINITY;
            this.invDz = Float.POSITIVE_INFINITY;
        }

        this.maxDistance = maxDistance;
        return this;
    }

    /**
     * Configures this ray to traverse between two 3D spatial points.
     *
     * @param startX Starting X coordinate
     * @param startY Starting Y coordinate
     * @param startZ Starting Z coordinate
     * @param endX   Target X coordinate
     * @param endY   Target Y coordinate
     * @param endZ   Target Z coordinate
     * @return This Ray3f instance for chaining
     */
    public Ray3f setFromPoints(float startX, float startY, float startZ, float endX, float endY, float endZ) {
        float dirX = endX - startX;
        float dirY = endY - startY;
        float dirZ = endZ - startZ;
        float dist = (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        return set(startX, startY, startZ, dirX, dirY, dirZ, dist);
    }
}
