package com.pixel.qve.mca.writer;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dedicated single-worker background integrity service for post-write Anvil MCA chunk verification.
 * Runs on thread "QVE-Integrity-Auditor" with minimum priority to audit written chunk sectors,
 * coordinates, and voxel contents asynchronously without stalling the high-throughput write pipeline.
 */
public final class AsyncChunkIntegrityVerifier {

    private static final System.Logger LOGGER = System.getLogger(AsyncChunkIntegrityVerifier.class.getName());

    /**
     * Descriptor of a chunk write operation requiring background integrity verification.
     */
    public record ChunkVerificationTask(
            McaWriteCoordinator coordinator,
            int chunkX,
            int chunkZ,
            McaWriteCoordinator.VoxelCheck voxelCheck,
            CompletableFuture<Boolean> completion
    ) {
        public ChunkVerificationTask {
            Objects.requireNonNull(coordinator, "coordinator cannot be null");
            Objects.requireNonNull(completion, "completion cannot be null");
        }
    }

    private static final BlockingQueue<ChunkVerificationTask> TASK_QUEUE = new LinkedBlockingQueue<>(65536);
    private static final AtomicBoolean RUNNING = new AtomicBoolean(true);
    private static volatile boolean enabled = true;
    private static final Thread WORKER_THREAD;

    static {
        WORKER_THREAD = new Thread(AsyncChunkIntegrityVerifier::runWorkerLoop, "QVE-Integrity-Auditor");
        WORKER_THREAD.setDaemon(true);
        WORKER_THREAD.setPriority(Thread.MIN_PRIORITY);
        WORKER_THREAD.start();
    }

    private AsyncChunkIntegrityVerifier() {}

    /**
     * Enqueues an asynchronous integrity verification task for a written chunk.
     * If the verifier is disabled, completes immediately with true.
     *
     * @param coordinator Target McaWriteCoordinator managing the region
     * @param chunkX      World chunk X
     * @param chunkZ      World chunk Z
     * @param voxelCheck  Optional VoxelCheck descriptor (or null)
     * @return CompletableFuture completing with true if verification succeeded, false if mismatch
     */
    public static CompletableFuture<Boolean> enqueue(McaWriteCoordinator coordinator, int chunkX, int chunkZ, McaWriteCoordinator.VoxelCheck voxelCheck) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (!enabled || !RUNNING.get()) {
            future.complete(true);
            return future;
        }

        ChunkVerificationTask task = new ChunkVerificationTask(coordinator, chunkX, chunkZ, voxelCheck, future);
        if (!TASK_QUEUE.offer(task)) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Integrity audit task queue is full (65536 items). Dropping async verification for chunk ({0}, {1}) to prevent backpressure",
                    chunkX, chunkZ);
            future.complete(true);
        }
        return future;
    }

    private static void runWorkerLoop() {
        while (RUNNING.get()) {
            try {
                ChunkVerificationTask task = TASK_QUEUE.poll(500, TimeUnit.MILLISECONDS);
                if (task != null) {
                    processTask(task);
                }
            } catch (InterruptedException e) {
                if (!RUNNING.get()) {
                    break;
                }
            } catch (Throwable t) {
                LOGGER.log(System.Logger.Level.ERROR, "Unexpected error in QVE-Integrity-Auditor worker: {0}", t.getMessage(), t);
            }
        }

        // Drain remaining tasks on shutdown
        ChunkVerificationTask remaining;
        while ((remaining = TASK_QUEUE.poll()) != null) {
            processTask(remaining);
        }
    }

    private static void processTask(ChunkVerificationTask task) {
        try {
            boolean valid = task.coordinator().verifyChunkSync(task.chunkX(), task.chunkZ(), task.voxelCheck());
            if (!valid) {
                LOGGER.log(System.Logger.Level.ERROR,
                        "ASYNC INTEGRITY AUDIT FAILURE: Physical verification mismatch in chunk ({0}, {1})!",
                        task.chunkX(), task.chunkZ());
            }
            task.completion().complete(valid);
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.ERROR,
                    "Exception during async chunk verification for ({0}, {1}): {2}",
                    task.chunkX(), task.chunkZ(), t.getMessage(), t);
            task.completion().complete(false);
        }
    }

    /**
     * Checks if async background verification is currently enabled.
     *
     * @return True if enabled
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether async background verification is enabled.
     *
     * @param isEnabled True to enable
     */
    public static void setEnabled(boolean isEnabled) {
        enabled = isEnabled;
    }

    /**
     * Gets the number of pending tasks awaiting background verification.
     *
     * @return Pending queue depth
     */
    public static int getPendingCount() {
        return TASK_QUEUE.size();
    }

    /**
     * Waits up to the specified timeout for all queued verification tasks to complete.
     *
     * @param timeout Max wait time
     * @param unit    Time unit
     * @return True if all tasks completed, false if timeout elapsed
     */
    public static boolean drainAndAwait(long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!TASK_QUEUE.isEmpty()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Signals the background auditor worker to drain and terminate.
     */
    public static void shutdown() {
        RUNNING.set(false);
        WORKER_THREAD.interrupt();
    }
}
