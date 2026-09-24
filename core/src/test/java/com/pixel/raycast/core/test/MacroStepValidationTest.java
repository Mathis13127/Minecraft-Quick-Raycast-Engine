package com.pixel.raycast.core.test;

import com.pixel.raycast.core.api.IVoxelGrid;
import com.pixel.raycast.core.api.RayHitResult;
import com.pixel.raycast.core.traversal.VoxelDDA;
import com.pixel.raycast.core.voxel.VoxelFace;
import com.pixel.raycast.core.voxel.VoxelSection;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates correctness of empty section macro-skipping against dense voxel-by-voxel traversal.
 */
public class MacroStepValidationTest {

    static class SimpleVoxelGrid implements IVoxelGrid {
        final Map<Long, VoxelSection> sections = new HashMap<>();
        final Map<Long, com.pixel.raycast.core.voxel.Heightmap2D> heightmaps = new HashMap<>();

        static long key(int sx, int sy, int sz) {
            return (((long) sx & 0x3FFFFFL)) | (((long) sz & 0x3FFFFFL) << 22) | (((long) sy & 0xFFFFFL) << 44);
        }

        static long chunkKey(int cx, int cz) {
            return (((long) cx & 0xFFFFFFFFL)) | (((long) cz & 0xFFFFFFFFL) << 32);
        }

        @Override
        public VoxelSection getSection(int sectionX, int sectionY, int sectionZ) {
            return sections.get(key(sectionX, sectionY, sectionZ));
        }

        @Override
        public com.pixel.raycast.core.voxel.Heightmap2D getHeightmap(int chunkX, int chunkZ) {
            return heightmaps.get(chunkKey(chunkX, chunkZ));
        }

        public void setVoxel(int wx, int wy, int wz, boolean solid, short blockId) {
            int sx = wx >> 4;
            int sy = wy >> 4;
            int sz = wz >> 4;
            long k = key(sx, sy, sz);
            VoxelSection sec = sections.computeIfAbsent(k, id -> new VoxelSection());
            sec.setVoxel(wx & 15, wy & 15, wz & 15, solid, blockId);

            if (solid) {
                com.pixel.raycast.core.voxel.Heightmap2D hm = heightmaps.computeIfAbsent(chunkKey(sx, sz), id -> new com.pixel.raycast.core.voxel.Heightmap2D());
                hm.updateMax(wx & 15, wz & 15, (short) wy);
            }
        }
    }

    /**
     * Reference baseline DDA without macro-stepping to ensure 100% mathematical parity.
     */
    static boolean referenceTrace(double startX, double startY, double startZ,
                                  double dirX, double dirY, double dirZ,
                                  double maxDist, IVoxelGrid grid, RayHitResult result) {
        result.reset();
        if (maxDist <= 0.0 || Double.isNaN(maxDist)) return false;

        int x = (int) Math.floor(startX);
        int y = (int) Math.floor(startY);
        int z = (int) Math.floor(startZ);

        int currentSx = x >> 4;
        int currentSy = y >> 4;
        int currentSz = z >> 4;
        VoxelSection currentSection = grid.getSection(currentSx, currentSy, currentSz);

        if (currentSection != null && !currentSection.isEmpty() && currentSection.isSolid(x & 15, y & 15, z & 15)) {
            short blockId = currentSection.getBlockId(x & 15, y & 15, z & 15);
            result.set(true, startX, startY, startZ, x, y, z, VoxelFace.NONE, blockId, 0.0);
            return true;
        }

        int stepX = (dirX > 0) ? 1 : ((dirX < 0) ? -1 : 0);
        int stepY = (dirY > 0) ? 1 : ((dirY < 0) ? -1 : 0);
        int stepZ = (dirZ > 0) ? 1 : ((dirZ < 0) ? -1 : 0);

        double tDeltaX = (stepX != 0) ? Math.abs(1.0 / dirX) : Double.MAX_VALUE;
        double tDeltaY = (stepY != 0) ? Math.abs(1.0 / dirY) : Double.MAX_VALUE;
        double tDeltaZ = (stepZ != 0) ? Math.abs(1.0 / dirZ) : Double.MAX_VALUE;

        double tMaxX = (stepX > 0) ? ((x + 1.0 - startX) * tDeltaX) : ((stepX < 0) ? ((startX - x) * tDeltaX) : Double.MAX_VALUE);
        double tMaxY = (stepY > 0) ? ((y + 1.0 - startY) * tDeltaY) : ((stepY < 0) ? ((startY - y) * tDeltaY) : Double.MAX_VALUE);
        double tMaxZ = (stepZ > 0) ? ((z + 1.0 - startZ) * tDeltaZ) : ((stepZ < 0) ? ((startZ - z) * tDeltaZ) : Double.MAX_VALUE);

        double t = 0.0;
        VoxelFace lastFace = VoxelFace.NONE;

        while (t <= maxDist) {
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

            if (t > maxDist) break;

            int sx = x >> 4;
            int sy = y >> 4;
            int sz = z >> 4;
            if (sx != currentSx || sy != currentSy || sz != currentSz) {
                currentSx = sx;
                currentSy = sy;
                currentSz = sz;
                currentSection = grid.getSection(sx, sy, sz);
            }

            if (currentSection != null && !currentSection.isEmpty() && currentSection.isSolid(x & 15, y & 15, z & 15)) {
                double hitX = startX + t * dirX;
                double hitY = startY + t * dirY;
                double hitZ = startZ + t * dirZ;
                short blockId = currentSection.getBlockId(x & 15, y & 15, z & 15);
                result.set(true, hitX, hitY, hitZ, x, y, z, lastFace, blockId, t);
                return true;
            }
        }
        return false;
    }

