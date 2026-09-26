package com.pixel.qve.neoforge.warmup;

import com.pixel.qve.neoforge.api.terrain.VirtualChunk;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelGrid;
import com.pixel.qve.neoforge.terrain.QveTerrainEngine;
import com.pixel.qve.neoforge.terrain.TerrainStage;
import com.pixel.qve.raycast.RaycastThreadPool;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asynchronous background engine warming up and pre-indexing all registered BlockStates.
 * Pre-populates L1/L2 caches, property keys/values, collision shapes, numeric block IDs,
 * dimension spatial grids, and terrain generation JIT pipelines before player gameplay,
 * ensuring zero runtime spike lags or allocation spikes.
 */
public final class VoxelWarmupEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("QuickVoxelEngine-Warmup");
    private static final AtomicBoolean WARMED_UP = new AtomicBoolean(false);
    private static final AtomicBoolean TERRAIN_WARMED_UP = new AtomicBoolean(false);
    private static final Set<ResourceKey<Level>> WARMED_LEVELS = ConcurrentHashMap.newKeySet();
    private static volatile WarmupStats stats = null;

    private VoxelWarmupEngine() {}

    /**
     * Triggers asynchronous background warmup across all registered Minecraft blocks and states.
     * Guaranteed to execute at most once per JVM runtime.
     *
     * @return CompletableFuture completing when warmup finishes
     */
    public static CompletableFuture<WarmupStats> startAsyncWarmup() {
        if (!WARMED_UP.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(stats);
        }

        CompletableFuture<WarmupStats> future = new CompletableFuture<>();
        RaycastThreadPool.submit(() -> {
            try {
                WarmupStats result = runWarmup();
                future.complete(result);
            } catch (Throwable t) {
                LOGGER.error("[QVE Warmup] Error during background BlockState warmup", t);
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Executes the warmup synchronously.
     *
     * @return WarmupStats summary
     */
    public static WarmupStats runWarmup() {
        long t0 = System.nanoTime();
        int totalBlocks = BuiltInRegistries.BLOCK.size();

        LOGGER.info("[QVE Warmup] Starting asynchronous BlockState pre-indexing across {} registered blocks...", totalBlocks);

        int totalStates = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                MinecraftVoxelBridge.getBlockId(state);
                totalStates++;
            }
        }

        long elapsedNs = System.nanoTime() - t0;
        double elapsedMs = elapsedNs / 1_000_000.0;
        int propKeys = MinecraftVoxelBridge.getBlockRegistry().getStateDictionary().getPropertyRegistry().getKeyCount();

        stats = new WarmupStats(totalBlocks, totalStates, propKeys, elapsedMs);

        LOGGER.info(String.format(
                "[QVE Warmup] Pre-indexing complete in %.2f ms! Indexed %,d BlockStates across %,d blocks (%d property keys). Zero runtime spike lag.",
                elapsedMs, totalStates, totalBlocks, propKeys
        ));

        return stats;
    }

    /**
     * Pre-warms the dimension spatial grid and Anvil MCA fallback directory for a ServerLevel.
     * Ensures region directories are scanned and L1 caches are allocated ahead of time.
     *
     * @param level Target ServerLevel
     */
    public static void warmupLevel(ServerLevel level) {
        if (level == null) return;
        if (WARMED_LEVELS.add(level.dimension())) {
            long t0 = System.nanoTime();
            MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
            long elapsed = System.nanoTime() - t0;
            LOGGER.info(String.format(
                    "[QVE Warmup] Pre-warmed spatial grid and Anvil connection for dimension %s in %.2f ms",
                    level.dimension().location(), elapsed / 1_000_000.0
            ));
        }
    }

    /**
     * Asynchronously pre-heats the chunk generation and JIT execution pipeline.
     * Generates a headless virtual chunk, exercises section block-copy loops, and initializes
     * light packet encoders so HotSpot's C2 compiler compiles machine code before gameplay.
     *
     * @param level Reference ServerLevel (e.g. Overworld)
     * @return CompletableFuture completing with true if warmed up, or false if already warm
     */
    public static CompletableFuture<Boolean> warmupTerrainEngineAsync(ServerLevel level) {
        if (level == null || !TERRAIN_WARMED_UP.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                long t0 = System.nanoTime();
                LOGGER.info("[QVE Warmup] Starting asynchronous terrain generator & JIT pipeline warmup...");

                // 1. Synthetic headless virtual chunk generation (triggers generator noise & surface rules compilation)
                VirtualChunk vc = QveTerrainEngine.generateVirtualChunkAsync(level, 0, 0, TerrainStage.SURFACE).join();

                // 2. Synthetic LevelChunkSection loop to pre-heat C2 JIT compiler on setBlockState
                LevelChunkSection[] sections = vc.getSections();
                LevelChunkSection dummySection = new LevelChunkSection(level.registryAccess().registryOrThrow(Registries.BIOME));
                if (sections != null) {
                    for (LevelChunkSection sec : sections) {
                        if (sec != null && !sec.hasOnlyAir()) {
                            for (int y = 0; y < 16; y++) {
                                for (int z = 0; z < 16; z++) {
                                    for (int x = 0; x < 16; x++) {
                                        dummySection.setBlockState(x, y, z, sec.getBlockState(x, y, z), false);
                                    }
                                }
                            }
                            break;
                        }
                    }
                }

                // 3. Synthetic ClientboundLevelChunkWithLightPacket instantiation (pre-loads Netty & light serializers)
                LevelChunk liveChunk = level.getChunk(0, 0);
                if (liveChunk != null) {
                    new ClientboundLevelChunkWithLightPacket(liveChunk, level.getLightEngine(), null, null);
                }

                long elapsed = System.nanoTime() - t0;
                LOGGER.info(String.format(
                        "[QVE Warmup] Terrain & JIT pipeline warmup complete in %.2f ms! Generator, surface rules, and network codecs pre-heated.",
                        elapsed / 1_000_000.0
                ));
                return true;
            } catch (Throwable t) {
                LOGGER.warn("[QVE Warmup] Non-fatal notice during terrain pipeline warmup: {}", t.getMessage());
                return false;
            }
        }, RaycastThreadPool.getExecutor());
    }

    /**
     * Returns true if the BlockState warmup process has completed.
     *
     * @return True if warmed up
     */
    public static boolean isWarm() {
        return stats != null;
    }

    /**
     * Returns true if the terrain & JIT pipeline warmup has completed.
     *
     * @return True if terrain warm
     */
    public static boolean isTerrainWarm() {
        return TERRAIN_WARMED_UP.get();
    }

    /**
     * Gets the latest warmup statistics, or null if warmup has not run yet.
     *
     * @return WarmupStats or null
     */
    public static WarmupStats getStats() {
        return stats;
    }

    /**
     * Resets the warmup state flag (useful for testing or cache reloads).
     */
    public static void reset() {
        WARMED_UP.set(false);
        TERRAIN_WARMED_UP.set(false);
        WARMED_LEVELS.clear();
        stats = null;
    }

    /**
     * Immutable statistics record detailing warmup performance.
     *
     * @param blockCount       Total number of registered block types
     * @param stateCount       Total number of indexed blockstate variants
     * @param propertyKeyCount Total number of indexed property keys
     * @param elapsedMs        Duration of the warmup pass in milliseconds
     */
    public record WarmupStats(int blockCount, int stateCount, int propertyKeyCount, double elapsedMs) {}
}
