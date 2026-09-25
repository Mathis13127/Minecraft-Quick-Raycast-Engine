package com.pixel.qve.mca.writer;

/**
 * Pure Java lifecycle listener for chunk write operations.
 * Allows core systems and benchmarks to intercept and record chunk writes without NeoForge dependencies.
 */
@FunctionalInterface
public interface IChunkWriteListener {

    /**
     * Invoked immediately after a chunk is successfully written to an MCA file.
     *
     * @param chunkX        World chunk X
     * @param chunkZ        World chunk Z
     * @param durationNanos Duration of the write operation in nanoseconds
     * @param metrics       WriteMetrics detailing sectors, size, and relocation
     */
    void onChunkWritten(int chunkX, int chunkZ, long durationNanos, McaRegionWriter.WriteMetrics metrics);
}
