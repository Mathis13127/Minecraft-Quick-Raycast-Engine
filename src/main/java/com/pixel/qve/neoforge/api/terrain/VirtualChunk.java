package com.pixel.qve.neoforge.api.terrain;

import com.pixel.qve.neoforge.api.WriteResult;
import com.pixel.qve.neoforge.terrain.TerrainStage;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;

import java.util.concurrent.CompletableFuture;

/**
 * Immutable in-memory virtual chunk container representing natural world terrain generated from seed.
 * <p>
 * Does not touch disk storage unless explicitly stamped into a target world location.
 * </p>
 */
public interface VirtualChunk {

    /**
     * Source chunk X coordinate used to sample the seed terrain.
     */
    int getSourceChunkX();

    /**
     * Source chunk Z coordinate used to sample the seed terrain.
     */
    int getSourceChunkZ();

    /**
     * The generation cutoff stage of this virtual chunk.
     */
    TerrainStage getStage();

    /**
     * Retrieves the BlockState at the given chunk-local coordinates.
     *
     * @param localX Local X [0..15]
     * @param worldY World Y [-64..319]
     * @param localZ Local Z [0..15]
     * @return BlockState at coordinate
     */
    BlockState getBlockState(int localX, int worldY, int localZ);

    /**
     * Retrieves the Biome at the given chunk-local coordinates.
     *
     * @param localX Local X [0..15]
     * @param worldY World Y [-64..319]
     * @param localZ Local Z [0..15]
     * @return Biome holder
     */
    Holder<Biome> getBiome(int localX, int worldY, int localZ);

    /**
     * Retrieves the vertical chunk sections of this virtual chunk.
     */
    LevelChunkSection[] getSections();

    /**
     * Returns the underlying ProtoChunk instance held in memory.
     */
    ProtoChunk getProtoChunk();

    /**
     * Stamps / copies this virtual chunk buffer directly into a target world chunk.
     * <p>
     * If the target chunk is resident in RAM, sections are injected live into the LevelChunk
     * and packets are dispatched so terrain materializes instantly without reloading.
     * If unloaded on disk, writes directly into the .mca region file.
     * </p>
     *
     * @param level        Target ServerLevel
     * @param targetChunkX Destination chunk X
     * @param targetChunkZ Destination chunk Z
     * @return CompletableFuture completing with WriteResult
     */
    CompletableFuture<WriteResult> stampInto(ServerLevel level, int targetChunkX, int targetChunkZ);

    /**
     * Returns the generation duration in nanoseconds for this virtual chunk.
     * If served from cache, returns 0.
     */
    long getGenerationDurationNanos();

    /**
     * Returns true if this virtual chunk was retrieved from the in-memory LRU cache.
     */
    boolean isFromCache();
}
