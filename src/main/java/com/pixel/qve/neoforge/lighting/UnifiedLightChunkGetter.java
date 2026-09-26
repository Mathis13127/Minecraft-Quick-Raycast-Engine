package com.pixel.qve.neoforge.lighting;

import com.pixel.qve.neoforge.bridge.MinecraftVoxelGrid;
import com.pixel.qve.world.VoxelChunkColumn;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance {@link LightChunkGetter} orchestrating a 3x3 chunk window for
 * {@link net.minecraft.world.level.lighting.LevelLightEngine}.
 * <p>
 * Leverages QVE's Unified Mode ({@link MinecraftVoxelGrid}) to transparently resolve neighbor
 * chunks from live RAM, L1 cache, or offline MCA disk without virtual chunk generation.
 * </p>
 */
public final class UnifiedLightChunkGetter implements LightChunkGetter {

    private final MinecraftVoxelGrid grid;
    private final int targetX;
    private final int targetZ;
    private final int minSectionY;
    private final int maxSectionY;
    private final LightChunk[][] chunkCache = new LightChunk[3][3];
    private final LightChunk emptyChunk;
    private final Map<Long, LightChunk> extendedCache = new ConcurrentHashMap<>();
    private final BlockGetter compositeLevel;

    /**
     * Constructs a UnifiedLightChunkGetter.
     *
     * @param level        Minecraft Level context (nullable for unit tests)
     * @param grid         MinecraftVoxelGrid instance (nullable for unit tests)
     * @param targetColumn Central target chunk column to illuminate
     */
    public UnifiedLightChunkGetter(Level level, MinecraftVoxelGrid grid, VoxelChunkColumn targetColumn) {
        Objects.requireNonNull(targetColumn, "targetColumn cannot be null");
        this.grid = grid;
        this.targetX = targetColumn.getChunkX();
        this.targetZ = targetColumn.getChunkZ();
        this.minSectionY = targetColumn.getMinSectionY();
        this.maxSectionY = targetColumn.getMaxSectionY();
        this.emptyChunk = new VoxelLightChunkAdapter.EmptyLightChunk(minSectionY, maxSectionY);

        // Pre-populate 3x3 neighborhood
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = targetX + dx;
                int cz = targetZ + dz;
                if (dx == 0 && dz == 0) {
                    chunkCache[dx + 1][dz + 1] = new VoxelLightChunkAdapter(targetColumn);
                } else if (grid != null) {
                    VoxelChunkColumn neighbor = grid.getColumn(cx, cz);
                    chunkCache[dx + 1][dz + 1] = (neighbor != null)
                            ? new VoxelLightChunkAdapter(neighbor)
                            : emptyChunk;
                } else {
                    chunkCache[dx + 1][dz + 1] = emptyChunk;
                }
            }
        }

        this.compositeLevel = new BlockGetter() {
            @Override
            public BlockState getBlockState(BlockPos pos) {
                LightChunk chunk = getChunkForLighting(pos.getX() >> 4, pos.getZ() >> 4);
                return (chunk != null) ? chunk.getBlockState(pos) : Blocks.AIR.defaultBlockState();
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                LightChunk chunk = getChunkForLighting(pos.getX() >> 4, pos.getZ() >> 4);
                return (chunk != null) ? chunk.getFluidState(pos) : Fluids.EMPTY.defaultFluidState();
            }

            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public int getMinBuildHeight() {
                return minSectionY << 4;
            }

            @Override
            public int getHeight() {
                return (maxSectionY - minSectionY) << 4;
            }
        };
    }

    /**
     * Manually overrides a neighbor chunk in the 3x3 grid (useful for testing neighbor propagation).
     *
     * @param column Neighbor VoxelChunkColumn
     */
    public void setNeighborColumn(VoxelChunkColumn column) {
        if (column == null) return;
        int dx = column.getChunkX() - targetX;
        int dz = column.getChunkZ() - targetZ;
        if (dx >= -1 && dx <= 1 && dz >= -1 && dz <= 1) {
            chunkCache[dx + 1][dz + 1] = new VoxelLightChunkAdapter(column);
        }
    }

    @Override
    public LightChunk getChunkForLighting(int chunkX, int chunkZ) {
        int dx = chunkX - targetX;
        int dz = chunkZ - targetZ;
        if (dx >= -1 && dx <= 1 && dz >= -1 && dz <= 1) {
            return chunkCache[dx + 1][dz + 1];
        }

        long key = (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
        return extendedCache.computeIfAbsent(key, k -> {
            if (grid != null) {
                VoxelChunkColumn col = grid.getColumn(chunkX, chunkZ);
                if (col != null) {
                    return new VoxelLightChunkAdapter(col);
                }
            }
            return emptyChunk;
        });
    }

    @Override
    public void onLightUpdate(LightLayer layer, SectionPos pos) {
        // Headless listener: no immediate network sync needed during bulk computation
    }

    @Override
    public BlockGetter getLevel() {
        return compositeLevel;
    }

    public int getTargetX() {
        return targetX;
    }

    public int getTargetZ() {
        return targetZ;
    }

    public int getMinSectionY() {
        return minSectionY;
    }

    public int getMaxSectionY() {
        return maxSectionY;
    }
}
