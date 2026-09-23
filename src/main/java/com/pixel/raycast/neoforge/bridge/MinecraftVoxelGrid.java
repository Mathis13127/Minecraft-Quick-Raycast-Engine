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
        VoxelSection section = cache.getSection(sectionX, sectionY, sectionZ);
        if (section != null) {
            return section;
        }

        // Section not in cache: check if chunk is loaded in live Minecraft memory
        LevelChunk chunk = null;
        if (level.getChunkSource() instanceof ServerChunkCache scc) {
            chunk = scc.getChunkNow(sectionX, sectionZ);
        } else {
            ChunkAccess ca = level.getChunk(sectionX, sectionZ, ChunkStatus.FULL, false);
            if (ca instanceof LevelChunk lc) {
                chunk = lc;
            }
        }

        if (chunk != null) {
            int blockY = sectionY << 4;
            if (!level.isOutsideBuildHeight(blockY)) {
                int secIdx = chunk.getSectionIndex(blockY);
                LevelChunkSection[] sections = chunk.getSections();
                if (secIdx >= 0 && secIdx < sections.length) {
                    LevelChunkSection vanillaSection = sections[secIdx];
                    VoxelChunkColumn column = cache.getOrCreateColumn(sectionX, sectionZ);
                    return MinecraftVoxelBridge.compileSection(vanillaSection, column, sectionY);
                }
            }
        }

        return null;
    }

    @Override
    public Heightmap2D getHeightmap(int chunkX, int chunkZ) {
        return cache.getHeightmap(chunkX, chunkZ);
    }

    @Override
    public short getHighestWorldY() {
        return cache.getHighestWorldY();
    }

    @Override
    public ShapeRegistry getShapeRegistry() {
        return cache.getShapeRegistry();
    }
}
