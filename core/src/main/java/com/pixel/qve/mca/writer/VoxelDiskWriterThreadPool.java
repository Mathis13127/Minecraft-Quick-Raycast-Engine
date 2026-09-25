package com.pixel.qve.mca.writer;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dedicated bounded thread pool for asynchronous Anvil MCA disk writing.
 * Isolates chunk compression, sector allocation, and file I/O from Minecraft server tick threads.
 */
public final class VoxelDiskWriterThreadPool {

    private static final int DEFAULT_THREADS = Math.max(2, Math.min(16, Runtime.getRuntime().availableProcessors()));
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);

    private static volatile ExecutorService EXECUTOR;

    private VoxelDiskWriterThreadPool() {}

    /**
     * Retrieves or initializes the shared disk writer executor service.
     *
     * @return ExecutorService instance
     */
    public static synchronized ExecutorService getExecutor() {
        if (EXECUTOR == null || EXECUTOR.isShutdown()) {
            EXECUTOR = new ThreadPoolExecutor(
                    DEFAULT_THREADS,
                    DEFAULT_THREADS,
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(65536),
                    r -> {
                        Thread t = new Thread(r, "QVE-DiskWriter-" + THREAD_COUNTER.getAndIncrement());
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY - 1);
                        return t;
                    },
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );
        }
        return EXECUTOR;
    }

    /**
     * Submits an asynchronous task to the disk writer pool.
     *
     * @param task Callable task
     * @param <T>  Return type
     * @return CompletableFuture completing when task finishes
     */
    public static <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        getExecutor().submit(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Submits an asynchronous runnable task to the disk writer pool.
     *
     * @param task Runnable task
     * @return CompletableFuture completing when task finishes
     */
    public static CompletableFuture<Void> runAsync(Runnable task) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        getExecutor().submit(() -> {
            try {
                task.run();
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Checks if the disk writer thread pool is actively initialized and accepting work.
     *
     * @return True if pool is running
     */
    public static boolean isRunning() {
        return EXECUTOR != null && !EXECUTOR.isShutdown() && !EXECUTOR.isTerminated();
    }

    /**
     * Gracefully drains all pending and active disk writing tasks, waiting up to the given timeout.
     * If tasks do not terminate in time, forcibly shuts down remaining worker threads.
     *
     * @param timeout Maximum wait time
     * @param unit    Time unit
     * @return True if all tasks completed cleanly, false if timeout was reached
     */
    public static synchronized boolean drainAndShutdown(long timeout, TimeUnit unit) {
        if (EXECUTOR != null && !EXECUTOR.isShutdown()) {
            EXECUTOR.shutdown();
            boolean clean = false;
            try {
                clean = EXECUTOR.awaitTermination(timeout, unit);
                if (!clean) {
                    EXECUTOR.shutdownNow();
                }
            } catch (InterruptedException e) {
                EXECUTOR.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                EXECUTOR = null;
            }
            return clean;
        }
        return true;
    }

    /**
     * Shuts down the thread pool with default 3-second grace period.
     */
    public static synchronized void shutdown() {
        drainAndShutdown(3, TimeUnit.SECONDS);
    }
}
