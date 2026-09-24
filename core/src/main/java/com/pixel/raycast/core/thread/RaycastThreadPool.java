package com.pixel.raycast.core.thread;

import com.pixel.raycast.core.api.RayHitResult;
import com.pixel.raycast.core.math.Ray3f;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Dedicated high-performance worker thread pool for parallel spatial raycasts,
 * ballistic trajectory stepping, and radar occlusion calculations.
 * Features zero-allocation thread-local raycast contexts and automatic workload partitioning.
 */
public final class RaycastThreadPool {

    /** Default minimum number of rays before scheduling across worker threads (16). */
    public static final int DEFAULT_PARALLEL_THRESHOLD = 16;
    private static final int WORKER_COUNT = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);
    private static final ThreadFactory THREAD_FACTORY = r -> {
        Thread t = new Thread(r, "Phalanx-Raycast-Worker-" + THREAD_COUNTER.getAndIncrement());
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    };

    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
        WORKER_COUNT,
        WORKER_COUNT,
        60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(),
        THREAD_FACTORY
    );

    /**
     * Thread-local reusable context containing pre-allocated Ray3f and RayHitResult.
     * Guarantees 0 byte heap allocation per raycast in multi-threaded loops.
     */
    public static final class RaycastContext {
        /** Pre-allocated reusable Ray3f instance. */
        public final Ray3f ray = new Ray3f();
        /** Pre-allocated reusable RayHitResult instance. */
        public final RayHitResult hitResult = new RayHitResult();

        /**
         * Constructs a new RaycastContext.
         */
        public RaycastContext() {}
    }

    private static final ThreadLocal<RaycastContext> LOCAL_CONTEXT = ThreadLocal.withInitial(RaycastContext::new);

    private RaycastThreadPool() {}

    /**
     * Retrieves the reusable RaycastContext for the calling worker thread.
     *
     * @return Thread-local RaycastContext instance
     */
    public static RaycastContext getThreadLocalContext() {
        return LOCAL_CONTEXT.get();
    }

    /**
     * Number of background worker threads in the pool.
     *
     * @return Worker thread count
     */
    public static int getWorkerCount() {
        return WORKER_COUNT;
    }

    /**
     * Submits an asynchronous task to the raycast worker pool.
     *
     * @param task Runnable task
     * @return Future representing pending completion
     */
    public static Future<?> submit(Runnable task) {
        return EXECUTOR.submit(task);
    }

    /**
     * Submits a value-returning task to the raycast worker pool.
     *
     * @param <T>  Result type
     * @param task Callable task
     * @return Future representing pending result
     */
    public static <T> Future<T> submit(Callable<T> task) {
        return EXECUTOR.submit(task);
    }

    /**
     * Executes an indexed range in parallel across the worker pool.
     * If the range size is below the threshold, runs sequentially on the calling thread to eliminate context switches.
     *
     * @param startInclusive Start index (inclusive)
     * @param endExclusive   End index (exclusive)
     * @param action         Consumer receiving each index
     */
    public static void parallelFor(int startInclusive, int endExclusive, IntConsumer action) {
        parallelFor(startInclusive, endExclusive, DEFAULT_PARALLEL_THRESHOLD, action);
    }

    /**
     * Executes an indexed range in parallel with a custom threshold.
     *
     * @param startInclusive Start index (inclusive)
     * @param endExclusive   End index (exclusive)
     * @param threshold      Minimum item count required to trigger multi-threading
     * @param action         Consumer receiving each index
     */
    public static void parallelFor(int startInclusive, int endExclusive, int threshold, IntConsumer action) {
        Objects.requireNonNull(action, "Action cannot be null");
        int count = endExclusive - startInclusive;
        if (count <= 0) {
            return;
        }

        if (count <= threshold || WORKER_COUNT <= 1 || Thread.currentThread().getName().startsWith("Phalanx-Raycast-Worker-")) {
            for (int i = startInclusive; i < endExclusive; i++) {
                action.accept(i);
            }
            return;
        }

        int numChunks = Math.min(WORKER_COUNT, count);
        int chunkSize = (count + numChunks - 1) / numChunks;
        CountDownLatch latch = new CountDownLatch(numChunks);
        Throwable[] errorHolder = new Throwable[1];

        for (int chunk = 0; chunk < numChunks; chunk++) {
            final int chunkStart = startInclusive + chunk * chunkSize;
            final int chunkEnd = Math.min(endExclusive, chunkStart + chunkSize);

            if (chunkStart >= endExclusive) {
                latch.countDown();
                continue;
            }

            EXECUTOR.execute(() -> {
                try {
                    for (int i = chunkStart; i < chunkEnd; i++) {
                        action.accept(i);
                    }
                } catch (Throwable t) {
                    errorHolder[0] = t;
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Parallel raycast execution interrupted", e);
        }

        if (errorHolder[0] != null) {
            if (errorHolder[0] instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Exception during parallel raycast execution", errorHolder[0]);
        }
    }

    /**
     * Iterates over a list in parallel using the worker pool.
     *
     * @param <T>    Element type
     * @param list   List to process
     * @param action Consumer for each element
     */
    public static <T> void parallelForEach(List<T> list, Consumer<T> action) {
        Objects.requireNonNull(list, "List cannot be null");
        Objects.requireNonNull(action, "Action cannot be null");
        parallelFor(0, list.size(), i -> action.accept(list.get(i)));
    }

    /**
     * Shuts down the raycast worker pool.
     */
    public static void shutdown() {
        EXECUTOR.shutdown();
    }
}