    @Test
    void testParityBetweenReferenceAndVoxelDDA() {
        SimpleVoxelGrid grid = new SimpleVoxelGrid();
        // Place some solid obstacles at various distances across multiple sections
        // Section (0,0,0) is mostly empty except a target at (10, 5, 10)
        grid.setVoxel(10, 5, 10, true, (short) 1);
        // Section (2,0,0) has a wall at X=35
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                grid.setVoxel(35, y, z, true, (short) 2);
            }
        }
        // Section (0,2,0) has a ceiling at Y=35
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                grid.setVoxel(x, 35, z, true, (short) 3);
            }
        }

        RayHitResult refResult = new RayHitResult();
        RayHitResult ddaResult = new RayHitResult();
        Random rng = new Random(12345);

        for (int i = 0; i < 1000; i++) {
            double startX = rng.nextDouble() * 10.0;
            double startY = rng.nextDouble() * 10.0;
            double startZ = rng.nextDouble() * 10.0;

            double theta = rng.nextDouble() * 2.0 * Math.PI;
            double phi = (rng.nextDouble() - 0.5) * Math.PI;
            double dirX = Math.cos(phi) * Math.cos(theta);
            double dirY = Math.sin(phi);
            double dirZ = Math.cos(phi) * Math.sin(theta);
            double maxDist = 80.0;

            boolean refHit = referenceTrace(startX, startY, startZ, dirX, dirY, dirZ, maxDist, grid, refResult);
            boolean ddaHit = VoxelDDA.trace(startX, startY, startZ, dirX, dirY, dirZ, maxDist, grid, ddaResult);

            assertEquals(refHit, ddaHit, "Ray #" + i + " hit status mismatch");
            if (refHit) {
                assertEquals(refResult.blockX, ddaResult.blockX, "Ray #" + i + " blockX mismatch");
                assertEquals(refResult.blockY, ddaResult.blockY, "Ray #" + i + " blockY mismatch");
                assertEquals(refResult.blockZ, ddaResult.blockZ, "Ray #" + i + " blockZ mismatch");
                assertEquals(refResult.face, ddaResult.face, "Ray #" + i + " face mismatch");
                assertEquals(refResult.blockId, ddaResult.blockId, "Ray #" + i + " blockId mismatch");
            }
        }
    }

    @Test
    public void testChunkMacroSteppingAcrossMultipleChunks() {
        SimpleVoxelGrid grid = new SimpleVoxelGrid();
        // Place a target wall far away at X=500 (chunk 31)
        for (int y = 0; y < 10; y++) {
            for (int z = -5; z <= 5; z++) {
                grid.setVoxel(500, y, z, true, (short) 42);
            }
        }

        RayHitResult refResult = new RayHitResult();
        RayHitResult ddaResult = new RayHitResult();

        // 1. Ray direct hit through empty sky across 31 chunks
        boolean refHit = referenceTrace(0.5, 5.0, 0.5, 1.0, 0.0, 0.0, 600.0, grid, refResult);
        boolean ddaHit = VoxelDDA.trace(0.5, 5.0, 0.5, 1.0, 0.0, 0.0, 600.0, grid, ddaResult);

        assertTrue(refHit, "Reference ray should hit wall at X=500");
        assertTrue(ddaHit, "Chunk macro-stepping DDA should hit wall at X=500");
        assertEquals(refResult.blockX, ddaResult.blockX);
        assertEquals(refResult.blockY, ddaResult.blockY);
        assertEquals(refResult.blockZ, ddaResult.blockZ);
        assertEquals(refResult.face, ddaResult.face);
        assertEquals(refResult.distance, ddaResult.distance, 1e-4);

        // 2. High sky ray that overshoots completely above terrain
        boolean refMiss = referenceTrace(0.5, 100.0, 0.5, 1.0, 0.0, 0.0, 600.0, grid, refResult);
        boolean ddaMiss = VoxelDDA.trace(0.5, 100.0, 0.5, 1.0, 0.0, 0.0, 600.0, grid, ddaResult);
        assertFalse(refMiss);
        assertFalse(ddaMiss);
    }
}
