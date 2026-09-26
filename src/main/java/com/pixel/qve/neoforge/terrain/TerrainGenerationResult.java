package com.pixel.qve.neoforge.terrain;

import java.util.Objects;

/**
 * Immutable telemetry and result record produced when generating an isolated chunk via QveTerrainEngine.
 */
public record TerrainGenerationResult(
        boolean success,
        int chunkX,
        int chunkZ,
        TerrainStage stage,
        long durationNanos,
        int sectorOffset,
        int compressedBytes,
        String errorMessage
) {

    public TerrainGenerationResult {
        Objects.requireNonNull(stage, "stage cannot be null");
    }

    /**
     * Total elapsed time in milliseconds.
     */
    public double durationMs() {
        return durationNanos / 1_000_000.0;
    }

    /**
     * Creates a successful result descriptor.
     */
    public static TerrainGenerationResult success(int chunkX, int chunkZ, TerrainStage stage, long durationNanos, int sectorOffset, int compressedBytes) {
        return new TerrainGenerationResult(true, chunkX, chunkZ, stage, durationNanos, sectorOffset, compressedBytes, null);
    }

    /**
     * Creates a failed result descriptor.
     */
    public static TerrainGenerationResult failure(int chunkX, int chunkZ, TerrainStage stage, String errorMessage) {
        return new TerrainGenerationResult(false, chunkX, chunkZ, stage, 0L, 0, 0, errorMessage != null ? errorMessage : "Unknown error");
    }
}
