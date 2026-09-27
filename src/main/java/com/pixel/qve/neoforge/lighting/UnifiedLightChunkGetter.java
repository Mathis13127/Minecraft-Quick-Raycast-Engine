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

    private static final ThreadLocal<UnifiedLightChunkGetter> POOL =
            ThreadLocal.withInitial(UnifiedLightChunkGetter::new);

    private MinecraftVoxelGrid grid;
    private int targetX;
    private int targetZ;
    private int minSectionY;
    private int maxSectionY;
    private final LightChunk[][] chunkCache = new LightChunk[3][3];
    private final VoxelLightChunkAdapter[][] localAdapters = new VoxelLightChunkAdapter[3][3];
    private LightChunk emptyChunk;
    private final Map<Long, LightChunk> extendedCache = new ConcurrentHashMap<>();
    private final BlockGetter compositeLevel;

    /**
     * Retrieves or initializes a ThreadLocal instance of UnifiedLightChunkGetter bound
     * to the specified target column and neighborhood.
     *
     * @param level        Minecraft Level context
     * @param grid         MinecraftVoxelGrid instance
     * @param targetColumn Central target chunk column to illuminate
     * @return Bound ThreadLocal UnifiedLightChunkGetter
     */
    public static UnifiedLightChunkGetter getThreadLocal(Level level, MinecraftVoxelGrid grid, VoxelChunkColumn targetColumn) {
        UnifiedLightChunkGetter getter = POOL.get();
        getter.bind(level, grid, targetColumn);
        return getter;
    }

    /**
     * Unbound constructor used for ThreadLocal pooling.
     */
    public UnifiedLightChunkGetter() {
        this.minSectionY = -4;
        this.maxSectionY = 20;
        this.emptyChunk = new VoxelLightChunkAdapter.EmptyLightChunk(-4, 20);
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                this.localAdapters[x][z] = new VoxelLightChunkAdapter(-4, 20);
                this.chunkCache[x][z] = this.localAdapters[x][z];
            }
        }
        this.compositeLevel = createCompositeLevel();
    }

    /**
     * Constructs a UnifiedLightChunkGetter.
     *
     * @param level        Minecraft Level context (nullable for unit tests)
     * @param grid         MinecraftVoxelGrid instance (nullable for unit tests)
     * @param targetColumn Central target chunk column to illuminate
     */
    public UnifiedLightChunkGetter(Level level, MinecraftVoxelGrid grid, VoxelChunkColumn targetColumn) {
        this();
        bind(level, grid, targetColumn);
    }

    /**
     * Rebinds this getter and its pre-allocated adapters to a new target column and grid context.
     *
     * @param level        Minecraft Level context
     * @param grid         MinecraftVoxelGrid instance
     * @param targetColumn Central target chunk column to illuminate
     */
    public void bind(Level level, MinecraftVoxelGrid grid, VoxelChunkColumn targetColumn) {
        Objects.requireNonNull(targetColumn, "targetColumn cannot be null");
        this.grid = grid;
        this.targetX = targetColumn.getChunkX();
        this.targetZ = targetColumn.getChunkZ();
        int newMin = targetColumn.getMinSectionY();
        int newMax = targetColumn.getMaxSectionY();
        this.extendedCache.clear();

        if (newMin != this.minSectionY || newMax != this.maxSectionY) {
            this.minSectionY = newMin;
            this.maxSectionY = newMax;
            this.emptyChunk = new VoxelLightChunkAdapter.EmptyLightChunk(minSectionY, maxSectionY);
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    this.localAdapters[x][z] = new VoxelLightChunkAdapter(minSectionY, maxSectionY);
                }
            }
        }

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = targetX + dx;
                int cz = targetZ + dz;
                VoxelLightChunkAdapter adapter = localAdapters[dx + 1][dz + 1];
                if (dx == 0 && dz == 0) {
                    adapter.bind(targetColumn);
                    chunkCache[1][1] = adapter;
                } else if (grid != null) {
                    VoxelChunkColumn neighbor = grid.getColumn(cx, cz);
                    if (neighbor != null) {
                        adapter.bind(neighbor);
                        chunkCache[dx + 1][dz + 1] = adapter;
                    } else {
                        chunkCache[dx + 1][dz + 1] = emptyChunk;
                    }
                } else {
                    chunkCache[dx + 1][dz + 1] = emptyChunk;
                }
            }
        }
    }

    /**
     * Returns true if any chunk in the active 3x3 window contains light-emitting blocks.
     *
     * @return True if at least one emitter exists in the 3x3 neighborhood
     */
    public boolean hasAnyLightEmitters() {
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                LightChunk chunk = chunkCache[x][z];
                if (chunk instanceof VoxelLightChunkAdapter adapter) {
                    if (adapter.hasLightEmitters()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private BlockGetter createCompositeLevel() {
        return new BlockGetter() {
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
            VoxelLightChunkAdapter adapter = localAdapters[dx + 1][dz + 1];
            adapter.bind(column);
            chunkCache[dx + 1][dz + 1] = adapter;
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
