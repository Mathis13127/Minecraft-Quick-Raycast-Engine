package com.pixel.qve.state;

import com.pixel.qve.world.VoxelFace;

import java.util.Objects;

/**
 * Axis-aligned bounding box within local voxel coordinates [0.0, 1.0].
 * Provides analytical slab intersection testing with exact hit distance and face determination.
 */
public final class SubBox {

    /** Minimum bounding box bounds in local voxel coordinates [0..1]. */
    public final float minX, minY, minZ;
    /** Maximum bounding box bounds in local voxel coordinates [0..1]. */
    public final float maxX, maxY, maxZ;

    /** Standard full cube bounding box [0, 0, 0] to [1, 1, 1]. */
    public static final SubBox FULL = new SubBox(0f, 0f, 0f, 1f, 1f, 1f);

    /**
     * Constructs a SubBox with the given minimum and maximum coordinate bounds.
     *
     * @param minX Minimum X [0..1]
     * @param minY Minimum Y [0..1]
     * @param minZ Minimum Z [0..1]
     * @param maxX Maximum X [0..1]
     * @param maxY Maximum Y [0..1]
     * @param maxZ Maximum Z [0..1]
     */
    public SubBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException(String.format("Invalid box bounds: [%.2f, %.2f, %.2f] to [%.2f, %.2f, %.2f]",
                minX, minY, minZ, maxX, maxY, maxZ));
        }
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    /**
     * Checks if this bounding box spans the entire 1x1x1 cube.
     *
     * @return True if full cube
     */
    public boolean isFullBlock() {
        return minX == 0f && minY == 0f && minZ == 0f && maxX == 1f && maxY == 1f && maxZ == 1f;
    }

    /**
     * Intersects a ray in local voxel space [0.0, 1.0].
     *
     * @param ox     Ray origin X relative to voxel minimum corner (startX - voxelX)
     * @param oy     Ray origin Y relative to voxel minimum corner (startY - voxelY)
     * @param oz     Ray origin Z relative to voxel minimum corner (startZ - voxelZ)
     * @param dx     Ray direction X
     * @param dy     Ray direction Y
     * @param dz     Ray direction Z
     * @param tEntry Distance along ray entering this voxel
     * @param tExit  Distance along ray exiting this voxel
     * @param hitOut Output container receiving impact distance and face if hit
     * @return True if ray intersects this sub-box within [tEntry, tExit]
     */
    public boolean intersect(double ox, double oy, double oz,
                             double dx, double dy, double dz,
                             double tEntry, double tExit,
                             SubBoxHit hitOut) {
        // Point-blank check: ray origin inside sub-box
        if (ox >= minX && ox <= maxX && oy >= minY && oy <= maxY && oz >= minZ && oz <= maxZ) {
            hitOut.t = Math.max(0.0, tEntry);
            hitOut.face = VoxelFace.NONE;
            return true;
        }

        double tNear = tEntry;
        double tFar = tExit;
        VoxelFace hitFace = VoxelFace.NONE;

        // X axis slab
        if (dx != 0.0) {
            double t1 = (minX - ox) / dx;
            double t2 = (maxX - ox) / dx;
            VoxelFace nearFace = (dx > 0) ? VoxelFace.WEST : VoxelFace.EAST;

            if (t1 > t2) {
                double temp = t1; t1 = t2; t2 = temp;
            }

            if (t1 > tNear) {
                tNear = t1;
                hitFace = nearFace;
            }
            if (t2 < tFar) {
                tFar = t2;
            }
            if (tNear > tFar) return false;
        } else if (ox < minX || ox > maxX) {
            return false;
        }

        // Y axis slab
        if (dy != 0.0) {
            double t1 = (minY - oy) / dy;
            double t2 = (maxY - oy) / dy;
            VoxelFace nearFace = (dy > 0) ? VoxelFace.DOWN : VoxelFace.UP;

            if (t1 > t2) {
                double temp = t1; t1 = t2; t2 = temp;
            }

            if (t1 > tNear) {
                tNear = t1;
                hitFace = nearFace;
            }
            if (t2 < tFar) {
                tFar = t2;
            }
            if (tNear > tFar) return false;
        } else if (oy < minY || oy > maxY) {
            return false;
        }

        // Z axis slab
        if (dz != 0.0) {
            double t1 = (minZ - oz) / dz;
            double t2 = (maxZ - oz) / dz;
            VoxelFace nearFace = (dz > 0) ? VoxelFace.NORTH : VoxelFace.SOUTH;

            if (t1 > t2) {
                double temp = t1; t1 = t2; t2 = temp;
            }

            if (t1 > tNear) {
                tNear = t1;
                hitFace = nearFace;
            }
            if (t2 < tFar) {
                tFar = t2;
            }
            if (tNear > tFar) return false;
        } else if (oz < minZ || oz > maxZ) {
            return false;
        }

        if (tNear > tExit || tFar < tEntry || tNear < 0.0) {
            return false;
        }

        hitOut.t = tNear;
        hitOut.face = hitFace;
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SubBox subBox = (SubBox) o;
        return Float.compare(subBox.minX, minX) == 0 &&
                Float.compare(subBox.minY, minY) == 0 &&
                Float.compare(subBox.minZ, minZ) == 0 &&
                Float.compare(subBox.maxX, maxX) == 0 &&
                Float.compare(subBox.maxY, maxY) == 0 &&
                Float.compare(subBox.maxZ, maxZ) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Mutable result container for sub-box intersection queries.
     */
    public static final class SubBoxHit {
        /** Intersection distance along the ray. */
        public double t;
        /** Normal boundary face struck by the ray. */
        public VoxelFace face;

        /**
         * Constructs a new SubBoxHit initialized to a miss state.
         */
        public SubBoxHit() {
            reset();
        }

        /**
         * Resets this hit result to a miss state.
         */
        public void reset() {
            this.t = Double.MAX_VALUE;
            this.face = VoxelFace.NONE;
        }
    }
}
