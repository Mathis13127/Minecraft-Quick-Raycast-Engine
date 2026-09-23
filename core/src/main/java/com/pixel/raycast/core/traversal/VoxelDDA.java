package com.pixel.raycast.core.traversal;

import com.pixel.raycast.core.api.IVoxelGrid;
import com.pixel.raycast.core.api.RayHitResult;
import com.pixel.raycast.core.math.Ray3f;
import com.pixel.raycast.core.voxel.VoxelFace;
import com.pixel.raycast.core.voxel.VoxelSection;

/**
 * High-performance 3D Digital Differential Analyzer (Amanatides & Woo).
 * Walks voxel grid boundaries analytically with zero heap allocation.
 */
public final class VoxelDDA {

    private VoxelDDA() {}

    /**
     * Traverses the voxel grid along the specified ray using 3D DDA.
     *
     * @param ray    The ray containing origin, direction, and max distance
     * @param grid   The voxel world provider
     * @param result Mutable result object populated with impact data
     * @return True if a solid voxel was struck, false on miss
     */
    public static boolean trace(Ray3f ray, IVoxelGrid grid, RayHitResult result) {
        return trace(ray.ox, ray.oy, ray.oz, ray.dx, ray.dy, ray.dz, ray.maxDistance, grid, result);
    }

