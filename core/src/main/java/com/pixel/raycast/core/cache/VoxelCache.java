package com.pixel.raycast.core.cache;

import com.pixel.raycast.core.api.IVoxelGrid;
import com.pixel.raycast.core.shape.ShapeRegistry;
import com.pixel.raycast.core.voxel.BlockIdRegistry;
import com.pixel.raycast.core.voxel.Heightmap2D;
import com.pixel.raycast.core.voxel.VoxelSection;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance, lock-free unified voxel cache and spatial grid.
 * Unifies in-memory chunks (live Minecraft world) and on-disk chunks (unloaded MCA files)
 * behind a seamless, zero-allocation IVoxelGrid interface.
 */
public final class VoxelCache implements IVoxelGrid {

    public static final int DEFAULT_MIN_SECTION_Y = -16;
    public static final int DEFAULT_MAX_SECTION_Y = 32;

    private final BlockIdRegistry blockIdRegistry;
    private final ShapeRegistry shapeRegistry;
    private final IVoxelGrid diskFallback;
    private final int minSectionY;
    private final int maxSectionY;

    private final Map<Long, VoxelChunkColumn> columns = new ConcurrentHashMap<>();
    private volatile short highestWorldY = Short.MIN_VALUE;

    public VoxelCache(BlockIdRegistry blockIdRegistry) {
        this(blockIdRegistry, new ShapeRegistry(), null, DEFAULT_MIN_SECTION_Y, DEFAULT_MAX_SECTION_Y);
    }

    public VoxelCache(BlockIdRegistry blockIdRegistry, ShapeRegistry shapeRegistry) {
        this(blockIdRegistry, shapeRegistry, null, DEFAULT_MIN_SECTION_Y, DEFAULT_MAX_SECTION_Y);
    }

    public VoxelCache(BlockIdRegistry blockIdRegistry, ShapeRegistry shapeRegistry, IVoxelGrid diskFallback) {
        this(blockIdRegistry, shapeRegistry, diskFallback, DEFAULT_MIN_SECTION_Y, DEFAULT_MAX_SECTION_Y);
    }

    public VoxelCache(BlockIdRegistry blockIdRegistry, ShapeRegistry shapeRegistry, IVoxelGrid diskFallback, int minSectionY, int maxSectionY) {
        this.blockIdRegistry = Objects.requireNonNull(blockIdRegistry, "BlockIdRegistry cannot be null");
        this.shapeRegistry = Objects.requireNonNull(shapeRegistry, "ShapeRegistry cannot be null");
        this.diskFallback = diskFallback;
        this.minSectionY = minSectionY;
        this.maxSectionY = maxSectionY;
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX & 0xFFFFFFFFL)) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }

    @Override
    public VoxelSection getSection(int sectionX, int sectionY, int sectionZ) {
        long cKey = chunkKey(sectionX, sectionZ);
        VoxelChunkColumn column = columns.get(cKey);
        if (column != null) {
            VoxelSection section = column.getSection(sectionY);
            if (section != null) {
                return section;
            }
        }

        // Lazy-load from disk fallback (MCA) if available
        if (diskFallback != null) {
            VoxelSection diskSection = diskFallback.getSection(sectionX, sectionY, sectionZ);
            if (diskSection != null) {
                putSection(sectionX, sectionY, sectionZ, diskSection);
                return diskSection;
            }
        }

        return null;
    }

    /**
     * Stores or updates a VoxelSection at the given section coordinates.
     */
    public void putSection(int sectionX, int sectionY, int sectionZ, VoxelSection section) {
        long cKey = chunkKey(sectionX, sectionZ);
        VoxelChunkColumn column = columns.computeIfAbsent(cKey, k -> new VoxelChunkColumn(sectionX, sectionZ, minSectionY, maxSectionY));
        column.setSection(sectionY, section);

        short colHighest = column.getHeightmap().getHighestY();
        if (colHighest > highestWorldY) {
            highestWorldY = colHighest;
        }
    }

    /**
     * Atomically modifies a single voxel in world coordinates and updates the corresponding heightmap.
     */
    public void setVoxel(int worldX, int worldY, int worldZ, boolean solid, short blockId) {
        int chunkX = worldX >> 4;
        int chunkZ = worldZ >> 4;
        long cKey = chunkKey(chunkX, chunkZ);
        VoxelChunkColumn column = columns.computeIfAbsent(cKey, k -> new VoxelChunkColumn(chunkX, chunkZ, minSectionY, maxSectionY));
        column.setVoxel(worldX & 15, worldY, worldZ & 15, solid, blockId);

        if (solid && worldY > highestWorldY) {
            highestWorldY = (short) worldY;
        }
    }

    /**
     * Invalidates a single section in the cache.
     */
    public void invalidateSection(int sectionX, int sectionY, int sectionZ) {
        long cKey = chunkKey(sectionX, sectionZ);
        VoxelChunkColumn column = columns.get(cKey);
        if (column != null) {
            column.setSection(sectionY, null);
        }
    }

    /**
     * Invalidates an entire chunk column from the in-memory cache.
     */
    public void invalidateChunk(int chunkX, int chunkZ) {
        columns.remove(chunkKey(chunkX, chunkZ));
    }

    /**
     * Retrieves or creates the chunk column at the specified chunk coordinates.
     */
    public VoxelChunkColumn getOrCreateColumn(int chunkX, int chunkZ) {
        return columns.computeIfAbsent(chunkKey(chunkX, chunkZ), k -> new VoxelChunkColumn(chunkX, chunkZ, minSectionY, maxSectionY));
    }

    /**
     * Retrieves the chunk column if currently cached in memory, or null otherwise.
     */
    public VoxelChunkColumn getColumn(int chunkX, int chunkZ) {
        return columns.get(chunkKey(chunkX, chunkZ));
    }

    @Override
    public Heightmap2D getHeightmap(int chunkX, int chunkZ) {
        long cKey = chunkKey(chunkX, chunkZ);
        VoxelChunkColumn column = columns.get(cKey);
        if (column != null) {
            return column.getHeightmap();
        }
        if (diskFallback != null) {
            return diskFallback.getHeightmap(chunkX, chunkZ);
        }
        return null;
    }

    @Override
    public short getHighestWorldY() {
        short max = highestWorldY;
        if (diskFallback != null) {
            short diskMax = diskFallback.getHighestWorldY();
            if (diskMax > max) {
                max = diskMax;
            }
        }
        return max;
    }

    @Override
    public ShapeRegistry getShapeRegistry() {
        return shapeRegistry;
    }

    public BlockIdRegistry getBlockIdRegistry() {
        return blockIdRegistry;
    }

    public IVoxelGrid getDiskFallback() {
        return diskFallback;
    }

    public int getCachedColumnCount() {
        return columns.size();
    }

    public int getCachedSectionCount() {
        int count = 0;
        for (VoxelChunkColumn col : columns.values()) {
            count += col.getCachedSectionCount();
        }
        return count;
    }

    public void clear() {
        columns.clear();
        highestWorldY = Short.MIN_VALUE;
    }
}
