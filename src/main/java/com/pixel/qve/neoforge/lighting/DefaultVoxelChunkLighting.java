package com.pixel.qve.neoforge.lighting;

import com.pixel.qve.neoforge.api.lighting.VoxelChunkLighting;
import net.minecraft.world.level.chunk.DataLayer;

import java.util.Objects;

/**
 * Concrete implementation of {@link VoxelChunkLighting} storing dense arrays of
 * Minecraft {@link DataLayer} nibble buffers for sky and block light.
 */
public final class DefaultVoxelChunkLighting implements VoxelChunkLighting {

    private final int chunkX;
    private final int chunkZ;
    private final int minSectionY;
    private final int maxSectionY;
    private final int minBuildHeight;
    private final int maxBuildHeight;
    private final DataLayer[] skyLayers;
    private final DataLayer[] blockLayers;

    /**
     * Constructs a DefaultVoxelChunkLighting container.
     *
     * @param chunkX      Chunk column X coordinate
     * @param chunkZ      Chunk column Z coordinate
     * @param minSectionY Minimum section Y coordinate (inclusive)
     * @param maxSectionY Maximum section Y coordinate (exclusive)
     * @param skyLayers   Array of DataLayer for sky light matching (maxSectionY - minSectionY)
     * @param blockLayers Array of DataLayer for block light matching (maxSectionY - minSectionY)
     */
    public DefaultVoxelChunkLighting(
            int chunkX,
            int chunkZ,
            int minSectionY,
            int maxSectionY,
            DataLayer[] skyLayers,
            DataLayer[] blockLayers
    ) {
        if (maxSectionY <= minSectionY) {
            throw new IllegalArgumentException("maxSectionY (" + maxSectionY + ") must be greater than minSectionY (" + minSectionY + ")");
        }
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minSectionY = minSectionY;
        this.maxSectionY = maxSectionY;
        this.minBuildHeight = minSectionY << 4;
        this.maxBuildHeight = maxSectionY << 4;

        int expectedLength = maxSectionY - minSectionY;
        this.skyLayers = Objects.requireNonNull(skyLayers, "skyLayers cannot be null");
        this.blockLayers = Objects.requireNonNull(blockLayers, "blockLayers cannot be null");

        if (skyLayers.length != expectedLength) {
            throw new IllegalArgumentException("skyLayers length (" + skyLayers.length + ") does not match section count (" + expectedLength + ")");
        }
        if (blockLayers.length != expectedLength) {
            throw new IllegalArgumentException("blockLayers length (" + blockLayers.length + ") does not match section count (" + expectedLength + ")");
        }
    }

    @Override
    public int getChunkX() {
        return chunkX;
    }

    @Override
    public int getChunkZ() {
        return chunkZ;
    }

    @Override
    public int getMinSectionY() {
        return minSectionY;
    }

    @Override
    public int getMaxSectionY() {
        return maxSectionY;
    }

    @Override
    public int getSkyLight(int localX, int worldY, int localZ) {
        if (worldY >= maxBuildHeight) {
            return 15;
        }
        if (worldY < minBuildHeight) {
            return 0;
        }
        int secY = worldY >> 4;
        int idx = secY - minSectionY;
        if (idx < 0 || idx >= skyLayers.length) {
            return 0;
        }
        DataLayer layer = skyLayers[idx];
        return (layer != null) ? layer.get(localX & 15, worldY & 15, localZ & 15) : 0;
    }

    @Override
    public int getBlockLight(int localX, int worldY, int localZ) {
        if (worldY < minBuildHeight || worldY >= maxBuildHeight) {
            return 0;
        }
        int secY = worldY >> 4;
        int idx = secY - minSectionY;
        if (idx < 0 || idx >= blockLayers.length) {
            return 0;
        }
        DataLayer layer = blockLayers[idx];
        return (layer != null) ? layer.get(localX & 15, worldY & 15, localZ & 15) : 0;
    }

    @Override
    public DataLayer getSkyDataLayer(int sectionY) {
        int idx = sectionY - minSectionY;
        if (idx < 0 || idx >= skyLayers.length) {
            return null;
        }
        return skyLayers[idx];
    }

    @Override
    public DataLayer getBlockDataLayer(int sectionY) {
        int idx = sectionY - minSectionY;
        if (idx < 0 || idx >= blockLayers.length) {
            return null;
        }
        return blockLayers[idx];
    }

    @Override
    public boolean hasSection(int sectionY) {
        int idx = sectionY - minSectionY;
        if (idx < 0 || idx >= skyLayers.length) {
            return false;
        }
        return skyLayers[idx] != null || blockLayers[idx] != null;
    }
}
