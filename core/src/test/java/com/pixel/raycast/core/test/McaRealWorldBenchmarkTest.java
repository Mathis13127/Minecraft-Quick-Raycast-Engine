package com.pixel.raycast.core.test;

import com.pixel.raycast.core.api.RayHitResult;
import com.pixel.raycast.core.mca.McaRegionReader;
import com.pixel.raycast.core.mca.McaVoxelGrid;
import com.pixel.raycast.core.traversal.VoxelDDA;
import com.pixel.raycast.core.voxel.BlockIdRegistry;
import com.pixel.raycast.core.voxel.VoxelFace;
import com.pixel.raycast.core.voxel.VoxelSection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration and performance benchmark using real Minecraft Anvil (.mca) region files.
 */
public class McaRealWorldBenchmarkTest {

    private static BlockIdRegistry registry;
    private static McaRegionReader region00;
    private static McaVoxelGrid grid;

    @BeforeAll
    static void setUp() throws IOException {
        registry = new BlockIdRegistry();
        File file = new File("src/test/resources/region/r.0.0.mca");
        if (!file.exists()) {
            file = new File("core/src/test/resources/region/r.0.0.mca");
        }
        if (!file.exists()) {
            file = new File("raycast-core/src/test/resources/region/r.0.0.mca");
        }
        assertTrue(file.exists(), "r.0.0.mca fixture must exist");

        region00 = new McaRegionReader(file.toPath(), registry);
        grid = new McaVoxelGrid(registry);
        grid.registerRegion(region00);

        // Register default shapes for slabs, stairs, panes, trapdoors
        grid.getShapeRegistry().registerDefaultVanillaShapes(registry);

        // Preload chunks (0..15, 0..15) for benchmark
        for (int cz = 0; cz < 16; cz++) {
            for (int cx = 0; cx < 16; cx++) {
                grid.loadChunk(cx, cz);
            }
        }
        System.out.println("Preloaded " + grid.getCachedSectionCount() + " real sections from r.0.0.mca");
        assertTrue(grid.getCachedSectionCount() > 0, "Voxel grid should contain preloaded sections");
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (region00 != null) {
            region00.close();
        }
    }

    @Test
    void testVerticalRaycastHitsTerrain() {
        // In superflat, terrain surface is at Y = -60 (grass block)
        RayHitResult result = new RayHitResult();
        boolean hit = VoxelDDA.trace(8.5, 50.0, 8.5, 0.0, -1.0, 0.0, 200.0, grid, result);

        assertTrue(hit, "Downward ray must strike the terrain");
        assertEquals(8, result.blockX);
        assertEquals(8, result.blockZ);
        assertEquals(VoxelFace.UP, result.face, "Downward ray must strike top face (UP)");
        assertTrue(result.blockId > 0, "Hit block ID must be non-zero");

        String hitBlockName = registry.getName(result.blockId);
        System.out.println("Downward ray struck: " + hitBlockName + " at block Y=" + result.blockY + " (distance=" + result.distance + "m)");
        assertTrue("minecraft:grass_block".equals(hitBlockName) || "minecraft:dirt".equals(hitBlockName) || hitBlockName.startsWith("minecraft:"),
            "Expected terrain block, got: " + hitBlockName);
    }

    @Test
    void testHorizontalSkyMiss() {
        // High altitude ray over the superflat world should not hit anything
        RayHitResult result = new RayHitResult();
        boolean hit = VoxelDDA.trace(0.0, 200.0, 0.0, 1.0, 0.0, 0.0, 500.0, grid, result);

        assertFalse(hit, "Horizontal sky ray must miss");
        assertEquals(Double.MAX_VALUE, result.distance);
    }

