package com.pixel.qve.neoforge.lighting;

import com.pixel.qve.neoforge.api.lighting.VoxelChunkLighting;
import net.minecraft.world.level.chunk.DataLayer;

import java.util.Objects;

/**
 * High-performance, zero-allocation implementation of {@link VoxelChunkLighting}.
 * <p>
 * Replaces heavy {@link DataLayer} object arrays with 64-bit primitive bitmasks ({@code long})
 * for homogeneous sections (sky 15, sky 0, block 0) and stores compact raw {@code byte[2048]}
 * nibble buffers only for heterogeneous boundary sections.
 * </p>
 */
public final class DefaultVoxelChunkLighting implements VoxelChunkLighting {

    private final int chunkX;
    private final int chunkZ;
    private final int minSectionY;
    private final int maxSectionY;
    private final int sectionCount;
    private final int minBuildHeight;
    private final int maxBuildHeight;

    private final long skyFullMask;
    private final long skyZeroMask;
    private final long blockZeroMask;
    private final byte[][] rawSkyData;
    private final byte[][] rawBlockData;

    /**
     * Constructs a DefaultVoxelChunkLighting container using primitive 64-bit masks and compact raw byte buffers.
     *
     * @param chunkX         Chunk X coordinate
     * @param chunkZ         Chunk Z coordinate
     * @param minSectionY    Minimum section Y coordinate (inclusive)
     * @param maxSectionY    Maximum section Y coordinate (exclusive)
     * @param skyFullMask    64-bit bitmask where bit i = 1 if section i is homogeneous sky 15
     * @param skyZeroMask    64-bit bitmask where bit i = 1 if section i is homogeneous sky 0
     * @param blockZeroMask  64-bit bitmask where bit i = 1 if section i is homogeneous block 0
     * @param rawSkyData     Compact array of byte[2048] for non-homogeneous sky sections (null for homogeneous)
     * @param rawBlockData   Compact array of byte[2048] for non-homogeneous block sections (null for homogeneous)
     */
    public DefaultVoxelChunkLighting(
            int chunkX,
            int chunkZ,
            int minSectionY,
            int maxSectionY,
            long skyFullMask,
            long skyZeroMask,
            long blockZeroMask,
            byte[][] rawSkyData,
            byte[][] rawBlockData
    ) {
        if (maxSectionY <= minSectionY) {
            throw new IllegalArgumentException("maxSectionY (" + maxSectionY + ") must be greater than minSectionY (" + minSectionY + ")");
        }
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minSectionY = minSectionY;
        this.maxSectionY = maxSectionY;
        this.sectionCount = maxSectionY - minSectionY;
        this.minBuildHeight = minSectionY << 4;
        this.maxBuildHeight = maxSectionY << 4;

        this.skyFullMask = skyFullMask;
        this.skyZeroMask = skyZeroMask;
        this.blockZeroMask = blockZeroMask;
        this.rawSkyData = rawSkyData;
        this.rawBlockData = rawBlockData;
    }

