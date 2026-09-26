package com.pixel.qve.neoforge.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server and common configuration settings for the Quick Voxel Engine (QVE).
 */
public final class QveConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue RESUME_INCOMPLETE_BATCHES;
    public static final ModConfigSpec.IntValue SHUTDOWN_DRAIN_TIMEOUT_SECONDS;
    public static final ModConfigSpec.BooleanValue REGION_BATCHING_ENABLED;
    public static final ModConfigSpec.IntValue COMPRESSION_LEVEL;
    public static final ModConfigSpec.BooleanValue ASYNC_VERIFICATION_ENABLED;

    static {
        BUILDER.push("write_pipeline");

        RESUME_INCOMPLETE_BATCHES = BUILDER
                .comment("Whether to serialize incomplete voxel write batches to a temporary recovery file on server stop",
                        "and resume them automatically when the world restarts.")
                .define("resumeIncompleteBatches", true);

        SHUTDOWN_DRAIN_TIMEOUT_SECONDS = BUILDER
                .comment("Maximum seconds to wait for active disk writing threads to complete before server shutdown.")
                .defineInRange("shutdownDrainTimeoutSeconds", 3, 1, 30);

        REGION_BATCHING_ENABLED = BUILDER
                .comment("Whether to group offline MCA chunk writes by region and sync the 8KB allocation header once per region batch.")
                .define("regionBatchingEnabled", true);

        COMPRESSION_LEVEL = BUILDER
                .comment("ZLIB compression level for Anvil .mca chunk deflation (1 = BEST_SPEED, 9 = BEST_COMPRESSION).",
                        "Level 1 yields identical 4KB sector allocation on disk while accelerating compression 3.5x to 4x.")
                .defineInRange("compressionLevel", 1, 1, 9);

        ASYNC_VERIFICATION_ENABLED = BUILDER
                .comment("Whether to audit written chunk sectors asynchronously on a dedicated background thread (QVE-Integrity-Auditor)",
                        "rather than stalling the write pipeline.")
                .define("asyncVerificationEnabled", true);

        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /**
     * Applies configured values to the underlying MCA writer and verifier engines.
     */
    public static void applyConfig() {
        try {
            if (COMPRESSION_LEVEL != null) {
                com.pixel.qve.mca.writer.McaRegionWriter.setCompressionLevel(COMPRESSION_LEVEL.get());
            }
            if (ASYNC_VERIFICATION_ENABLED != null) {
                com.pixel.qve.mca.writer.AsyncChunkIntegrityVerifier.setEnabled(ASYNC_VERIFICATION_ENABLED.get());
            }
        } catch (Throwable ignored) {
        }
    }

    private QveConfig() {}
}
