package com.pixel.qve.mca;

import com.pixel.qve.api.nbt.INbtService;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * On-demand NBT fetcher that retrieves block entity (tile entity) data directly from
 * offline Anvil MCA region files on disk. Zero NBT metadata is retained in the voxel cache.
 */
public final class OnDemandNbtFetcher implements INbtService {

    private final BiFunction<Integer, Integer, McaRegionReader> regionProvider;

    /**
     * Constructs an OnDemandNbtFetcher backed by a region provider.
     *
     * @param regionProvider Function resolving (regionX, regionZ) to an active or opened McaRegionReader
     */
    public OnDemandNbtFetcher(BiFunction<Integer, Integer, McaRegionReader> regionProvider) {
        this.regionProvider = Objects.requireNonNull(regionProvider, "RegionProvider cannot be null");
    }

    @Override
    public ByteBuffer getBlockEntityRawNbt(int worldX, int worldY, int worldZ) {
        int regionX = worldX >> 9;
        int regionZ = worldZ >> 9;

        McaRegionReader reader = regionProvider.apply(regionX, regionZ);
        if (reader == null) {
            return null;
        }

        int chunkX = worldX >> 4;
        int chunkZ = worldZ >> 4;
        int localChunkX = chunkX & 31;
        int localChunkZ = chunkZ & 31;

        return reader.readBlockEntityCompound(localChunkX, localChunkZ, worldX, worldY, worldZ);
    }

    @Override
    public Map<String, Object> getBlockEntityData(int worldX, int worldY, int worldZ) {
        ByteBuffer raw = getBlockEntityRawNbt(worldX, worldY, worldZ);
        if (raw == null) {
            return null;
        }
        return FastNbtReader.parseCompound(raw);
    }
}
