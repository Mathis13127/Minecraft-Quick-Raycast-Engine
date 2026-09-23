package com.pixel.raycast.core.test;

import com.pixel.raycast.core.api.IVoxelGrid;
import com.pixel.raycast.core.api.RayHitResult;
import com.pixel.raycast.core.math.Ray3f;
import com.pixel.raycast.core.math.Vector3f;
import com.pixel.raycast.core.traversal.VoxelDDA;
import com.pixel.raycast.core.voxel.VoxelFace;
import com.pixel.raycast.core.voxel.VoxelSection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VoxelDDATest {

    private static class TestVoxelGrid implements IVoxelGrid {
        private final Map<Long, VoxelSection> sections = new HashMap<>();

        private static long key(int sx, int sy, int sz) {
            return (((long) sx & 0x3FFFFF) << 42) | (((long) sy & 0xFFFFF) << 22) | ((long) sz & 0x3FFFFF);
        }

        public VoxelSection getOrCreateSection(int sx, int sy, int sz) {
            return sections.computeIfAbsent(key(sx, sy, sz), k -> new VoxelSection());
        }

        @Override
        public VoxelSection getSection(int sectionX, int sectionY, int sectionZ) {
            return sections.get(key(sectionX, sectionY, sectionZ));
        }

        public void setBlock(int x, int y, int z, boolean solid, short blockId) {
            int sx = x >> 4;
            int sy = y >> 4;
            int sz = z >> 4;
            getOrCreateSection(sx, sy, sz).setVoxel(x & 15, y & 15, z & 15, solid, blockId);
        }
    }

    @Test
    @DisplayName("VoxelSection: 512-byte Bitmask occupancy and Palette ID storage")
    void testVoxelSectionOccupancy() {
        VoxelSection section = new VoxelSection();
        assertTrue(section.isEmpty());
        assertEquals(0, section.getSolidCount());

        // Set voxels at opposite corners
        section.setVoxel(0, 0, 0, true, (short) 101);
        section.setVoxel(15, 15, 15, true, (short) 202);
        section.setVoxel(7, 8, 9, true, (short) 303);

        assertFalse(section.isEmpty());
        assertEquals(3, section.getSolidCount());

        assertTrue(section.isSolid(0, 0, 0));
        assertEquals((short) 101, section.getBlockId(0, 0, 0));

        assertTrue(section.isSolid(15, 15, 15));
        assertEquals((short) 202, section.getBlockId(15, 15, 15));

        assertTrue(section.isSolid(7, 8, 9));
        assertEquals((short) 303, section.getBlockId(7, 8, 9));

        assertFalse(section.isSolid(1, 0, 0));

        // Clear one voxel
        section.setVoxel(0, 0, 0, false, (short) 0);
        assertFalse(section.isSolid(0, 0, 0));
        assertEquals(2, section.getSolidCount());
    }

    @Test
    @DisplayName("3D DDA: Cardinal axis raycast, exact distance and face resolution")
    void testCardinalAxisRaycast() {
        TestVoxelGrid grid = new TestVoxelGrid();
        grid.setBlock(10, 5, 5, true, (short) 42);

        RayHitResult result = new RayHitResult();

        // Fire along +X from (0.5, 5.5, 5.5) towards (10, 5, 5)
        boolean hit = VoxelDDA.trace(0.5, 5.5, 5.5, 1.0, 0.0, 0.0, 50.0, grid, result);

        assertTrue(hit);
        assertEquals(10, result.blockX);
        assertEquals(5, result.blockY);
        assertEquals(5, result.blockZ);
        assertEquals(VoxelFace.WEST, result.face, "Ray traveling +X must strike the WEST face");
        assertEquals((short) 42, result.blockId);
        assertEquals(9.5, result.distance, 1e-4);
        assertEquals(10.0, result.hitX, 1e-4);
        assertEquals(5.5, result.hitY, 1e-4);
        assertEquals(5.5, result.hitZ, 1e-4);
    }

    @Test
    @DisplayName("3D DDA: Point-blank detection (origin inside solid block)")
    void testPointBlankRaycast() {
        TestVoxelGrid grid = new TestVoxelGrid();
        grid.setBlock(3, 4, 5, true, (short) 99);

        RayHitResult result = new RayHitResult();
        boolean hit = VoxelDDA.trace(3.2, 4.8, 5.5, 1.0, 0.0, 0.0, 20.0, grid, result);

        assertTrue(hit);
        assertEquals(3, result.blockX);
        assertEquals(4, result.blockY);
        assertEquals(5, result.blockZ);
        assertEquals(VoxelFace.NONE, result.face);
        assertEquals((short) 99, result.blockId);
        assertEquals(0.0, result.distance);
    }

    @Test
    @DisplayName("3D DDA: Cross-section boundary traversal across multiple 16x16 chunks")
    void testCrossSectionTraversal() {
        TestVoxelGrid grid = new TestVoxelGrid();
        // Target is in section X=4 (world X=65)
        grid.setBlock(65, 0, 0, true, (short) 77);

        RayHitResult result = new RayHitResult();
        boolean hit = VoxelDDA.trace(0.0, 0.5, 0.5, 1.0, 0.0, 0.0, 100.0, grid, result);

        assertTrue(hit);
        assertEquals(65, result.blockX);
        assertEquals(0, result.blockY);
        assertEquals(0, result.blockZ);
        assertEquals(VoxelFace.WEST, result.face);
        assertEquals((short) 77, result.blockId);
        assertEquals(65.0, result.distance, 1e-4);
    }

    @Test
    @DisplayName("3D DDA: Diagonal raycast with near-miss accuracy")
    void testDiagonalRaycastAndMiss() {
        TestVoxelGrid grid = new TestVoxelGrid();
        grid.setBlock(5, 5, 5, true, (short) 1);

        RayHitResult result = new RayHitResult();

        // Ray passing just next to (5, 5, 5) at Y=6.5
        boolean hit = VoxelDDA.trace(0.0, 6.5, 0.0, 1.0, 0.0, 1.0, 50.0, grid, result);
        assertFalse(hit);
        assertFalse(result.hit);
    }

    @Test
    @DisplayName("3D DDA: Sky culling safety when highestWorldY is uninitialized (Short.MIN_VALUE)")
    void testSkyCullingUninitializedSafety() {
        class UninitializedGrid extends TestVoxelGrid {
            @Override
            public short getHighestWorldY() {
                return Short.MIN_VALUE;
            }
        }
        UninitializedGrid grid = new UninitializedGrid();
        grid.setBlock(0, 60, 5, true, (short) 10);

        RayHitResult result = new RayHitResult();
        // Ray fired from (0.5, 70.0, 0.5) down towards (0.5, 60.0, 5.5)
        double dx = 0.0;
        double dy = -10.0;
        double dz = 5.0;
        double dist = Math.sqrt(dy * dy + dz * dz);
        boolean hit = VoxelDDA.trace(0.5, 70.0, 0.5, dx / dist, dy / dist, dz / dist, 50.0, grid, result);

        assertTrue(hit, "Ray must hit solid block even if grid.getHighestWorldY() is Short.MIN_VALUE");
        assertEquals(0, result.blockX);
        assertEquals(60, result.blockY);
        assertEquals(5, result.blockZ);
    }

    @Test
    @DisplayName("Performance Smoke Test: Minimum throughput >= 80,000 ops/s on populated voxel grid")
    void testPerformanceThroughput() {
        TestVoxelGrid grid = new TestVoxelGrid();

        // Populate a 4x4x4 section area (64x64x64 blocks) with a checkerboard of solid voxels
        for (int x = 0; x < 64; x += 4) {
            for (int z = 0; z < 64; z += 4) {
                grid.setBlock(x, 10, z, true, (short) 1);
                grid.setBlock(x, 20, z, true, (short) 2);
            }
        }

        RayHitResult result = new RayHitResult();

        // 1. Warm-up JIT C2 compiler (20,000 rays)
        for (int i = 0; i < 20_000; i++) {
            float angle = (float) (i * 0.01);
            float dx = (float) Math.cos(angle);
            float dz = (float) Math.sin(angle);
            VoxelDDA.trace(32.0, 15.0, 32.0, dx, 0.1, dz, 60.0, grid, result);
        }

        // 2. Measure 100,000 raycasts
        int iterations = 100_000;
        long startTime = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            float angle = (float) (i * 0.005);
            float dx = (float) Math.cos(angle);
            float dz = (float) Math.sin(angle);
            VoxelDDA.trace(32.0, 15.0, 32.0, dx, 0.05, dz, 60.0, grid, result);
        }

        long elapsedNanos = System.nanoTime() - startTime;
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double raysPerSecond = iterations / elapsedSeconds;

        System.out.printf("[VoxelDDA Benchmark] %d rays in %.3f s -> %,.0f rays/second (%.1f ns/ray)%n",
            iterations, elapsedSeconds, raysPerSecond, (double) elapsedNanos / iterations);

        assertTrue(raysPerSecond >= 80_000,
            String.format("Throughput regression: expected >= 80,000 rays/s, got %,.0f rays/s", raysPerSecond));
    }
}