    /**
     * Legacy compatibility constructor converting DataLayer arrays into compact primitive bitmasks.
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
        this.sectionCount = maxSectionY - minSectionY;
        this.minBuildHeight = minSectionY << 4;
        this.maxBuildHeight = maxSectionY << 4;

        long sFull = 0L;
        long sZero = 0L;
        long bZero = 0L;
        byte[][] rSky = new byte[sectionCount][];
        byte[][] rBlock = new byte[sectionCount][];

        for (int i = 0; i < sectionCount; i++) {
            DataLayer sky = (skyLayers != null && i < skyLayers.length) ? skyLayers[i] : null;
            byte[] skyData = (sky != null) ? sky.getData() : null;
            if (sky == null || sky.isEmpty() || sky.isDefinitelyFilledWith(0) || isUniform(skyData, (byte) 0x00)) {
                sZero |= (1L << i);
            } else if (sky.isDefinitelyFilledWith(15) || isUniform(skyData, (byte) 0xFF)) {
                sFull |= (1L << i);
            } else {
                rSky[i] = skyData.clone();
            }

            DataLayer block = (blockLayers != null && i < blockLayers.length) ? blockLayers[i] : null;
            byte[] blockData = (block != null) ? block.getData() : null;
            if (block == null || block.isEmpty() || block.isDefinitelyFilledWith(0) || isUniform(blockData, (byte) 0x00)) {
                bZero |= (1L << i);
            } else {
                rBlock[i] = blockData.clone();
            }
        }

        this.skyFullMask = sFull;
        this.skyZeroMask = sZero;
        this.blockZeroMask = bZero;
        this.rawSkyData = rSky;
        this.rawBlockData = rBlock;
    }

    /**
     * Checks if all bytes in the array match the expected uniform value.
     */
    public static boolean isUniform(byte[] data, byte value) {
        if (data == null || data.length == 0) {
            return false;
        }
        if (data[0] != value || data[data.length - 1] != value || data[data.length >> 1] != value) {
            return false;
        }
        for (int i = 1; i < data.length - 1; i++) {
            if (data[i] != value) {
                return false;
            }
        }
        return true;
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
        if (idx < 0 || idx >= sectionCount) {
            return 0;
        }

        long bit = 1L << idx;
        if ((skyFullMask & bit) != 0) {
            return 15;
        }
        if ((skyZeroMask & bit) != 0) {
            return 0;
        }

        byte[] data = (rawSkyData != null) ? rawSkyData[idx] : null;
        if (data == null) {
            return 0;
        }

        int index = ((worldY & 15) << 8) | ((localZ & 15) << 4) | (localX & 15);
        int b = data[index >> 1] & 0xFF;
        return (index & 1) == 0 ? (b & 0x0F) : (b >>> 4);
    }

    @Override
    public int getBlockLight(int localX, int worldY, int localZ) {
        if (worldY < minBuildHeight || worldY >= maxBuildHeight) {
            return 0;
        }
        int secY = worldY >> 4;
        int idx = secY - minSectionY;
        if (idx < 0 || idx >= sectionCount) {
            return 0;
        }

        long bit = 1L << idx;
        if ((blockZeroMask & bit) != 0) {
            return 0;
        }

        byte[] data = (rawBlockData != null) ? rawBlockData[idx] : null;
        if (data == null) {
            return 0;
        }

        int index = ((worldY & 15) << 8) | ((localZ & 15) << 4) | (localX & 15);
        int b = data[index >> 1] & 0xFF;
        return (index & 1) == 0 ? (b & 0x0F) : (b >>> 4);
    }

    @Override
    public DataLayer getSkyDataLayer(int sectionY) {
        int idx = sectionY - minSectionY;
        if (idx < 0 || idx >= sectionCount) {
            return null;
        }
        long bit = 1L << idx;
        if ((skyFullMask & bit) != 0) {
            return new DataLayer(15);
        }
        if ((skyZeroMask & bit) != 0) {
            return new DataLayer(0);
        }
        byte[] data = (rawSkyData != null) ? rawSkyData[idx] : null;
        return (data != null) ? new DataLayer(data.clone()) : null;
    }

    @Override
    public DataLayer getBlockDataLayer(int sectionY) {
        int idx = sectionY - minSectionY;
        if (idx < 0 || idx >= sectionCount) {
            return null;
        }
        long bit = 1L << idx;
        if ((blockZeroMask & bit) != 0) {
            return new DataLayer(0);
        }
        byte[] data = (rawBlockData != null) ? rawBlockData[idx] : null;
        return (data != null) ? new DataLayer(data.clone()) : null;
    }

    @Override
    public boolean hasSection(int sectionY) {
        int idx = sectionY - minSectionY;
        if (idx < 0 || idx >= sectionCount) {
            return false;
        }
        long bit = 1L << idx;
        return (skyFullMask & bit) != 0 || (skyZeroMask & bit) != 0
                || (rawSkyData != null && rawSkyData[idx] != null)
                || (rawBlockData != null && rawBlockData[idx] != null);
    }

    public long getSkyFullMask() {
        return skyFullMask;
    }

    public long getSkyZeroMask() {
        return skyZeroMask;
    }

    public long getBlockZeroMask() {
        return blockZeroMask;
    }

    public byte[][] getRawSkyData() {
        return rawSkyData;
    }

    public byte[][] getRawBlockData() {
        return rawBlockData;
    }
}
