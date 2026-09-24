package com.pixel.raycast.neoforge.bridge;

import com.pixel.raycast.core.api.IVoxelGrid;
import com.pixel.raycast.core.cache.VoxelCache;
import com.pixel.raycast.core.cache.VoxelChunkColumn;
import com.pixel.raycast.core.shape.ShapeRegistry;
import com.pixel.raycast.core.voxel.Heightmap2D;
import com.pixel.raycast.core.voxel.VoxelSection;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

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
        LevelChunk chunk = null;
        ChunkAccess ca = level.getChunk(sectionX, sectionZ, ChunkStatus.FULL, false);
        if (ca instanceof LevelChunk lc) {
            chunk = lc;
        } else if (level.getChunkSource() instanceof ServerChunkCache scc) {
            chunk = scc.getChunkNow(sectionX, sectionZ);
        }

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

        LevelChunk chunk = null;
        ChunkAccess ca = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (ca instanceof LevelChunk lc) {
            chunk = lc;
        } else if (level.getChunkSource() instanceof ServerChunkCache scc) {
            chunk = scc.getChunkNow(chunkX, chunkZ);
        }

        if (chunk != null) {
            return cache.getOrCreateColumn(chunkX, chunkZ);
        }

        return null;
    }

    @Override
    public Heightmap2D getHeightmap(int chunkX, int chunkZ) {
        Heightmap2D hm = cache.getHeightmap(chunkX, chunkZ);
        if (hm != null && hm.getHighestY() != Heightmap2D.VOID_Y) {
            return hm;
        }

        LevelChunk chunk = null;
        ChunkAccess ca = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (ca instanceof LevelChunk lc) {
            chunk = lc;
        } else if (level.getChunkSource() instanceof ServerChunkCache scc) {
            chunk = scc.getChunkNow(chunkX, chunkZ);
        }

        if (chunk != null) {
            VoxelChunkColumn col = cache.getOrCreateColumn(chunkX, chunkZ);
            Heightmap2D colHm = col.getHeightmap();
            if (colHm.getHighestY() == Heightmap2D.VOID_Y) {
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = sections.length - 1; i >= 0; i--) {
                    LevelChunkSection s = sections[i];
                    if (s != null && !s.hasOnlyAir()) {
                        int secY = chunk.getSectionYFromSectionIndex(i);
                        short topY = (short) ((secY << 4) + 15);
                        colHm.recomputeHighest();
                        if (topY > colHm.getHighestY()) {
                            colHm.setHeight(0, 0, topY);
                        }
                        break;
                    }
                }
            }
            return colHm;
        }

        return hm;
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