    @Test
    void testRealWorldBenchmarkThroughput() {
        final int WARMUP_RAYS = 50_000;
        final int BENCHMARK_RAYS = 200_000;
        RayHitResult result = new RayHitResult();
        Random rng = new Random(42);

        // Pre-generate ray origins and directions across loaded chunks (world coordinates 0..255)
        double[] originsX = new double[BENCHMARK_RAYS];
        double[] originsY = new double[BENCHMARK_RAYS];
        double[] originsZ = new double[BENCHMARK_RAYS];
        double[] dirsX = new double[BENCHMARK_RAYS];
        double[] dirsY = new double[BENCHMARK_RAYS];
        double[] dirsZ = new double[BENCHMARK_RAYS];

        for (int i = 0; i < BENCHMARK_RAYS; i++) {
            originsX[i] = rng.nextDouble() * 250.0 + 2.0;
            originsY[i] = rng.nextDouble() * 150.0 - 50.0; // Y from -50 to 100
            originsZ[i] = rng.nextDouble() * 250.0 + 2.0;

            // Random direction on unit sphere
            double theta = rng.nextDouble() * 2.0 * Math.PI;
            double phi = (rng.nextDouble() - 0.5) * Math.PI;
            dirsX[i] = Math.cos(phi) * Math.cos(theta);
            dirsY[i] = Math.sin(phi);
            dirsZ[i] = Math.cos(phi) * Math.sin(theta);
        }

        // 1. JIT Warmup
        for (int i = 0; i < WARMUP_RAYS; i++) {
            VoxelDDA.trace(originsX[i], originsY[i], originsZ[i], dirsX[i], dirsY[i], dirsZ[i], 128.0, grid, result);
        }

        // 2. Timed Benchmark Run
        long hits = 0;
        long misses = 0;
        long startNanos = System.nanoTime();

        for (int i = 0; i < BENCHMARK_RAYS; i++) {
            if (VoxelDDA.trace(originsX[i], originsY[i], originsZ[i], dirsX[i], dirsY[i], dirsZ[i], 128.0, grid, result)) {
                hits++;
            } else {
                misses++;
            }
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double raysPerSecond = BENCHMARK_RAYS / elapsedSeconds;
        double nanosPerRay = (double) elapsedNanos / BENCHMARK_RAYS;

        System.out.printf("[REAL-WORLD BENCHMARK] Executed %d rays across real .mca terrain in %.3f s%n", BENCHMARK_RAYS, elapsedSeconds);
        System.out.printf("[REAL-WORLD BENCHMARK] Hits: %d, Misses: %d%n", hits, misses);
        System.out.printf("[REAL-WORLD BENCHMARK] Throughput: %,.0f rays/second (%.1f ns/ray)%n", raysPerSecond, nanosPerRay);

        // Strict CI performance gate: must achieve at least 100,000 rays/sec
        assertTrue(raysPerSecond >= 100_000.0,
            "Real-world throughput regression! Expected >= 100,000 ops/s, got: " + raysPerSecond);
    }

    @Test
    void testSkyFastPassBenchmark() {
        final int BENCHMARK_RAYS = 200_000;
        RayHitResult result = new RayHitResult();
        Random rng = new Random(999);

        double[] originsX = new double[BENCHMARK_RAYS];
        double[] originsY = new double[BENCHMARK_RAYS];
        double[] originsZ = new double[BENCHMARK_RAYS];
        double[] dirsX = new double[BENCHMARK_RAYS];
        double[] dirsY = new double[BENCHMARK_RAYS];
        double[] dirsZ = new double[BENCHMARK_RAYS];

        for (int i = 0; i < BENCHMARK_RAYS; i++) {
            originsX[i] = rng.nextDouble() * 250.0 + 2.0;
            originsY[i] = rng.nextDouble() * 100.0 + 100.0; // Y from 100 to 200 (well above terrain)
            originsZ[i] = rng.nextDouble() * 250.0 + 2.0;

            double theta = rng.nextDouble() * 2.0 * Math.PI;
            double phi = (rng.nextDouble() * 0.4) - 0.2; // mostly horizontal / upward
            dirsX[i] = Math.cos(phi) * Math.cos(theta);
            dirsY[i] = Math.sin(phi);
            dirsZ[i] = Math.cos(phi) * Math.sin(theta);
        }

        // Warmup
        for (int i = 0; i < 20_000; i++) {
            VoxelDDA.trace(originsX[i], originsY[i], originsZ[i], dirsX[i], dirsY[i], dirsZ[i], 256.0, grid, result);
        }

        long start = System.nanoTime();
        long misses = 0;
        for (int i = 0; i < BENCHMARK_RAYS; i++) {
            if (!VoxelDDA.trace(originsX[i], originsY[i], originsZ[i], dirsX[i], dirsY[i], dirsZ[i], 256.0, grid, result)) {
                misses++;
            }
        }
        long elapsed = System.nanoTime() - start;
        double secs = elapsed / 1_000_000_000.0;
        double opsPerSec = BENCHMARK_RAYS / secs;
        double nsPerRay = (double) elapsed / BENCHMARK_RAYS;

        System.out.printf("[SCENARIO A: SKY FAST-PASS] Executed %d pure sky rays in %.3f s%n", BENCHMARK_RAYS, secs);
        System.out.printf("[SCENARIO A: SKY FAST-PASS] Misses: %d / %d%n", misses, BENCHMARK_RAYS);
        System.out.printf("[SCENARIO A: SKY FAST-PASS] Throughput: %,.0f rays/second (%.1f ns/ray)%n", opsPerSec, nsPerRay);

        // Scenario A target is > 1,500,000 ops/s
        assertTrue(opsPerSec >= 1_500_000.0, "Sky Fast-Pass target > 1,500,000 ops/s, got: " + opsPerSec);
    }
}
