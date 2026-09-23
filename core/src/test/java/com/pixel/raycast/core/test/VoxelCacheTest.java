package com.pixel.raycast.core.test;

import com.pixel.raycast.core.api.IVoxelGrid;
import com.pixel.raycast.core.cache.VoxelCache;
import com.pixel.raycast.core.cache.VoxelChunkColumn;
import com.pixel.raycast.core.math.Ray3f;
import com.pixel.raycast.core.shape.ShapeRegistry;
import com.pixel.raycast.core.api.RayHitResult;
import com.pixel.raycast.core.traversal.VoxelDDA;
import com.pixel.raycast.core.voxel.BlockIdRegistry;
import com.pixel.raycast.core.voxel.Heightmap2D;
import com.pixel.raycast.core.voxel.VoxelSection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class VoxelCacheTest {

    @Test
    @DisplayName("VoxelChunkColumn correctly updates sections, heightmaps, and handles removal")
    void testColumnOperations() {
        VoxelChunkColumn column = new VoxelChunkColumn(0, 0, -4, 20);
        assertEquals(0, column.getChunkX());
        assertEquals(0, column.getChunkZ());
        assertEquals(24, column.getSectionCount());
        assertNull(column.getSection(0));

        // Place a block at Y=64 (sectionY = 4, localY = 0)
        column.setVoxel(5, 64, 5, true, (short) 10);
        VoxelSection section = column.getSection(4);
        assertNotNull(section);
        assertTrue(section.isSolid(5, 0, 5));
        assertEquals((short) 10, section.getBlockId(5, 0, 5));
        assertEquals((short) 64, column.getHeightmap().getHeight(5, 5));
        assertEquals((short) 64, column.getHeightmap().getHighestY());

        // Place a higher block at Y=80 (sectionY = 5, localY = 0)
        column.setVoxel(5, 80, 5, true, (short) 20);
        assertEquals((short) 80, column.getHeightmap().getHeight(5, 5));
        assertEquals((short) 80, column.getHeightmap().getHighestY());

        // Remove the higher block -> heightmap should recompute back to Y=64
        column.setVoxel(5, 80, 5, false, (short) 0);
        assertEquals((short) 64, column.getHeightmap().getHeight(5, 5));
        assertEquals((short) 64, column.getHeightmap().getHighestY());

        // Remove the Y=64 block -> heightmap should become VOID_Y
        column.setVoxel(5, 64, 5, false, (short) 0);
        assertEquals(Heightmap2D.VOID_Y, column.getHeightmap().getHeight(5, 5));
    }

    @Test
    @DisplayName("VoxelCache seamlessly falls back to disk provider on cache miss")
    void testVoxelCacheDiskFallback() {
        BlockIdRegistry registry = new BlockIdRegistry();
        short stoneId = registry.getOrRegister("minecraft:stone");

        // Create a simulated disk fallback
        IVoxelGrid diskFallback = new IVoxelGrid() {
            @Override
            public VoxelSection getSection(int sectionX, int sectionY, int sectionZ) {
                if (sectionX == 2 && sectionY == 3 && sectionZ == 4) {
                    VoxelSection sec = new VoxelSection();
                    sec.setVoxel(1, 2, 3, true, stoneId);
                    return sec;
                }
                return null;
            }
        };

        VoxelCache cache = new VoxelCache(registry, new ShapeRegistry(), diskFallback);
        assertEquals(0, cache.getCachedColumnCount());
        assertEquals(0, cache.getCachedSectionCount());

        // Querying (2, 3, 4) should fetch from disk and cache in memory
        VoxelSection cached = cache.getSection(2, 3, 4);
        assertNotNull(cached);
        assertTrue(cached.isSolid(1, 2, 3));
        assertEquals(stoneId, cached.getBlockId(1, 2, 3));

        assertEquals(1, cache.getCachedColumnCount());
        assertEquals(1, cache.getCachedSectionCount());

        // Second query hits RAM cache directly
        assertSame(cached, cache.getSection(2, 3, 4));
    }

    @Test
    @DisplayName("Lock-Free Concurrency: 8 readers raycasting while 2 writers mutate voxels")
    void testLockFreeConcurrency() throws InterruptedException {
        BlockIdRegistry registry = new BlockIdRegistry();
        short dirtId = registry.getOrRegister("minecraft:dirt");
        VoxelCache cache = new VoxelCache(registry);

        // Prepopulate some terrain
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                cache.setVoxel(x, 10, z, true, dirtId);
            }
        }

        int readerThreads = 8;
        int writerThreads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(readerThreads + writerThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger totalRays = new AtomicInteger(0);
        AtomicInteger totalMutations = new AtomicInteger(0);

        // 8 Reader threads
        for (int i = 0; i < readerThreads; i++) {
            final int id = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    RayHitResult hit = new RayHitResult();
                    Ray3f ray = new Ray3f();
                    while (running.get()) {
                        float startX = (id * 7) % 60;
                        float startZ = (id * 11) % 60;
                        ray.set(startX, 25.0f, startZ, 0.0f, -1.0f, 0.0f, 50.0f);
                        VoxelDDA.trace(ray, cache, hit);
                        totalRays.incrementAndGet();
                    }
                } catch (Exception e) {
                    fail("Reader thread encountered exception: " + e.getMessage());
                }
            });
        }

        // 2 Writer threads
        for (int i = 0; i < writerThreads; i++) {
            final int id = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    int step = 0;
                    while (running.get()) {
                        int x = (id * 13 + step) & 63;
                        int z = (id * 17 + step) & 63;
                        boolean solid = (step % 2) == 0;
                        cache.setVoxel(x, 10, z, solid, solid ? dirtId : (short) 0);
                        totalMutations.incrementAndGet();
                        step++;
                        Thread.yield();
                    }
                } catch (Exception e) {
                    fail("Writer thread encountered exception: " + e.getMessage());
                }
            });
        }

        startLatch.countDown();
        Thread.sleep(300); // Run under heavy contention for 300 ms
        running.set(false);

        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertTrue(totalRays.get() > 10_000, "Should have completed tens of thousands of rays concurrently, got: " + totalRays.get());
        assertTrue(totalMutations.get() > 100, "Should have completed mutations concurrently, got: " + totalMutations.get());
    }
}
