package com.pixel.qve.core.test;

import com.pixel.qve.world.cache.UnifiedVoxelCache;
import com.pixel.qve.raycast.RaycastThreadPool;
import com.pixel.qve.raycast.VoxelDDA;
import com.pixel.qve.state.BlockIdRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.*;

public class RaycastThreadPoolTest {

    @Test
    @DisplayName("parallelFor accurately processes all indices across worker threads")
    void testParallelForCorrectness() {
        final int COUNT = 10_000;
        boolean[] visited = new boolean[COUNT];
        LongAdder sum = new LongAdder();

        RaycastThreadPool.parallelFor(0, COUNT, i -> {
            synchronized (visited) {
                visited[i] = true;
            }
            sum.add(i);
        });

        long expectedSum = (long) (COUNT - 1) * COUNT / 2;
        assertEquals(expectedSum, sum.sum());
        for (int i = 0; i < COUNT; i++) {
            assertTrue(visited[i], "Index " + i + " was not visited!");
        }
    }

    @Test
    @DisplayName("Thread-local RaycastContext maintains distinct instances per worker thread")
    void testThreadLocalContextDistinctPerThread() {
        Set<RaycastThreadPool.RaycastContext> contexts = Collections.newSetFromMap(new ConcurrentHashMap<>());
        Set<String> threadNames = Collections.newSetFromMap(new ConcurrentHashMap<>());

        RaycastThreadPool.parallelFor(0, 1000, i -> {
            RaycastThreadPool.RaycastContext ctx = RaycastThreadPool.getThreadLocalContext();
            assertNotNull(ctx);
            assertNotNull(ctx.ray);
            assertNotNull(ctx.hitResult);
            contexts.add(ctx);
            threadNames.add(Thread.currentThread().getName());
        });

        assertTrue(contexts.size() >= 2, "Expected multiple thread-local contexts, got: " + contexts.size());
        assertTrue(threadNames.stream().anyMatch(name -> name.startsWith("Phalanx-Raycast-Worker-")),
            "Expected worker threads named 'Phalanx-Raycast-Worker-*'");
    }

    @Test
    @DisplayName("Parallel DDA Raycasting on UnifiedVoxelCache via RaycastThreadPool")
    void testParallelRaycastingOnGrid() {
        BlockIdRegistry registry = new BlockIdRegistry();
        int stoneId = registry.getOrRegister("minecraft:stone");
        UnifiedVoxelCache cache = new UnifiedVoxelCache(registry);

        // Populate a floor at Y=10
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                cache.setVoxel(x, 10, z, true, stoneId);
            }
        }

        final int RAYS = 50_000;
        AtomicInteger hits = new AtomicInteger(0);

        RaycastThreadPool.parallelFor(0, RAYS, i -> {
            var ctx = RaycastThreadPool.getThreadLocalContext();
            float x = (i % 30) + 1.0f;
            float z = ((i / 30) % 30) + 1.0f;
            ctx.ray.set(x, 20.0f, z, 0.0f, -1.0f, 0.0f, 30.0f);

            if (VoxelDDA.trace(ctx.ray, cache, ctx.hitResult)) {
                hits.incrementAndGet();
                assertEquals(10, ctx.hitResult.blockY);
                assertEquals(stoneId, ctx.hitResult.blockId);
            }
        });

        assertEquals(RAYS, hits.get(), "All downward rays must strike the floor at Y=10");
    }
}
