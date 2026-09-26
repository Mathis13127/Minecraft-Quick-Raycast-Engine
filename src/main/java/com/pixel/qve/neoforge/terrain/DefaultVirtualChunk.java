package com.pixel.qve.neoforge.terrain;

import com.pixel.qve.neoforge.api.WriteResult;
import com.pixel.qve.neoforge.api.terrain.VirtualChunk;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Standard implementation of {@link VirtualChunk} wrapping an in-memory {@link ProtoChunk}.
 */
public final class DefaultVirtualChunk implements VirtualChunk {

    private final int sourceChunkX;
    private final int sourceChunkZ;
    private final TerrainStage stage;
    private final ProtoChunk protoChunk;
    private final long generationDurationNanos;
    private final boolean fromCache;

    public DefaultVirtualChunk(int sourceChunkX, int sourceChunkZ, TerrainStage stage, ProtoChunk protoChunk) {
        this(sourceChunkX, sourceChunkZ, stage, protoChunk, 0L, false);
    }

    public DefaultVirtualChunk(int sourceChunkX, int sourceChunkZ, TerrainStage stage, ProtoChunk protoChunk,
                               long generationDurationNanos, boolean fromCache) {
        this.sourceChunkX = sourceChunkX;
        this.sourceChunkZ = sourceChunkZ;
        this.stage = Objects.requireNonNull(stage, "stage cannot be null");
        this.protoChunk = Objects.requireNonNull(protoChunk, "protoChunk cannot be null");
        this.generationDurationNanos = generationDurationNanos;
        this.fromCache = fromCache;
    }

    /**
     * Returns a copy of this virtual chunk marked as retrieved from the LRU cache.
     */
    public DefaultVirtualChunk asCachedCopy() {
        return new DefaultVirtualChunk(sourceChunkX, sourceChunkZ, stage, protoChunk, 0L, true);
    }

    @Override
    public int getSourceChunkX() {
        return sourceChunkX;
    }

    @Override
    public int getSourceChunkZ() {
        return sourceChunkZ;
    }

    @Override
    public TerrainStage getStage() {
        return stage;
    }

    @Override
    public BlockState getBlockState(int localX, int worldY, int localZ) {
        int secIndex = protoChunk.getSectionIndex(worldY);
        if (secIndex < 0 || secIndex >= protoChunk.getSections().length) {
            return Blocks.AIR.defaultBlockState();
        }
        LevelChunkSection sec = protoChunk.getSection(secIndex);
        if (sec == null || sec.hasOnlyAir()) {
            return Blocks.AIR.defaultBlockState();
        }
        return sec.getBlockState(localX & 15, worldY & 15, localZ & 15);
    }

    @Override
    public Holder<Biome> getBiome(int localX, int worldY, int localZ) {
        int secIndex = protoChunk.getSectionIndex(worldY);
        if (secIndex < 0 || secIndex >= protoChunk.getSections().length) {
            return null;
        }
        LevelChunkSection sec = protoChunk.getSection(secIndex);
        if (sec == null) {
            return null;
        }
        return sec.getNoiseBiome((localX & 15) >> 2, (worldY & 15) >> 2, (localZ & 15) >> 2);
    }

    @Override
    public LevelChunkSection[] getSections() {
        return protoChunk.getSections();
    }

    @Override
    public ProtoChunk getProtoChunk() {
        return protoChunk;
    }

    @Override
    public CompletableFuture<WriteResult> stampInto(ServerLevel level, int targetChunkX, int targetChunkZ) {
        return QveTerrainEngine.stampVirtualChunkAsync(level, this, targetChunkX, targetChunkZ);
    }

    @Override
    public long getGenerationDurationNanos() {
        return generationDurationNanos;
    }

    @Override
    public boolean isFromCache() {
        return fromCache;
    }
}
