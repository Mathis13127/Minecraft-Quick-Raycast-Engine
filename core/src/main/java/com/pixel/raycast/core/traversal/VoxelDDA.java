package com.pixel.raycast.core.traversal;

import com.pixel.raycast.core.api.IVoxelGrid;
import com.pixel.raycast.core.api.RayHitResult;
import com.pixel.raycast.core.math.Ray3f;
import com.pixel.raycast.core.voxel.Heightmap2D;
import com.pixel.raycast.core.voxel.VoxelFace;
import com.pixel.raycast.core.voxel.VoxelSection;

/**
 * High-performance 3D Digital Differential Analyzer (Amanatides and Woo).
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

        if (maxDist <= 0.0 || Double.isNaN(maxDist) ||
            Double.isNaN(startX) || Double.isNaN(startY) || Double.isNaN(startZ) ||
            Double.isNaN(dirX) || Double.isNaN(dirY) || Double.isNaN(dirZ)) {
            return false;
        }

        double dirLenSq = dirX * dirX + dirY * dirY + dirZ * dirZ;
        if (dirLenSq < 1e-12) {
            int x0 = (int) Math.floor(startX);
            int y0 = (int) Math.floor(startY);
            int z0 = (int) Math.floor(startZ);
            VoxelSection sec0 = grid.getSection(x0 >> 4, y0 >> 4, z0 >> 4);
            if (sec0 != null && !sec0.isEmpty() && sec0.isSolid(x0 & 15, y0 & 15, z0 & 15)) {
                short blockId = sec0.getBlockId(x0 & 15, y0 & 15, z0 & 15);
                com.pixel.raycast.core.shape.SubBox.SubBoxHit subHit0 = new com.pixel.raycast.core.shape.SubBox.SubBoxHit();
                return evaluateVoxelHit(startX, startY, startZ, 0, 0, 0, x0, y0, z0, 0.0, 0.0, VoxelFace.NONE, blockId, grid, subHit0, result);
            }
            return false;
        }

        // Determine step direction along each axis
        int stepX = (dirX > 0) ? 1 : ((dirX < 0) ? -1 : 0);
        int stepY = (dirY > 0) ? 1 : ((dirY < 0) ? -1 : 0);
        int stepZ = (dirZ > 0) ? 1 : ((dirZ < 0) ? -1 : 0);

        short highestWorldY = grid.getHighestWorldY();
        short lowestWorldY = grid.getLowestWorldY();

        // Dynamic Analytical Ray-AABB vertical Y-slab truncation (datapacks / custom dimensions compatible)
        double tExit = maxDist;
        if (lowestWorldY > Short.MIN_VALUE && highestWorldY < Short.MAX_VALUE) {
            double worldMinY = (double) lowestWorldY;
            double worldMaxY = (double) highestWorldY + 1.0;

            if (stepY > 0) {
                if (startY >= worldMaxY) {
                    return false; // Pointing up above ceiling
                }
                double tExitY = (worldMaxY - startY) / dirY;
                if (tExitY < tExit) tExit = tExitY;
            } else if (stepY < 0) {
                if (startY < worldMinY) {
                    return false; // Pointing down below floor
                }
                double tExitY = (worldMinY - startY) / dirY;
                if (tExitY < tExit) tExit = tExitY;
            } else {
                if (startY < worldMinY || startY >= worldMaxY) {
                    return false; // Completely outside vertical limits
                }
            }
        }

        // Dynamic Analytical Ray-AABB horizontal X/Z bounding truncation
        if (grid.hasWorldBounds()) {
            double worldMinX = grid.getWorldMinX();
            double worldMaxX = grid.getWorldMaxX();
            double worldMinZ = grid.getWorldMinZ();
            double worldMaxZ = grid.getWorldMaxZ();

            if (stepX > 0) {
                if (startX >= worldMaxX) return false;
                double tExitX = (worldMaxX - startX) / dirX;
                if (tExitX < tExit) tExit = tExitX;
            } else if (stepX < 0) {
                if (startX < worldMinX) return false;
                double tExitX = (worldMinX - startX) / dirX;
                if (tExitX < tExit) tExit = tExitX;
            } else {
                if (startX < worldMinX || startX >= worldMaxX) return false;
            }

            if (stepZ > 0) {
                if (startZ >= worldMaxZ) return false;
                double tExitZ = (worldMaxZ - startZ) / dirZ;
                if (tExitZ < tExit) tExit = tExitZ;
            } else if (stepZ < 0) {
                if (startZ < worldMinZ) return false;
                double tExitZ = (worldMinZ - startZ) / dirZ;
                if (tExitZ < tExit) tExit = tExitZ;
            } else {
                if (startZ < worldMinZ || startZ >= worldMaxZ) return false;
            }
        }

        if (tExit <= 0.0) {
            return false;
        }
        if (tExit < maxDist) {
            maxDist = tExit;
        }

        // Instant O(1) global sky culling if ray stays above all solid geometry in the world
        double endY = startY + maxDist * dirY;
        double minRayY = Math.min(startY, endY);
        if (highestWorldY > Short.MIN_VALUE && highestWorldY < Short.MAX_VALUE && minRayY >= (highestWorldY + 1.0)) {
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
        int currentChunkX = currentSx;
        int currentChunkZ = currentSz;
        com.pixel.raycast.core.cache.VoxelChunkColumn currentColumn = grid.getColumn(currentSx, currentSz);
        VoxelSection currentSection = (currentColumn != null) ? currentColumn.getSection(currentSy) : null;
        if (currentSection == null) {
            currentSection = grid.getSection(currentSx, currentSy, currentSz);
        }

        // Instant O(1) out-of-bounds culling if start point is already outside world heading away
        int x0 = (int) Math.floor(startX);
        int z0 = (int) Math.floor(startZ);
        if (grid.isOutOfBounds(x0, z0, stepX, stepZ)) {
            return false; // Zero DDA steps: ray is outside all known regions and pointing away into void
        }

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

        // Chunk tracking registers across horizontal traversal
        com.pixel.raycast.core.voxel.Heightmap2D currentHm = (currentColumn != null) ? currentColumn.getHeightmap() : grid.getHeightmap(currentChunkX, currentChunkZ);

        // Point-blank check: start position inside voxel with shape precision
        if (currentSection != null && !currentSection.isEmpty() && currentSection.isSolid(x & 15, y & 15, z & 15)) {
            if (checkHit(startX, startY, startZ, dirX, dirY, dirZ, x, y, z, 0.0, tMaxX, tMaxY, tMaxZ, VoxelFace.NONE, currentSection, grid, subHit, result)) {
                return true;
            }
        }

        while (t <= maxDist) {
            // Macro-skip: jump across empty space in 1 step
            if (currentSection == null || currentSection.isEmpty()) {
                // Keep chunk heightmap synchronized with current horizontal position
                if (currentSx != currentChunkX || currentSz != currentChunkZ) {
                    currentChunkX = currentSx;
                    currentChunkZ = currentSz;
                    currentColumn = grid.getColumn(currentChunkX, currentChunkZ);
                    currentHm = (currentColumn != null) ? currentColumn.getHeightmap() : grid.getHeightmap(currentChunkX, currentChunkZ);
                }

                // 0. Out-of-bounds termination: ray left all known geometry heading into void
                if (grid.isOutOfBounds(x, z, stepX, stepZ)) {
                    break;
                }
                if (stepY > 0 && y > highestWorldY) {
                    break;
                }
                if (stepY < 0 && y < lowestWorldY) {
                    break;
                }

                // 1. Region-Level Macro-Skip: bypass entire 512x512 block region if absent or empty
                int currentRx = currentSx >> 5;
                int currentRz = currentSz >> 5;
                if (grid.isRegionEmpty(currentRx, currentRz)) {
                    int regMinX = currentRx << 9;
                    int regMinZ = currentRz << 9;

                    double exitRegTx = (stepX > 0) ? ((regMinX + 512.0 - startX) * tDeltaX) : ((stepX < 0) ? ((startX - regMinX) * tDeltaX) : Double.MAX_VALUE);
                    double exitRegTz = (stepZ > 0) ? ((regMinZ + 512.0 - startZ) * tDeltaZ) : ((stepZ < 0) ? ((startZ - regMinZ) * tDeltaZ) : Double.MAX_VALUE);
                    double exitRegT = Math.min(exitRegTx, exitRegTz);

                    if (exitRegT > t) {
                        if (exitRegT > maxDist) {
                            break;
                        }

                        if (Math.abs(exitRegTx - exitRegTz) < 1e-9) {
                            x = (stepX > 0) ? (regMinX + 512) : (regMinX - 1);
                            z = (stepZ > 0) ? (regMinZ + 512) : (regMinZ - 1);
                            y = (int) Math.floor(startY + exitRegT * dirY);
                            t = exitRegTx;
                            lastFace = (stepX > 0) ? VoxelFace.WEST : VoxelFace.EAST;
                        } else if (exitRegT == exitRegTx) {
                            x = (stepX > 0) ? (regMinX + 512) : (regMinX - 1);
                            y = (int) Math.floor(startY + exitRegT * dirY);
                            z = Math.max(regMinZ, Math.min(regMinZ + 511, (int) Math.floor(startZ + exitRegT * dirZ)));
                            t = exitRegTx;
                            lastFace = (stepX > 0) ? VoxelFace.WEST : VoxelFace.EAST;
                        } else {
                            x = Math.max(regMinX, Math.min(regMinX + 511, (int) Math.floor(startX + exitRegT * dirX)));
                            y = (int) Math.floor(startY + exitRegT * dirY);
                            z = (stepZ > 0) ? (regMinZ + 512) : (regMinZ - 1);
                            t = exitRegTz;
                            lastFace = (stepZ > 0) ? VoxelFace.NORTH : VoxelFace.SOUTH;
                        }

                        tMaxX = (stepX > 0) ? ((x + 1.0 - startX) * tDeltaX) : ((stepX < 0) ? ((startX - x) * tDeltaX) : Double.MAX_VALUE);
                        tMaxY = (stepY > 0) ? ((y + 1.0 - startY) * tDeltaY) : ((stepY < 0) ? ((startY - y) * tDeltaY) : Double.MAX_VALUE);
                        tMaxZ = (stepZ > 0) ? ((z + 1.0 - startZ) * tDeltaZ) : ((stepZ < 0) ? ((startZ - z) * tDeltaZ) : Double.MAX_VALUE);

                        currentSx = x >> 4;
                        currentSy = y >> 4;
                        currentSz = z >> 4;
                        currentChunkX = currentSx;
                        currentChunkZ = currentSz;
                        currentColumn = grid.getColumn(currentChunkX, currentChunkZ);
                        currentHm = (currentColumn != null) ? currentColumn.getHeightmap() : grid.getHeightmap(currentChunkX, currentChunkZ);
                        currentSection = (currentColumn != null) ? currentColumn.getSection(currentSy) : null;
                        if (currentSection == null) {
                            currentSection = grid.getSection(currentSx, currentSy, currentSz);
                        }

                        if (currentSection != null && !currentSection.isEmpty() && currentSection.isSolid(x & 15, y & 15, z & 15)) {
                            if (checkHit(startX, startY, startZ, dirX, dirY, dirZ, x, y, z, t, tMaxX, tMaxY, tMaxZ, lastFace, currentSection, grid, subHit, result)) {
                                return true;
                            }
                        }
                        continue;
                    }
                }

                // 2. Chunk-Level Macro-Skip: bypass entire 16x16 chunk column horizontally if above terrain or empty column
                boolean columnEmpty = (currentColumn != null && currentColumn.isEmpty());
                if (currentHm != null || columnEmpty) {
                    short highestY = (currentHm != null) ? currentHm.getHighestY() : Heightmap2D.VOID_Y;
                    int chunkMinX = currentChunkX << 4;
                    int chunkMinZ = currentChunkZ << 4;

                    double exitChunkTx = (stepX > 0) ? ((chunkMinX + 16.0 - startX) * tDeltaX) : ((stepX < 0) ? ((startX - chunkMinX) * tDeltaX) : Double.MAX_VALUE);
                    double exitChunkTz = (stepZ > 0) ? ((chunkMinZ + 16.0 - startZ) * tDeltaZ) : ((stepZ < 0) ? ((startZ - chunkMinZ) * tDeltaZ) : Double.MAX_VALUE);
                    double exitChunkT = Math.min(exitChunkTx, exitChunkTz);

                    if (exitChunkT > t) {
                        double tEnd = Math.min(exitChunkT, maxDist);
                        double yCurrent = startY + t * dirY;
                        double yEnd = startY + tEnd * dirY;
                        double minYInChunk = Math.min(yCurrent, yEnd);

                        if (columnEmpty || (currentHm != null && currentHm.isAboveTerrain(minYInChunk))) {
                            if (exitChunkT > maxDist) {
                                break;
                            }

                            if (Math.abs(exitChunkTx - exitChunkTz) < 1e-9) {
                                x = (stepX > 0) ? (chunkMinX + 16) : (chunkMinX - 1);
                                z = (stepZ > 0) ? (chunkMinZ + 16) : (chunkMinZ - 1);
                                y = (int) Math.floor(startY + exitChunkT * dirY);
                                t = exitChunkTx;
                                lastFace = (stepX > 0) ? VoxelFace.WEST : VoxelFace.EAST;
                            } else if (exitChunkT == exitChunkTx) {
                                x = (stepX > 0) ? (chunkMinX + 16) : (chunkMinX - 1);
                                y = (int) Math.floor(startY + exitChunkT * dirY);
                                z = Math.max(chunkMinZ, Math.min(chunkMinZ + 15, (int) Math.floor(startZ + exitChunkT * dirZ)));
                                t = exitChunkTx;
                                lastFace = (stepX > 0) ? VoxelFace.WEST : VoxelFace.EAST;
                            } else {
                                x = Math.max(chunkMinX, Math.min(chunkMinX + 15, (int) Math.floor(startX + exitChunkT * dirX)));
                                y = (int) Math.floor(startY + exitChunkT * dirY);
                                z = (stepZ > 0) ? (chunkMinZ + 16) : (chunkMinZ - 1);
                                t = exitChunkTz;
                                lastFace = (stepZ > 0) ? VoxelFace.NORTH : VoxelFace.SOUTH;
                            }

                            tMaxX = (stepX > 0) ? ((x + 1.0 - startX) * tDeltaX) : ((stepX < 0) ? ((startX - x) * tDeltaX) : Double.MAX_VALUE);
                            tMaxY = (stepY > 0) ? ((y + 1.0 - startY) * tDeltaY) : ((stepY < 0) ? ((startY - y) * tDeltaY) : Double.MAX_VALUE);
                            tMaxZ = (stepZ > 0) ? ((z + 1.0 - startZ) * tDeltaZ) : ((stepZ < 0) ? ((startZ - z) * tDeltaZ) : Double.MAX_VALUE);

                            currentSx = x >> 4;
                            currentSy = y >> 4;
                            currentSz = z >> 4;
                            if (currentSx != currentChunkX || currentSz != currentChunkZ) {
                                currentChunkX = currentSx;
                                currentChunkZ = currentSz;
                                currentColumn = grid.getColumn(currentChunkX, currentChunkZ);
                                currentHm = (currentColumn != null) ? currentColumn.getHeightmap() : grid.getHeightmap(currentChunkX, currentChunkZ);
                            }
                            currentSection = (currentColumn != null) ? currentColumn.getSection(currentSy) : null;
                            if (currentSection == null) {
                                currentSection = grid.getSection(currentSx, currentSy, currentSz);
                            }

                            if (currentSection != null && !currentSection.isEmpty() && currentSection.isSolid(x & 15, y & 15, z & 15)) {
                                if (checkHit(startX, startY, startZ, dirX, dirY, dirZ, x, y, z, t, tMaxX, tMaxY, tMaxZ, lastFace, currentSection, grid, subHit, result)) {
                                    return true;
                                }
                            }
                            continue;
                        }
                    }
                } else if (lowestWorldY > Short.MIN_VALUE) {
                    double yCurrent = startY + t * dirY;
                    if (yCurrent < lowestWorldY && dirY <= 0) {
                        break;
                    }
                }

                // 2. Section-Level Macro-Skip: jump across empty 16x16x16 sections in 1 step
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
                    if (currentSx != currentChunkX || currentSz != currentChunkZ) {
                        currentChunkX = currentSx;
                        currentChunkZ = currentSz;
                        currentColumn = grid.getColumn(currentChunkX, currentChunkZ);
                        currentHm = (currentColumn != null) ? currentColumn.getHeightmap() : grid.getHeightmap(currentChunkX, currentChunkZ);
                    }
                    currentSection = (currentColumn != null) ? currentColumn.getSection(currentSy) : null;
                    if (currentSection == null) {
                        currentSection = grid.getSection(currentSx, currentSy, currentSz);
                    }

                    if (currentSection != null && !currentSection.isEmpty() && currentSection.isSolid(x & 15, y & 15, z & 15)) {
                        if (checkHit(startX, startY, startZ, dirX, dirY, dirZ, x, y, z, t, tMaxX, tMaxY, tMaxZ, lastFace, currentSection, grid, subHit, result)) {
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

            if (stepY > 0 && y > highestWorldY) {
                break;
            }
            if (stepY < 0 && y < lowestWorldY) {
                break;
            }

            // Update cached section if crossed chunk section boundary
            int sx = x >> 4;
            int sy = y >> 4;
            int sz = z >> 4;
            if (sx != currentSx || sy != currentSy || sz != currentSz) {
                if (sx != currentSx || sz != currentSz) {
                    currentSx = sx;
                    currentSz = sz;
                    currentChunkX = sx;
                    currentChunkZ = sz;
                    currentColumn = grid.getColumn(sx, sz);
                    currentHm = (currentColumn != null) ? currentColumn.getHeightmap() : grid.getHeightmap(sx, sz);
                }
                currentSy = sy;
                currentSection = (currentColumn != null) ? currentColumn.getSection(sy) : null;
                if (currentSection == null) {
                    currentSection = grid.getSection(sx, sy, sz);
                }
            }

            // Evaluate occupancy
            if (currentSection != null && !currentSection.isEmpty() && currentSection.isSolid(x & 15, y & 15, z & 15)) {
                if (checkHit(startX, startY, startZ, dirX, dirY, dirZ, x, y, z, t, tMaxX, tMaxY, tMaxZ, lastFace, currentSection, grid, subHit, result)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean checkHit(double startX, double startY, double startZ,
                                    double dirX, double dirY, double dirZ,
                                    int x, int y, int z,
                                    double tEntry, double tMaxX, double tMaxY, double tMaxZ,
                                    VoxelFace face,
                                    VoxelSection section,
                                    IVoxelGrid grid,
                                    com.pixel.raycast.core.shape.SubBox.SubBoxHit subHit,
                                    RayHitResult result) {
        if (section.allSolidAreFullCubes()) {
            short blockId = section.getBlockId(x & 15, y & 15, z & 15);
            double hitX = startX + tEntry * dirX;
            double hitY = startY + tEntry * dirY;
            double hitZ = startZ + tEntry * dirZ;
            result.set(true, hitX, hitY, hitZ, x, y, z, face, blockId, tEntry);
            return true;
        }
        short blockId = section.getBlockId(x & 15, y & 15, z & 15);
        double tExit = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
        return evaluateVoxelHit(startX, startY, startZ, dirX, dirY, dirZ, x, y, z, tEntry, tExit, face, blockId, grid, subHit, result);
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
        if (shapeRegistry == null || shapeRegistry.isFullCube(blockId)) {
            double hitX = startX + tEntry * dirX;
            double hitY = startY + tEntry * dirY;
            double hitZ = startZ + tEntry * dirZ;
            result.set(true, hitX, hitY, hitZ, x, y, z, entryFace, blockId, tEntry);
            return true;
        }

        com.pixel.raycast.core.shape.VoxelShape shape = shapeRegistry.getShape(blockId);

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
