package com.pixel.qve.neoforge.api;

/**
 * Status enumeration indicating the precise outcome of a block or chunk write operation.
 */
public enum WriteStatus {
    /** Block was modified in active RAM on the server thread (chunk was loaded). */
    SUCCESS_RAM,

    /** Chunk was overwritten in-place within its existing MCA sectors on disk (zero fragmentation). */
    SUCCESS_DISK_IN_PLACE,

    /** Chunk was written to disk with new or relocated sectors in the MCA region file. */
    SUCCESS_DISK_REALLOCATED,

    /** Strict disk write rejected because the target chunk is currently active or loaded in RAM. */
    FAIL_CHUNK_LOADED_IN_RAM,

    /** Direct disk write was cancelled by an addon listening to ChunkPreDirectWriteEvent. */
    FAIL_CANCELLED_BY_EVENT,

    /** I/O exception occurred while writing or compressing chunk on disk. */
    FAIL_IO_ERROR,

    /** Target world coordinates or dimension are invalid. */
    FAIL_INVALID_COORDINATES,

    /** Chunk or region file does not exist on disk and creation policy forbids ex-nihilo creation. */
    FAIL_CHUNK_NOT_FOUND,

    /** Physical disk read-back verification failed to match expected block state. */
    FAIL_VERIFICATION_MISMATCH
}
