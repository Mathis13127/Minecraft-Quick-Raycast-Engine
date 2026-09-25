package com.pixel.qve.neoforge.api;

import java.util.List;

/**
 * Result record for batch multi-block and multi-chunk write operations.
 * Encapsulates total submitted tasks, affected voxel counts, RAM/Disk chunk distributions,
 * total elapsed time, and per-chunk results.
 *
 * @param totalSubmitted Total chunk write tasks submitted
 * @param totalSucceeded Number of chunks successfully written
 * @param totalFailed    Number of chunks that failed
 * @param totalBlocks    Total number of individual voxels modified across all chunks
 * @param ramChunkCount  Number of chunks routed to live RAM
 * @param diskChunkCount Number of chunks routed to offline MCA disk
 * @param durationNanos  Total batch execution time in nanoseconds
 * @param results        Individual WriteResults per chunk
 */
public record BatchWriteResult(
        int totalSubmitted,
        int totalSucceeded,
        int totalFailed,
        int totalBlocks,
        int ramChunkCount,
        int diskChunkCount,
        long durationNanos,
        List<WriteResult> results
) {
    /**
     * Backward-compatible constructor for basic chunk write metrics.
     */
    public BatchWriteResult(int totalSubmitted, int totalSucceeded, int totalFailed,
                            long durationNanos, List<WriteResult> results) {
        this(totalSubmitted, totalSucceeded, totalFailed, 0, 0, 0, durationNanos, results);
    }

    /**
     * Checks if all chunks in the batch were written successfully.
     *
     * @return True if no failures occurred
     */
    public boolean isAllSuccessful() {
        return totalFailed == 0 && totalSucceeded == totalSubmitted;
    }

    /**
     * Calculates the total duration in milliseconds.
     *
     * @return Elapsed time in milliseconds
     */
    public double durationMs() {
        return durationNanos / 1_000_000.0;
    }

    /**
     * Calculates the execution throughput in voxels per second.
     *
     * @return Throughput (voxels/sec)
     */
    public double throughputBlocksPerSecond() {
        double seconds = durationNanos / 1_000_000_000.0;
        return (seconds > 0 && totalBlocks > 0) ? (totalBlocks / seconds) : 0.0;
    }
}
