package com.pixel.qve.neoforge.api;

/**
 * Result record encapsulating the execution metrics, verification state, and outcome of a write operation.
 *
 * @param status          Outcome status
 * @param chunkX          World chunk X
 * @param chunkZ          World chunk Z
 * @param durationNanos   Execution time in nanoseconds
 * @param compressedBytes Number of compressed bytes written to disk (or 0 for RAM)
 * @param sectorOffset    Allocated sector offset in .mca file (or 0 for RAM)
 * @param errorMessage    Error message if failed, or null if success
 * @param isVerified      Whether immediate post-write read-back verification succeeded
 * @param verifiedBlock   Block identifier read back from physical storage (disk or RAM)
 */
public record WriteResult(
        WriteStatus status,
        int chunkX,
        int chunkZ,
        long durationNanos,
        int compressedBytes,
        int sectorOffset,
        String errorMessage,
        boolean isVerified,
        String verifiedBlock
) {
    /**
     * Backward-compatible constructor without explicit verification data.
     */
    public WriteResult(WriteStatus status, int chunkX, int chunkZ, long durationNanos,
                       int compressedBytes, int sectorOffset, String errorMessage) {
        this(status, chunkX, chunkZ, durationNanos, compressedBytes, sectorOffset, errorMessage, false, null);
    }

    /**
     * Checks if the write completed successfully (either in RAM or on Disk).
     *
     * @return True if success
     */
    public boolean isSuccess() {
        return status == WriteStatus.SUCCESS_RAM ||
               status == WriteStatus.SUCCESS_DISK_IN_PLACE ||
               status == WriteStatus.SUCCESS_DISK_REALLOCATED ||
               status == WriteStatus.SUCCESS_DEFERRED;
    }

    /**
     * Convenient factory for successful deferred chunk mutations.
     */
    public static WriteResult successDeferred(int chunkX, int chunkZ, long durationNanos) {
        return new WriteResult(WriteStatus.SUCCESS_DEFERRED, chunkX, chunkZ, durationNanos, 0, 0, null, true, "deferred");
    }

    /**
     * Convenient factory for successful RAM writes.
     */
    public static WriteResult successRam(int chunkX, int chunkZ, long durationNanos) {
        return new WriteResult(WriteStatus.SUCCESS_RAM, chunkX, chunkZ, durationNanos, 0, 0, null, false, null);
    }

    /**
     * Convenient factory for successful disk writes.
     */
    public static WriteResult successDisk(int chunkX, int chunkZ, long durationNanos,
                                          int compressedBytes, int sectorOffset, boolean isRelocated) {
        WriteStatus s = isRelocated ? WriteStatus.SUCCESS_DISK_REALLOCATED : WriteStatus.SUCCESS_DISK_IN_PLACE;
        return new WriteResult(s, chunkX, chunkZ, durationNanos, compressedBytes, sectorOffset, null, false, null);
    }

    /**
     * Convenient factory for failure results.
     */
    public static WriteResult failure(WriteStatus status, int chunkX, int chunkZ, String message) {
        return new WriteResult(status, chunkX, chunkZ, 0L, 0, 0, message, false, null);
    }

    /**
     * Returns a copy of this result with attached read-back verification details.
     *
     * @param verified      True if read-back matches expected block
     * @param verifiedBlock Name of the verified block read back from storage
     * @return New WriteResult with verification metadata
     */
    public WriteResult withVerification(boolean verified, String verifiedBlock) {
        return new WriteResult(status, chunkX, chunkZ, durationNanos, compressedBytes, sectorOffset, errorMessage, verified, verifiedBlock);
    }
}