    /**
     * Traverses the voxel grid along the specified line segment using 3D DDA.
     *
     * @param startX Starting X coordinate
     * @param startY Starting Y coordinate
     * @param startZ Starting Z coordinate
     * @param dirX   Normalized direction X
     * @param dirY   Normalized direction Y
     * @param dirZ   Normalized direction Z
     * @param maxDist Maximum travel distance
     * @param grid   The voxel world provider
     * @param result Mutable result object populated with impact data
     * @return True if a solid voxel was struck, false on miss
     */
    public static boolean trace(double startX, double startY, double startZ,
                                double dirX, double dirY, double dirZ,
                                double maxDist, IVoxelGrid grid, RayHitResult result) {
        result.reset();

        if (maxDist <= 0.0 || Double.isNaN(maxDist)) {
            return false;
        }

        // Instant O(1) global sky culling if ray stays above all solid geometry in the world
        double endY = startY + maxDist * dirY;
        double minRayY = Math.min(startY, endY);
        if (minRayY >= (grid.getHighestWorldY() + 1.0)) {
            return false; // Zero DDA steps: ray never descends to any solid altitude
        }

        // Instant O(1) Heightmap sky culling if ray stays within one chunk column
        int startChunkX = ((int) Math.floor(startX)) >> 4;
        int startChunkZ = ((int) Math.floor(startZ)) >> 4;
        int endChunkX = ((int) Math.floor(startX + maxDist * dirX)) >> 4;
        int endChunkZ = ((int) Math.floor(startZ + maxDist * dirZ)) >> 4;

        if (startChunkX == endChunkX && startChunkZ == endChunkZ) {
            com.pixel.raycast.core.voxel.Heightmap2D hm = grid.getHeightmap(startChunkX, startChunkZ);
            if (hm != null && hm.isAboveTerrain(minRayY)) {
                return false; // Guaranteed pure sky traversal: zero DDA steps
            }
        }

        // Current voxel integer coordinate
        int x = (int) Math.floor(startX);
        int y = (int) Math.floor(startY);
        int z = (int) Math.floor(startZ);

        // Section caching registers across the traversal
        int currentSx = x >> 4;
        int currentSy = y >> 4;
        int currentSz = z >> 4;
        VoxelSection currentSection = grid.getSection(currentSx, currentSy, currentSz);

        // Point-blank check: start position inside solid voxel
        if (currentSection != null && !currentSection.isEmpty() && currentSection.isSolid(x & 15, y & 15, z & 15)) {
            short blockId = currentSection.getBlockId(x & 15, y & 15, z & 15);
            result.set(true, startX, startY, startZ, x, y, z, VoxelFace.NONE, blockId, 0.0);
            return true;
        }

        // Determine step direction along each axis
        int stepX = (dirX > 0) ? 1 : ((dirX < 0) ? -1 : 0);
        int stepY = (dirY > 0) ? 1 : ((dirY < 0) ? -1 : 0);
        int stepZ = (dirZ > 0) ? 1 : ((dirZ < 0) ? -1 : 0);

        // Interval between voxel boundaries along each axis
        double tDeltaX = (stepX != 0) ? Math.abs(1.0 / dirX) : Double.MAX_VALUE;
        double tDeltaY = (stepY != 0) ? Math.abs(1.0 / dirY) : Double.MAX_VALUE;
        double tDeltaZ = (stepZ != 0) ? Math.abs(1.0 / dirZ) : Double.MAX_VALUE;

        // Distance to first voxel boundary
        double tMaxX = (stepX > 0) ? ((x + 1.0 - startX) * tDeltaX) : ((stepX < 0) ? ((startX - x) * tDeltaX) : Double.MAX_VALUE);
        double tMaxY = (stepY > 0) ? ((y + 1.0 - startY) * tDeltaY) : ((stepY < 0) ? ((startY - y) * tDeltaY) : Double.MAX_VALUE);
        double tMaxZ = (stepZ > 0) ? ((z + 1.0 - startZ) * tDeltaZ) : ((stepZ < 0) ? ((startZ - z) * tDeltaZ) : Double.MAX_VALUE);

        double t = 0.0;
        VoxelFace lastFace = VoxelFace.NONE;
        com.pixel.raycast.core.shape.SubBox.SubBoxHit subHit = new com.pixel.raycast.core.shape.SubBox.SubBoxHit();

        // Point-blank check: start position inside voxel with shape precision
        if (currentSection != null && !currentSection.isEmpty() && currentSection.isSolid(x & 15, y & 15, z & 15)) {
            short blockId = currentSection.getBlockId(x & 15, y & 15, z & 15);
            double tExit = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (evaluateVoxelHit(startX, startY, startZ, dirX, dirY, dirZ, x, y, z, 0.0, tExit, VoxelFace.NONE, blockId, grid, subHit, result)) {
                return true;
            }
        }

        while (t <= maxDist) {
            // Macro-skip: jump across empty 16x16x16 sections in 1 step
            if (currentSection == null || currentSection.isEmpty()) {
                int minX = currentSx << 4;
                int minY = currentSy << 4;
                int minZ = currentSz << 4;

                double exitTx = (stepX > 0) ? ((minX + 16.0 - startX) * tDeltaX) : ((stepX < 0) ? ((startX - minX) * tDeltaX) : Double.MAX_VALUE);
                double exitTy = (stepY > 0) ? ((minY + 16.0 - startY) * tDeltaY) : ((stepY < 0) ? ((startY - minY) * tDeltaY) : Double.MAX_VALUE);
                double exitTz = (stepZ > 0) ? ((minZ + 16.0 - startZ) * tDeltaZ) : ((stepZ < 0) ? ((startZ - minZ) * tDeltaZ) : Double.MAX_VALUE);

                double exitT = Math.min(exitTx, Math.min(exitTy, exitTz));

                if (exitT > t) {
                    if (exitT > maxDist) {
                        break;
                    }

                    if (exitT == exitTx) {
                        x = (stepX > 0) ? (minX + 16) : (minX - 1);
                        y = Math.max(minY, Math.min(minY + 15, (int) Math.floor(startY + exitT * dirY)));
                        z = Math.max(minZ, Math.min(minZ + 15, (int) Math.floor(startZ + exitT * dirZ)));
                        t = exitTx;
                        lastFace = (stepX > 0) ? VoxelFace.WEST : VoxelFace.EAST;
                    } else if (exitT == exitTy) {
                        x = Math.max(minX, Math.min(minX + 15, (int) Math.floor(startX + exitT * dirX)));
                        y = (stepY > 0) ? (minY + 16) : (minY - 1);
                        z = Math.max(minZ, Math.min(minZ + 15, (int) Math.floor(startZ + exitT * dirZ)));
                        t = exitTy;
                        lastFace = (stepY > 0) ? VoxelFace.DOWN : VoxelFace.UP;
                    } else {
                        x = Math.max(minX, Math.min(minX + 15, (int) Math.floor(startX + exitT * dirX)));
                        y = Math.max(minY, Math.min(minY + 15, (int) Math.floor(startY + exitT * dirY)));
                        z = (stepZ > 0) ? (minZ + 16) : (minZ - 1);
                        t = exitTz;
                        lastFace = (stepZ > 0) ? VoxelFace.NORTH : VoxelFace.SOUTH;
                    }

                    tMaxX = (stepX > 0) ? ((x + 1.0 - startX) * tDeltaX) : ((stepX < 0) ? ((startX - x) * tDeltaX) : Double.MAX_VALUE);
                    tMaxY = (stepY > 0) ? ((y + 1.0 - startY) * tDeltaY) : ((stepY < 0) ? ((startY - y) * tDeltaY) : Double.MAX_VALUE);
                    tMaxZ = (stepZ > 0) ? ((z + 1.0 - startZ) * tDeltaZ) : ((stepZ < 0) ? ((startZ - z) * tDeltaZ) : Double.MAX_VALUE);

                    currentSx = x >> 4;
                    currentSy = y >> 4;
                    currentSz = z >> 4;
                    currentSection = grid.getSection(currentSx, currentSy, currentSz);

                    if (currentSection != null && !currentSection.isEmpty() && currentSection.isSolid(x & 15, y & 15, z & 15)) {
                        short blockId = currentSection.getBlockId(x & 15, y & 15, z & 15);
                        double tExitLanding = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
                        if (evaluateVoxelHit(startX, startY, startZ, dirX, dirY, dirZ, x, y, z, t, tExitLanding, lastFace, blockId, grid, subHit, result)) {
                            return true;
                        }
                    }
                    continue;
                }
            }

            // Advance to the nearest boundary plane
            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    x += stepX;
                    t = tMaxX;
                    tMaxX += tDeltaX;
                    lastFace = (stepX > 0) ? VoxelFace.WEST : VoxelFace.EAST;
                } else {
                    z += stepZ;
                    t = tMaxZ;
                    tMaxZ += tDeltaZ;
                    lastFace = (stepZ > 0) ? VoxelFace.NORTH : VoxelFace.SOUTH;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    y += stepY;
                    t = tMaxY;
                    tMaxY += tDeltaY;
                    lastFace = (stepY > 0) ? VoxelFace.DOWN : VoxelFace.UP;
                } else {
                    z += stepZ;
                    t = tMaxZ;
                    tMaxZ += tDeltaZ;
                    lastFace = (stepZ > 0) ? VoxelFace.NORTH : VoxelFace.SOUTH;
                }
            }

