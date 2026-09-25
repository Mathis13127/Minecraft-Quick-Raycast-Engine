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

        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private QveConfig() {}
}
