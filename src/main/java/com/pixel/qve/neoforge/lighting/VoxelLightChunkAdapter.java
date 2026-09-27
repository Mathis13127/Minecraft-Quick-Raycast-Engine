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
 * <p>
 * Supports zero-allocation pooling by rebinding to new columns via {@link #bind(VoxelChunkColumn)}.
 * </p>
 */
public final class VoxelLightChunkAdapter implements LightChunk {

    private VoxelChunkColumn column;
    private int chunkX;
    private int chunkZ;
    private int minSectionY;
    private int maxSectionY;
    private int minBuildHeight;
    private int maxBuildHeight;
    private ChunkSkyLightSources skyLightSources;
    private final BlockPos.MutableBlockPos scratchBlockPos = new BlockPos.MutableBlockPos();

    /**
     * Constructs an unbound VoxelLightChunkAdapter for thread-local pooling.
     *
     * @param minSectionY Minimum vertical section Y
     * @param maxSectionY Maximum vertical section Y
     */
    public VoxelLightChunkAdapter(int minSectionY, int maxSectionY) {
        this.minSectionY = minSectionY;
        this.maxSectionY = maxSectionY;
        this.minBuildHeight = minSectionY << 4;
        this.maxBuildHeight = maxSectionY << 4;
        this.skyLightSources = new ChunkSkyLightSources(this);
    }

    /**
     * Constructs a VoxelLightChunkAdapter directly wrapping the provided VoxelChunkColumn.
     *
     * @param column VoxelChunkColumn to adapt
     */
    public VoxelLightChunkAdapter(VoxelChunkColumn column) {
        this(column.getMinSectionY(), column.getMaxSectionY());
        bind(column);
    }

    /**
     * Rebinds this adapter to a new VoxelChunkColumn without allocating new bit storages or wrappers.
     *
     * @param column New VoxelChunkColumn to bind
     */
    public void bind(VoxelChunkColumn column) {
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
        if (column == null) return;
        Heightmap2D hm = column.getHeightmap();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                short topY = (hm != null) ? hm.getHeight(x, z) : Heightmap2D.VOID_Y;
                if (topY == Heightmap2D.VOID_Y) {
                    topY = findHighestSolidVoxel(x, z);
                }
                int startY = (topY != Heightmap2D.VOID_Y)
                        ? Math.min(maxBuildHeight - 1, topY + 1)
                        : (maxBuildHeight - 1);
                skyLightSources.update(this, x, startY, z);
            }
        }
    }

    private short findHighestSolidVoxel(int localX, int localZ) {
        if (column == null) return Heightmap2D.VOID_Y;
        for (int secY = maxSectionY - 1; secY >= minSectionY; secY--) {
            VoxelSection sec = column.getSection(secY);
            if (sec != null && !sec.isEmpty()) {
                for (int y = 15; y >= 0; y--) {
                    if (sec.isSolid(localX, y, localZ)) {
                        return (short) ((secY << 4) | y);
                    }
                }
            }
        }
        return Heightmap2D.VOID_Y;
    }

    /**
     * Returns true if any section in this column is known to contain light emitting blocks.
     *
     * @return True if light emitters exist
     */
    public boolean hasLightEmitters() {
        if (column == null) {
            return false;
        }
        for (int secY = minSectionY; secY < maxSectionY; secY++) {
            VoxelSection sec = column.getSection(secY);
            if (sec != null && !sec.isEmpty() && sec.hasLightEmitters()) {
                return true;
            }
        }
        return false;
    }

    public VoxelChunkColumn getColumn() {
        return column;
    }

    @Override
    public void findBlockLightSources(BiConsumer<BlockPos, BlockState> consumer) {
        if (column == null) return;
        int startWorldX = chunkX << 4;
        int startWorldZ = chunkZ << 4;
        BlockPos.MutableBlockPos pos = scratchBlockPos;

        for (int secY = minSectionY; secY < maxSectionY; secY++) {
            VoxelSection sec = column.getSection(secY);
            if (sec == null || sec.isEmpty() || !sec.hasLightEmitters()) {
                continue;
            }
            int baseWorldY = secY << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int blockId = sec.getBlockId(x, y, z);
                        if (blockId == BlockIdRegistry.AIR_ID || !MinecraftVoxelBridge.isLightEmitter(blockId)) {
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
        if (column == null) {
            return Blocks.AIR.defaultBlockState();
        }
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