            if (t > maxDist) {
                break;
            }

            // Update cached section if crossed chunk section boundary
            int sx = x >> 4;
            int sy = y >> 4;
            int sz = z >> 4;
            if (sx != currentSx || sy != currentSy || sz != currentSz) {
                currentSx = sx;
                currentSy = sy;
                currentSz = sz;
                currentSection = grid.getSection(sx, sy, sz);
            }

            // Evaluate occupancy
            if (currentSection != null && !currentSection.isEmpty() && currentSection.isSolid(x & 15, y & 15, z & 15)) {
                short blockId = currentSection.getBlockId(x & 15, y & 15, z & 15);
                double tExit = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
                if (evaluateVoxelHit(startX, startY, startZ, dirX, dirY, dirZ, x, y, z, t, tExit, lastFace, blockId, grid, subHit, result)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean evaluateVoxelHit(double startX, double startY, double startZ,
                                           double dirX, double dirY, double dirZ,
                                           int x, int y, int z,
                                           double tEntry, double tExit,
                                           VoxelFace entryFace,
                                           short blockId,
                                           IVoxelGrid grid,
                                           com.pixel.raycast.core.shape.SubBox.SubBoxHit subHitOut,
                                           RayHitResult result) {
        com.pixel.raycast.core.shape.ShapeRegistry shapeRegistry = grid.getShapeRegistry();
        com.pixel.raycast.core.shape.VoxelShape shape = (shapeRegistry != null) ? shapeRegistry.getShape(blockId) : com.pixel.raycast.core.shape.VoxelShape.FULL_CUBE;

        if (shape.isFullCube()) {
            double hitX = startX + tEntry * dirX;
            double hitY = startY + tEntry * dirY;
            double hitZ = startZ + tEntry * dirZ;
            result.set(true, hitX, hitY, hitZ, x, y, z, entryFace, blockId, tEntry);
            return true;
        }

        // Sub-box intersection
        double localOx = startX - x;
        double localOy = startY - y;
        double localOz = startZ - z;
        subHitOut.reset();

        if (shape.intersect(localOx, localOy, localOz, dirX, dirY, dirZ, tEntry, tExit, subHitOut)) {
            double hitX = startX + subHitOut.t * dirX;
            double hitY = startY + subHitOut.t * dirY;
            double hitZ = startZ + subHitOut.t * dirZ;
            VoxelFace hitFace = (subHitOut.face != VoxelFace.NONE) ? subHitOut.face : entryFace;
            result.set(true, hitX, hitY, hitZ, x, y, z, hitFace, blockId, subHitOut.t);
            return true;
        }

        // Ray passed through the open part of the complex shape without impact
        return false;
    }
}
