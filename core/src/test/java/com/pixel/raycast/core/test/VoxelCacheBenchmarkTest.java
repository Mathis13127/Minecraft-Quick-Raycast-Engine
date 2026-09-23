package com.pixel.raycast.core.test;

import com.pixel.raycast.core.api.RayHitResult;
import com.pixel.raycast.core.cache.VoxelCache;
import com.pixel.raycast.core.mca.McaRegionReader;
import com.pixel.raycast.core.mca.McaVoxelGrid;
import com.pixel.raycast.core.shape.ShapeRegistry;
import com.pixel.raycast.core.traversal.VoxelDDA;
import com.pixel.raycast.core.voxel.BlockIdRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance benchmark evaluating Milestone #5: Unified Lock-Free VoxelCache.
 * Measures raycast throughput through in-memory cached sections backed by Anvil disk fallback.
 */
public class VoxelCacheBenchmarkTest {

    private static BlockIdRegistry registry;
    private static McaRegionReader region00;
    private static McaVoxelGrid diskFallback;
    private static VoxelCache cache;

    @BeforeAll
    static void setUp() throws IOException {
        registry = new BlockIdRegistry();
        File file = new File("src/test/resources/region/r.0.0.mca");
        if (!file.exists()) {
            file = new File("raycast-core/src/test/resources/region/r.0.0.mca");
        }
        assertTrue(file.exists(), "r.0.0.mca fixture must exist");

        region00 = new McaRegionReader(file.toPath(), registry);
        diskFallback = new McaVoxelGrid(registry);
        diskFallback.registerRegion(region00);

        ShapeRegistry shapeRegistry = new ShapeRegistry();
        shapeRegistry.registerDefaultVanillaShapes(registry);

        cache = new VoxelCache(registry, shapeRegistry, diskFallback);

        // Preload chunks (0..15, 0..15) into diskFallback and cache
        for (int cz = 0; cz < 16; cz++) {
            for (int cx = 0; cx < 16; cx++) {
                diskFallback.loadChunk(cx, cz);
                // Warm up sections into VoxelCache
                for (int sy = -4; sy < 20; sy++) {
                    var sec = diskFallback.getSection(cx, sy, cz);
                    if (sec != null) {
                        cache.putSection(cx, sy, cz, sec);
                    }
                }
            }
        }
        System.out.println("VoxelCache Benchmark: Loaded " + cache.getCachedSectionCount() + " sections across " + cache.getCachedColumnCount() + " columns into VoxelCache");
        assertTrue(cache.getCachedSectionCount() > 0, "VoxelCache should contain preloaded sections");
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (region00 != null) {
            region00.close();
        }
    }

    @Test
    @DisplayName("Benchmark Milestone #5: 200,000 rays through Unified VoxelCache (Single-Thread)")
    void testVoxelCacheSingleThreadBenchmark() {
        final int WARMUP_RAYS = 50_000;
        final int BENCHMARK_RAYS = 200_000;
        RayHitResult result = new RayHitResult();
        Random rng = new Random(42);

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

            double theta = rng.nextDouble() * 2.0 * Math.PI;
            double phi = (rng.nextDouble() - 0.5) * Math.PI;
            dirsX[i] = Math.cos(phi) * Math.cos(theta);
            dirsY[i] = Math.sin(phi);
            dirsZ[i] = Math.cos(phi) * Math.sin(theta);
        }

        // JIT Warmup
        for (int i = 0; i < WARMUP_RAYS; i++) {
            VoxelDDA.trace(originsX[i], originsY[i], originsZ[i], dirsX[i], dirsY[i], dirsZ[i], 128.0, cache, result);
        }

        // Timed run
        long hits = 0;
        long misses = 0;
        long startNanos = System.nanoTime();

        for (int i = 0; i < BENCHMARK_RAYS; i++) {
            if (VoxelDDA.trace(originsX[i], originsY[i], originsZ[i], dirsX[i], dirsY[i], dirsZ[i], 128.0, cache, result)) {
                hits++;
            } else {
                misses++;
            }
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double raysPerSecond = BENCHMARK_RAYS / elapsedSeconds;
        double nanosPerRay = (double) elapsedNanos / BENCHMARK_RAYS;

        System.out.printf("[VOXEL-CACHE BENCHMARK] Executed %d rays across VoxelCache in %.3f s%n", BENCHMARK_RAYS, elapsedSeconds);
        System.out.printf("[VOXEL-CACHE BENCHMARK] Hits: %d, Misses: %d%n", hits, misses);
        System.out.printf("[VOXEL-CACHE BENCHMARK] Single-Thread Throughput: %,.0f rays/second (%.1f ns/ray)%n", raysPerSecond, nanosPerRay);

        assertTrue(raysPerSecond >= 100_000.0, "Throughput regression in VoxelCache: " + raysPerSecond);
    }

    @Test
    @DisplayName("Benchmark Milestone #5: Multi-Threaded Scalability (4 Workers)")
    void testVoxelCacheMultiThreadBenchmark() throws InterruptedException {
        final int THREADS = 4;
        final int RAYS_PER_THREAD = 100_000;
        final int TOTAL_RAYS = THREADS * RAYS_PER_THREAD;

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        AtomicLong totalHits = new AtomicLong(0);
        AtomicLong totalMisses = new AtomicLong(0);

        long startNanos = System.nanoTime();

        for (int t = 0; t < THREADS; t++) {
            final int seed = t * 1000 + 42;
            pool.submit(() -> {
                Random rng = new Random(seed);
                RayHitResult localResult = new RayHitResult();
                long hits = 0;
                long misses = 0;

                for (int i = 0; i < RAYS_PER_THREAD; i++) {
                    double ox = rng.nextDouble() * 250.0 + 2.0;
                    double oy = rng.nextDouble() * 150.0 - 50.0;
                    double oz = rng.nextDouble() * 250.0 + 2.0;

                    double theta = rng.nextDouble() * 2.0 * Math.PI;
                    double phi = (rng.nextDouble() - 0.5) * Math.PI;
                    double dx = Math.cos(phi) * Math.cos(theta);
                    double dy = Math.sin(phi);
                    double dz = Math.cos(phi) * Math.sin(theta);

                    if (VoxelDDA.trace(ox, oy, oz, dx, dy, dz, 128.0, cache, localResult)) {
                        hits++;
                    } else {
                        misses++;
                    }
                }
                totalHits.addAndGet(hits);
                totalMisses.addAndGet(misses);
            });
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long elapsedNanos = System.nanoTime() - startNanos;
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double raysPerSecond = TOTAL_RAYS / elapsedSeconds;
        double nanosPerRay = (double) elapsedNanos / TOTAL_RAYS;

        System.out.printf("[VOXEL-CACHE MULTI-THREAD (4T)] Executed %d rays in %.3f s%n", TOTAL_RAYS, elapsedSeconds);
        System.out.printf("[VOXEL-CACHE MULTI-THREAD (4T)] Hits: %d, Misses: %d%n", totalHits.get(), totalMisses.get());
        System.out.printf("[VOXEL-CACHE MULTI-THREAD (4T)] Multi-Thread Throughput: %,.0f rays/second (%.1f ns/ray)%n", raysPerSecond, nanosPerRay);

        assertTrue(raysPerSecond >= 300_000.0, "Multi-thread throughput regression: " + raysPerSecond);
    }
}
