package com.pixel.qve.neoforge.api;

import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelGrid;
import com.pixel.qve.world.Heightmap2D;
import com.pixel.qve.world.VoxelChunkColumn;
import com.pixel.qve.world.VoxelSection;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

import java.util.concurrent.CompletableFuture;

/**
 * High-level unified chunk and voxel query facade for Minecraft / NeoForge.
 * Seamlessly provides instant, lock-free access to both live RAM chunks and
 * offline Anvil MCA regions on disk for terrain generation, radar, LODs, and ballistics.
 */
public final class VoxelChunkAPI {

    private VoxelChunkAPI() {}

    /**
     * Retrieves the MinecraftVoxelGrid instance for the given Level.
     *
     * @param level Minecraft Level
     * @return MinecraftVoxelGrid spatial accessor
     */
    public static MinecraftVoxelGrid getGrid(Level level) {
        return MinecraftVoxelBridge.getOrCreateGrid(level);
    }

    /**
     * Retrieves or loads the VoxelChunkColumn at the specified chunk coordinates.
     * Checks live RAM chunks first, falling back to disk MCA if unloaded.
     *
     * @param level  Minecraft Level
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return VoxelChunkColumn, or null if empty or unpopulated
     */
    public static VoxelChunkColumn getColumn(Level level, int chunkX, int chunkZ) {
        if (level == null) return null;
        return getGrid(level).getColumn(chunkX, chunkZ);
    }

    /**
     * Retrieves the 2D heightmap for the specified chunk column.
     *
     * @param level  Minecraft Level
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return Heightmap2D, or null if unavailable
     */
    public static Heightmap2D getHeightmap(Level level, int chunkX, int chunkZ) {
        if (level == null) return null;
        return getGrid(level).getHeightmap(chunkX, chunkZ);
    }

    /**
     * Retrieves the VoxelSection at the specified section coordinates.
     *
     * @param level    Minecraft Level
     * @param sectionX Section X coordinate
     * @param sectionY Section Y coordinate
     * @param sectionZ Section Z coordinate
     * @return VoxelSection, or null if unpopulated or outside bounds
     */
    public static VoxelSection getSection(Level level, int sectionX, int sectionY, int sectionZ) {
        if (level == null) return null;
        return getGrid(level).getSection(sectionX, sectionY, sectionZ);
    }

    /**
     * Checks if a voxel at the given absolute block coordinates is solid.
     *
     * @param level  Minecraft Level
     * @param worldX Block X coordinate
     * @param worldY Block Y coordinate
     * @param worldZ Block Z coordinate
     * @return True if solid voxel, false if air or non-solid
     */
    public static boolean isSolid(Level level, int worldX, int worldY, int worldZ) {
        if (level == null) return false;
        int sectionX = worldX >> 4;
        int sectionY = worldY >> 4;
        int sectionZ = worldZ >> 4;
        VoxelSection section = getGrid(level).getSection(sectionX, sectionY, sectionZ);
        if (section == null || section.isEmpty()) {
            return false;
        }
        return section.isSolid(worldX & 15, worldY & 15, worldZ & 15);
    }

    /**
     * Retrieves the 32-bit compact block ID at the given absolute block coordinates.
     *
     * @param level  Minecraft Level
     * @param worldX Block X coordinate
     * @param worldY Block Y coordinate
     * @param worldZ Block Z coordinate
     * @return 32-bit block ID (0 for air)
     */
    public static int getBlockId(Level level, int worldX, int worldY, int worldZ) {
        if (level == null) return 0;
        int sectionX = worldX >> 4;
        int sectionY = worldY >> 4;
        int sectionZ = worldZ >> 4;
        VoxelSection section = getGrid(level).getSection(sectionX, sectionY, sectionZ);
        if (section == null || section.isEmpty()) {
            return 0;
        }
        return section.getBlockId(worldX & 15, worldY & 15, worldZ & 15);
    }

    /**
     * Retrieves the CompoundTag for a block entity at the given BlockPos.
     * Transparently queries live RAM chunks or offline MCA on disk.
     *
     * @param level Minecraft Level
     * @param pos   Block position
     * @return CompoundTag, or null if absent
     */
    public static CompoundTag getBlockEntityData(Level level, BlockPos pos) {
        if (level == null || pos == null) return null;
        return getGrid(level).getBlockEntityCompoundTag(pos);
    }

    /**
     * Asynchronously pre-fetches and compiles chunk columns in a square area.
     *
     * @param level        Minecraft Level
     * @param centerChunkX Center chunk X coordinate
     * @param centerChunkZ Center chunk Z coordinate
     * @param radiusChunks Radius in chunks to prefetch
     * @return CompletableFuture completing when all chunks are cached
     */
    public static CompletableFuture<Void> prefetchAreaAsync(Level level, int centerChunkX, int centerChunkZ, int radiusChunks) {
        if (level == null || radiusChunks < 0) {
            return CompletableFuture.completedFuture(null);
        }
        MinecraftVoxelGrid grid = getGrid(level);
        return CompletableFuture.runAsync(() -> {
            for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
                for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                    grid.getColumn(centerChunkX + dx, centerChunkZ + dz);
                }
            }
        });
    }
}
