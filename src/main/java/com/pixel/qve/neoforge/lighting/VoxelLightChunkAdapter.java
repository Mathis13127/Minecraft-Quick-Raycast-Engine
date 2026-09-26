package com.pixel.qve.neoforge.lighting;

import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.world.Heightmap2D;
import com.pixel.qve.world.VoxelChunkColumn;
import com.pixel.qve.world.VoxelSection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Lightweight, zero-overhead adapter presenting a QVE {@link VoxelChunkColumn} to Minecraft's
 * {@link LightChunk} interface for consumption by {@link net.minecraft.world.level.lighting.LevelLightEngine}.
 */
public final class VoxelLightChunkAdapter implements LightChunk {

    private final VoxelChunkColumn column;
    private final int chunkX;
    private final int chunkZ;
    private final int minSectionY;
    private final int maxSectionY;
    private final int minBuildHeight;
    private final int maxBuildHeight;
    private final ChunkSkyLightSources skyLightSources;

    /**
     * Constructs a VoxelLightChunkAdapter wrapping the provided VoxelChunkColumn.
     *
     * @param column VoxelChunkColumn to adapt
     */
    public VoxelLightChunkAdapter(VoxelChunkColumn column) {
        this.column = Objects.requireNonNull(column, "column cannot be null");
        this.chunkX = column.getChunkX();
        this.chunkZ = column.getChunkZ();
        this.minSectionY = column.getMinSectionY();
        this.maxSectionY = column.getMaxSectionY();
        this.minBuildHeight = minSectionY << 4;
        this.maxBuildHeight = maxSectionY << 4;

        this.skyLightSources = new ChunkSkyLightSources(this);
        initializeSkySources();
    }

    private void initializeSkySources() {
        Heightmap2D hm = column.getHeightmap();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                short topY = (hm != null) ? hm.getHeight(x, z) : Heightmap2D.VOID_Y;
                int startY = (topY != Heightmap2D.VOID_Y)
                        ? Math.min(maxBuildHeight - 1, topY + 1)
                        : (maxBuildHeight - 1);
                skyLightSources.update(this, x, startY, z);
            }
        }
    }

    @Override
    public void findBlockLightSources(BiConsumer<BlockPos, BlockState> consumer) {
        int startWorldX = chunkX << 4;
        int startWorldZ = chunkZ << 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int secY = minSectionY; secY < maxSectionY; secY++) {
            VoxelSection sec = column.getSection(secY);
            if (sec == null || sec.isEmpty()) {
                continue;
            }
            int baseWorldY = secY << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int blockId = sec.getBlockId(x, y, z);
                        if (blockId == BlockIdRegistry.AIR_ID) {
                            continue;
                        }
                        BlockState state = MinecraftVoxelBridge.getBlockState(blockId);
                        if (state != null && state.getLightEmission() > 0) {
                            pos.set(startWorldX + x, baseWorldY + y, startWorldZ + z);
                            consumer.accept(pos, state);
                        }
                    }
                }
            }
        }
    }

    @Override
    public ChunkSkyLightSources getSkyLightSources() {
        return skyLightSources;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int y = pos.getY();
        if (y < minBuildHeight || y >= maxBuildHeight) {
            return Blocks.AIR.defaultBlockState();
        }
        int secY = y >> 4;
        VoxelSection sec = column.getSection(secY);
        if (sec == null || sec.isEmpty()) {
            return Blocks.AIR.defaultBlockState();
        }
        int lx = pos.getX() & 15;
        int ly = y & 15;
        int lz = pos.getZ() & 15;
        int blockId = sec.getBlockId(lx, ly, lz);
        if (blockId == BlockIdRegistry.AIR_ID) {
            return Blocks.AIR.defaultBlockState();
        }
        BlockState state = MinecraftVoxelBridge.getBlockState(blockId);
        return (state != null) ? state : Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getMinBuildHeight() {
        return minBuildHeight;
    }

    @Override
    public int getHeight() {
        return maxBuildHeight - minBuildHeight;
    }

    /**
     * Singleton representing an empty, transparent air column for out-of-bounds or unvisited chunks.
     */
    public static final class EmptyLightChunk implements LightChunk {

        private final int minBuildHeight;
        private final int height;
        private final ChunkSkyLightSources skySources;

        public EmptyLightChunk(int minSectionY, int maxSectionY) {
            this.minBuildHeight = minSectionY << 4;
            this.height = (maxSectionY - minSectionY) << 4;
            this.skySources = new ChunkSkyLightSources(this);
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    this.skySources.update(this, x, (minSectionY << 4) + height - 1, z);
                }
            }
        }

        @Override
        public void findBlockLightSources(BiConsumer<BlockPos, BlockState> consumer) {
            // Empty chunk has zero block light sources
        }

        @Override
        public ChunkSkyLightSources getSkyLightSources() {
            return skySources;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getMinBuildHeight() {
            return minBuildHeight;
        }

        @Override
        public int getHeight() {
            return height;
        }
    }
}
