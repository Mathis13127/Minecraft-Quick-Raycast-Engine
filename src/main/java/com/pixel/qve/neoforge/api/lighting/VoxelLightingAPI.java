package com.pixel.qve.neoforge.api.lighting;

import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelGrid;
import com.pixel.qve.neoforge.lighting.DefaultVoxelChunkLighting;
import com.pixel.qve.neoforge.lighting.QveLightingEngine;
import com.pixel.qve.neoforge.lighting.UnifiedLightChunkGetter;
import com.pixel.qve.raycast.RaycastThreadPool;
import com.pixel.qve.world.VoxelChunkColumn;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Public facade API for calculating authentic, deterministic Minecraft block and sky illumination
 * using Mojang's official {@link net.minecraft.world.level.lighting.LevelLightEngine}.
 * <p>
 * Transparently integrates surrounding 3x3 neighbor context through QVE's Unified Mode
 * (retrieving live RAM chunks, cache, or offline MCA disk without virtual chunk generation).
 * </p>
 */
public final class VoxelLightingAPI {

    private VoxelLightingAPI() {}

    /**
     * Asynchronously computes official Minecraft block and sky lighting for a custom chunk column
     * while sampling surrounding neighbor context from the specified level via Unified Mode.
     *
     * @param level        Target Minecraft Level providing the world context
     * @param targetColumn Target VoxelChunkColumn to illuminate
     * @return CompletableFuture completing with the computed VoxelChunkLighting container
     */
    public static CompletableFuture<VoxelChunkLighting> computeLightingAsync(Level level, VoxelChunkColumn targetColumn) {
        Objects.requireNonNull(targetColumn, "targetColumn cannot be null");
        return CompletableFuture.supplyAsync(() -> computeLighting(level, targetColumn), RaycastThreadPool.getExecutor());
    }

    /**
     * Synchronously computes official Minecraft block and sky lighting for a custom chunk column
     * while sampling surrounding neighbor context from the specified level via Unified Mode.
     *
     * @param level        Target Minecraft Level providing the world context (can be null for standalone/isolated calls)
     * @param targetColumn Target VoxelChunkColumn to illuminate
     * @return Computed VoxelChunkLighting container
     */
    public static VoxelChunkLighting computeLighting(Level level, VoxelChunkColumn targetColumn) {
        Objects.requireNonNull(targetColumn, "targetColumn cannot be null");
        Object cached = targetColumn.getCachedLighting();
        if (cached instanceof VoxelChunkLighting vcl) {
            return vcl;
        }
        MinecraftVoxelGrid grid = (level != null) ? MinecraftVoxelBridge.getOrCreateGrid(level) : null;
        UnifiedLightChunkGetter getter = UnifiedLightChunkGetter.getThreadLocal(level, grid, targetColumn);
        VoxelChunkLighting result = QveLightingEngine.computeLighting(getter);
        targetColumn.setCachedLighting(result);
        return result;
    }

    /**
     * Asynchronously computes or extracts official Minecraft lighting for an existing world chunk.
     *
     * @param level  Target Minecraft Level
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return CompletableFuture completing with VoxelChunkLighting
     */
    public static CompletableFuture<VoxelChunkLighting> computeChunkLightingAsync(Level level, int chunkX, int chunkZ) {
        Objects.requireNonNull(level, "level cannot be null");
        return CompletableFuture.supplyAsync(() -> computeChunkLighting(level, chunkX, chunkZ), RaycastThreadPool.getExecutor());
    }

    /**
     * Synchronously computes or extracts official Minecraft lighting for an existing world chunk.
     *
     * @param level  Target Minecraft Level
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return Computed VoxelChunkLighting
     */
    public static VoxelChunkLighting computeChunkLighting(Level level, int chunkX, int chunkZ) {
        Objects.requireNonNull(level, "level cannot be null");
        MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
        VoxelChunkColumn targetCol = grid.getColumn(chunkX, chunkZ);

        if (targetCol == null) {
            // Unvisited chunk: return zero-allocation container with full sky daylight and zero block light
            int minSecY = level.getMinSection();
            int maxSecY = level.getMaxSection();
            int secCount = maxSecY - minSecY;
            long fullMask = (secCount >= 64) ? -1L : ((1L << secCount) - 1);
            return new DefaultVoxelChunkLighting(chunkX, chunkZ, minSecY, maxSecY, fullMask, 0L, fullMask, null, null);
        }

        return computeLighting(level, targetCol);
    }
}
