package com.pixel.qve.neoforge.api.terrain;

import com.pixel.qve.neoforge.terrain.QveTerrainEngine;
import com.pixel.qve.neoforge.terrain.TerrainStage;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Public facade API for generating and querying in-memory virtual chunks from Minecraft world seed.
 */
public final class VoxelTerrainAPI {

    private VoxelTerrainAPI() {}

    /**
     * Asynchronously generates an in-memory {@link VirtualChunk} from the level's world seed
     * without writing any data to disk or modifying active chunks.
     *
     * @param level  Target ServerLevel
     * @param chunkX Source chunk X to sample from seed
     * @param chunkZ Source chunk Z to sample from seed
     * @param stage  Generation cutoff stage
     * @return CompletableFuture completing with the generated VirtualChunk
     */
    public static CompletableFuture<VirtualChunk> generateVirtualChunkAsync(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            TerrainStage stage
    ) {
        Objects.requireNonNull(level, "level cannot be null");
        Objects.requireNonNull(stage, "stage cannot be null");
        return QveTerrainEngine.generateVirtualChunkAsync(level, chunkX, chunkZ, stage);
    }
}
