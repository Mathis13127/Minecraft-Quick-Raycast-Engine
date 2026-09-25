package com.pixel.qve.core.test;

import com.pixel.qve.mca.DirectBufferCleaner;
import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.state.ShapeRegistry;
import com.pixel.qve.world.Heightmap2D;
import com.pixel.qve.world.VoxelChunkColumn;
import com.pixel.qve.world.VoxelSection;
import com.pixel.qve.world.cache.UnifiedVoxelCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class ConcurrencyAndMemorySafetyTest {

    @Test
    @DisplayName("DirectBufferCleaner frees direct buffers and handles null/heap safely")
    void testDirectBufferCleaner() {
        // Direct buffer
        ByteBuffer direct = ByteBuffer.allocateDirect(4096);
        direct.putInt(0xCAFEBABE);
        assertDoesNotThrow(() -> DirectBufferCleaner.clean(direct));

        // Null buffer
        assertDoesNotThrow(() -> DirectBufferCleaner.clean(null));

        // Heap buffer (should be a no-op, no exception)
        ByteBuffer heap = ByteBuffer.allocate(1024);
        assertDoesNotThrow(() -> DirectBufferCleaner.clean(heap));
    }

    @Test
    @DisplayName("VoxelSection atomic operations maintain consistency under high concurrency")
    void testVoxelSectionConcurrentWrites() throws InterruptedException {
        VoxelSection section = new VoxelSection();
        int threadCount = 8;
        int voxelsPerThread = 256;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicBoolean errorDetected = new AtomicBoolean(false);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // Each thread writes to its own slice: Y in [threadId * 2, threadId * 2 + 1]
                    int yStart = threadId * 2;
                    int written = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        int y = yStart + dy;
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 8; x++) {
                                int blockId = 1000 + (threadId * 256) + written;
                                section.setVoxel(x, y, z, true, blockId);
                                written++;
                            }
                        }
                    }
                } catch (Throwable e) {
                    errorDetected.set(true);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Concurrent write workers timed out");
        executor.shutdown();
        assertFalse(errorDetected.get(), "Exception occurred during concurrent VoxelSection writes");

        int expectedTotalSolid = threadCount * voxelsPerThread;
        assertEquals(expectedTotalSolid, section.getSolidCount(), "Solid count must exactly match total written voxels");

        // Verify data integrity
        for (int t = 0; t < threadCount; t++) {
            int yStart = t * 2;
            int readCount = 0;
            for (int dy = 0; dy < 2; dy++) {
                int y = yStart + dy;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 8; x++) {
                        assertTrue(section.isSolid(x, y, z), "Voxel must be solid at (" + x + "," + y + "," + z + ")");
                        int expectedId = 1000 + (t * 256) + readCount;
                        assertEquals(expectedId, section.getBlockId(x, y, z), "BlockId mismatch at (" + x + "," + y + "," + z + ")");
                        readCount++;
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Heightmap2D handles concurrent multi-threaded height updates accurately")
    void testHeightmapConcurrentUpdates() throws InterruptedException {
        Heightmap2D heightmap = new Heightmap2D();
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // Each thread writes distinct (x, z) positions
                    int xStart = threadId * 2;
                    for (int dx = 0; dx < 2; dx++) {
                        int x = xStart + dx;
                        for (int z = 0; z < 16; z++) {
                            short height = (short) (50 + threadId * 10 + z);
                            heightmap.setHeight(x, z, height);
                        }
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // The maximum height set corresponds to threadId=7, z=15 -> 50 + 70 + 15 = 135
        assertEquals((short) 135, heightmap.getHighestY());
    }

    @Test
    @DisplayName("UnifiedVoxelCache enforces bounded column capacity via LRU eviction")
    void testUnifiedVoxelCacheBoundedEviction() {
        int maxCap = 32;
        UnifiedVoxelCache cache = new UnifiedVoxelCache(
                new BlockIdRegistry(),
                new ShapeRegistry(),
                null,
                null,
                -4,
                20,
                maxCap
        );

        // Insert 128 columns
        for (int i = 0; i < 128; i++) {
            cache.getOrCreateColumn(i, i);
        }

        // Cache must not exceed bounded capacity
        assertTrue(cache.getCachedColumnCount() <= maxCap,
                "Cache size " + cache.getCachedColumnCount() + " exceeded max capacity " + maxCap);

        // Calling clear must wipe columns and eviction queue
        cache.clear();
        assertEquals(0, cache.getCachedColumnCount());

        // Calling close must be idempotent and clear state
        assertDoesNotThrow(cache::close);
        assertEquals(0, cache.getCachedColumnCount());
    }

    @Test
    @DisplayName("UnifiedVoxelCache L1 cache coordinates validation prevents cross-column read races")
    void testUnifiedVoxelCacheL1Safety() {
        UnifiedVoxelCache cache = new UnifiedVoxelCache(new BlockIdRegistry());
        VoxelChunkColumn col1 = cache.getOrCreateColumn(0, 0);
        col1.setVoxel(0, 10, 0, true, 42);

        // Fetch col1 to prime L1 cache
        VoxelChunkColumn fetched1 = cache.getColumn(0, 0);
        assertSame(col1, fetched1);

        // Fetch another column with the same or different hash
        VoxelChunkColumn col2 = cache.getOrCreateColumn(1024, 0);
        col2.setVoxel(0, 10, 0, true, 84);

        VoxelChunkColumn fetched2 = cache.getColumn(1024, 0);
        assertSame(col2, fetched2);

        // Re-fetch column 1
        VoxelChunkColumn recheck1 = cache.getColumn(0, 0);
        assertSame(col1, recheck1);
    }
}
