package com.pixel.raycast.neoforge.bridge;

import com.pixel.raycast.core.api.IVoxelGrid;
import com.pixel.raycast.core.cache.VoxelCache;
import com.pixel.raycast.core.cache.VoxelChunkColumn;
import com.pixel.raycast.core.shape.ShapeRegistry;
import com.pixel.raycast.core.voxel.Heightmap2D;
import com.pixel.raycast.core.voxel.VoxelSection;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Objects;

/**
 * Live Minecraft IVoxelGrid implementation backed by VoxelCache and on-demand
 * section compilation. Seamlessly unites live chunks in RAM and offline chunks on disk.
 */
public final class MinecraftVoxelGrid implements IVoxelGrid {

    private final Level level;
    private final VoxelCache cache;

    /**
     * Constructs a MinecraftVoxelGrid binding a live Level to a VoxelCache.
     *
     * @param level Live Minecraft level
     * @param cache Backing spatial cache
     */
    public MinecraftVoxelGrid(Level level, VoxelCache cache) {
        this.level = Objects.requireNonNull(level, "Level cannot be null");
        this.cache = Objects.requireNonNull(cache, "VoxelCache cannot be null");
    }

    /**
     * Retrieves the associated live Minecraft Level.
     *
     * @return Level instance
     */
    public Level getLevel() {
        return level;
    }

    /**
     * Retrieves the backing VoxelCache.
     *
     * @return VoxelCache instance
     */
    public VoxelCache getCache() {
        return cache;
    }

    /**
     * Retrieves a LevelChunk without risking server thread deadlocks when called
     * from background raycast worker threads.
     */
    private LevelChunk getChunkSafe(int chunkX, int chunkZ) {
        if (level instanceof ServerLevel serverLevel) {
            ServerChunkCache scc = serverLevel.getChunkSource();
            if (Thread.currentThread() == serverLevel.getServer().getRunningThread()) {
                ChunkAccess ca = scc.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (ca instanceof LevelChunk lc) {
                    return lc;
                }
            } else {
                // Background worker thread: NEVER call scc.getChunk() which dispatches to mainThreadProcessor
                // and deadlocks if the main server thread is waiting on latch.await()!
                return scc.getChunkNow(chunkX, chunkZ);
            }
        } else {
            ChunkAccess ca = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            if (ca instanceof LevelChunk lc) {
                return lc;
            }
        }
        return null;
    }

    @Override
    public VoxelSection getSection(int sectionX, int sectionY, int sectionZ) {
        // 1. Check in-memory VoxelCache columns first (lock-free)
        VoxelChunkColumn column = cache.getColumn(sectionX, sectionZ);
        if (column != null) {
            VoxelSection section = column.getSection(sectionY);
            if (section != null) {
                return section;
            }
        }

        // 2. Check if chunk is loaded in live Minecraft memory (RAM)
        LevelChunk chunk = getChunkSafe(sectionX, sectionZ);
        if (chunk != null) {
            int blockY = sectionY << 4;
            if (!level.isOutsideBuildHeight(blockY)) {
                int secIdx = chunk.getSectionIndex(blockY);
                LevelChunkSection[] sections = chunk.getSections();
                if (secIdx >= 0 && secIdx < sections.length) {
                    LevelChunkSection vanillaSection = sections[secIdx];
                    VoxelChunkColumn col = cache.getOrCreateColumn(sectionX, sectionZ);
                    return MinecraftVoxelBridge.compileSection(vanillaSection, col, sectionY);
                }
            }
        }

        // 3. Fallback to offline disk provider (MCA region reader) only if not loaded in live RAM
        IVoxelGrid diskFallback = cache.getDiskFallback();
        if (diskFallback != null) {
            VoxelSection diskSection = diskFallback.getSection(sectionX, sectionY, sectionZ);
            if (diskSection != null) {
                cache.putSection(sectionX, sectionY, sectionZ, diskSection);
                return diskSection;
            }
        }

        return null;
    }

    @Override
    public VoxelChunkColumn getColumn(int chunkX, int chunkZ) {
        VoxelChunkColumn col = cache.getColumn(chunkX, chunkZ);
        if (col != null) {
            return col;
        }

        LevelChunk chunk = getChunkSafe(chunkX, chunkZ);
        if (chunk != null) {
            return cache.getOrCreateColumn(chunkX, chunkZ);
        }

        IVoxelGrid diskFallback = cache.getDiskFallback();
        if (diskFallback != null) {
            return diskFallback.getColumn(chunkX, chunkZ);
        }

        return null;
    }

    @Override
    public Heightmap2D getHeightmap(int chunkX, int chunkZ) {
        Heightmap2D hm = cache.getHeightmap(chunkX, chunkZ);
        if (hm != null && hm.getHighestY() != Heightmap2D.VOID_Y) {
            return hm;
        }

        LevelChunk chunk = getChunkSafe(chunkX, chunkZ);
        if (chunk != null) {
            VoxelChunkColumn col = cache.getOrCreateColumn(chunkX, chunkZ);
            Heightmap2D colHm = col.getHeightmap();
            if (colHm.getHighestY() == Heightmap2D.VOID_Y) {
                // Populate true heightmap using Minecraft's native LevelChunk motion-blocking heightmap
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int h = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                        if (h > level.getMinBuildHeight()) {
                            colHm.setHeight(x, z, (short) (h - 1));
                        } else {
                            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                                    (chunkX << 4) | x, level.getMinBuildHeight(), (chunkZ << 4) | z);
                            if (!chunk.getBlockState(pos).isAir()) {
                                colHm.setHeight(x, z, (short) level.getMinBuildHeight());
                            } else {
                                colHm.setHeight(x, z, Heightmap2D.VOID_Y);
                            }
                        }
                    }
                }
            }
            return colHm;
        }

        IVoxelGrid diskFallback = cache.getDiskFallback();
        if (diskFallback != null) {
            return diskFallback.getHeightmap(chunkX, chunkZ);
        }

        return null;
    }

    @Override
    public short getLowestWorldY() {
        return (short) level.getMinBuildHeight();
    }

    @Override
    public short getHighestWorldY() {
        return (short) (level.getMaxBuildHeight() - 1);
    }

    @Override
    public ShapeRegistry getShapeRegistry() {
        return cache.getShapeRegistry();
    }
}
