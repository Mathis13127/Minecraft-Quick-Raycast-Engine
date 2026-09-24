package com.pixel.qve.world.cache;

import com.pixel.qve.api.IVoxelGrid;
import com.pixel.qve.world.VoxelChunkColumn;


import com.pixel.qve.api.IVoxelWorld;
import com.pixel.qve.state.ShapeRegistry;
import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.world.Heightmap2D;
import com.pixel.qve.world.VoxelSection;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance, lock-free unified voxel cache and spatial grid.
 * Unifies in-memory chunks (live Minecraft world) and on-disk chunks (unloaded MCA files)
 * behind a seamless, zero-allocation IVoxelGrid interface.
 */
public class UnifiedVoxelCache implements IVoxelGrid, IVoxelWorld {

    /** Default minimum vertical section Y coordinate (-16, corresponding to Y=-256). */
    public static final int DEFAULT_MIN_SECTION_Y = -16;
    /** Default maximum vertical section Y coordinate (32, corresponding to Y=512). */
    public static final int DEFAULT_MAX_SECTION_Y = 32;

    private final BlockIdRegistry blockIdRegistry;
    private final ShapeRegistry shapeRegistry;
    private final com.pixel.qve.state.BlockTraitRegistry traitRegistry;
    private volatile IVoxelWorld diskFallback;
    private final int minSectionY;
    private final int maxSectionY;

    private final Map<Long, VoxelChunkColumn> columns = new ConcurrentHashMap<>();
    private final Map<Long, com.pixel.qve.world.RegionHeightmap2D> regionHeightmaps = new ConcurrentHashMap<>();
    private static final int L1_SIZE = 1024;
    private static final int L1_MASK = L1_SIZE - 1;
    private final long[] l1Keys = new long[L1_SIZE];
    private final VoxelChunkColumn[] l1Columns = new VoxelChunkColumn[L1_SIZE];
    private volatile short highestWorldY = Short.MIN_VALUE;

    /**
     * Constructs a UnifiedVoxelCache using the given block ID registry with default shape registry and bounds.
     *
     * @param blockIdRegistry Registry mapping block identifiers
     */
    public UnifiedVoxelCache(BlockIdRegistry blockIdRegistry) {
        this(blockIdRegistry, new ShapeRegistry(), new com.pixel.qve.state.BlockTraitRegistry(), null, DEFAULT_MIN_SECTION_Y, DEFAULT_MAX_SECTION_Y);
    }

    /**
     * Constructs a UnifiedVoxelCache using the given block ID and shape registries with default bounds.
     *
     * @param blockIdRegistry Registry mapping block identifiers
     * @param shapeRegistry   Registry mapping block collision shapes
     */
    public UnifiedVoxelCache(BlockIdRegistry blockIdRegistry, ShapeRegistry shapeRegistry) {
        this(blockIdRegistry, shapeRegistry, new com.pixel.qve.state.BlockTraitRegistry(), null, DEFAULT_MIN_SECTION_Y, DEFAULT_MAX_SECTION_Y);
    }

    /**
     * Constructs a UnifiedVoxelCache with an offline disk fallback (e.g. MCA region reader) and default bounds.
     *
     * @param blockIdRegistry Registry mapping block identifiers
     * @param shapeRegistry   Registry mapping block collision shapes
     * @param diskFallback    Underlying disk provider for uncached sections
     */
    public UnifiedVoxelCache(BlockIdRegistry blockIdRegistry, ShapeRegistry shapeRegistry, IVoxelWorld diskFallback) {
        this(blockIdRegistry, shapeRegistry, new com.pixel.qve.state.BlockTraitRegistry(), diskFallback, DEFAULT_MIN_SECTION_Y, DEFAULT_MAX_SECTION_Y);
    }

    /**
     * Constructs a fully customized UnifiedVoxelCache with explicit vertical section bounds.
     *
     * @param blockIdRegistry Registry mapping block identifiers
     * @param shapeRegistry   Registry mapping block collision shapes
     * @param diskFallback    Underlying disk provider for uncached sections
     * @param minSectionY     Minimum vertical section coordinate (inclusive, e.g. -16)
     * @param maxSectionY     Maximum vertical section coordinate (exclusive, e.g. 32)
     */
    public UnifiedVoxelCache(BlockIdRegistry blockIdRegistry, ShapeRegistry shapeRegistry, IVoxelWorld diskFallback, int minSectionY, int maxSectionY) {
        this(blockIdRegistry, shapeRegistry, new com.pixel.qve.state.BlockTraitRegistry(), diskFallback, minSectionY, maxSectionY);
    }

    /**
     * Constructs a fully customized UnifiedVoxelCache with explicit trait registry and vertical section bounds.
     *
     * @param blockIdRegistry Registry mapping block identifiers
     * @param shapeRegistry   Registry mapping block collision shapes
     * @param traitRegistry   Registry mapping physical/optical block traits
     * @param diskFallback    Underlying disk provider for uncached sections
     * @param minSectionY     Minimum vertical section coordinate (inclusive, e.g. -16)
     * @param maxSectionY     Maximum vertical section coordinate (exclusive, e.g. 32)
     */
    public UnifiedVoxelCache(BlockIdRegistry blockIdRegistry, ShapeRegistry shapeRegistry, com.pixel.qve.state.BlockTraitRegistry traitRegistry, IVoxelWorld diskFallback, int minSectionY, int maxSectionY) {
        this.blockIdRegistry = Objects.requireNonNull(blockIdRegistry, "BlockIdRegistry cannot be null");
        this.shapeRegistry = Objects.requireNonNull(shapeRegistry, "ShapeRegistry cannot be null");
        this.traitRegistry = (traitRegistry != null) ? traitRegistry : new com.pixel.qve.state.BlockTraitRegistry();
        this.diskFallback = diskFallback;
        this.minSectionY = minSectionY;
        this.maxSectionY = maxSectionY;
    }

    /**
     * Computes a 64-bit spatial hash key for chunk coordinates (X, Z).
     *
     * @param chunkX Chunk column X coordinate
     * @param chunkZ Chunk column Z coordinate
     * @return 64-bit long key
     */
    public static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX & 0xFFFFFFFFL)) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }

    @Override
    public VoxelChunkColumn getColumn(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        int slot = (int) ((key ^ (key >>> 16) ^ (key >>> 32)) & L1_MASK);
        if (l1Keys[slot] == key) {
            VoxelChunkColumn col = l1Columns[slot];
            if (col != null && col.getChunkX() == chunkX && col.getChunkZ() == chunkZ) {
                return col;
            }
        }

        VoxelChunkColumn column = columns.get(key);
        if (column != null) {
            l1Keys[slot] = key;
            l1Columns[slot] = column;
            return column;
        }

        if (diskFallback != null) {
            return diskFallback.getColumn(chunkX, chunkZ);
        }

        return null;
    }

    @Override
    public VoxelSection getSection(int sectionX, int sectionY, int sectionZ) {
        VoxelChunkColumn column = getColumn(sectionX, sectionZ);
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
     *
     * @param sectionX Section X coordinate
     * @param sectionY Section Y coordinate
     * @param sectionZ Section Z coordinate
     * @param section  VoxelSection to store
     */
    public void putSection(int sectionX, int sectionY, int sectionZ, VoxelSection section) {
        long cKey = chunkKey(sectionX, sectionZ);
        VoxelChunkColumn column = columns.computeIfAbsent(cKey, k -> new VoxelChunkColumn(sectionX, sectionZ, minSectionY, maxSectionY));
        column.setSection(sectionY, section);

        int slot = (int) ((cKey ^ (cKey >>> 16) ^ (cKey >>> 32)) & L1_MASK);
        l1Keys[slot] = cKey;
        l1Columns[slot] = column;

        short colHighest = column.getHeightmap().getHighestY();
        if (colHighest > highestWorldY) {
            highestWorldY = colHighest;
        }

        int rx = sectionX >> 5;
        int rz = sectionZ >> 5;
        regionHeightmaps.computeIfAbsent(regionKey(rx, rz), k -> new com.pixel.qve.world.RegionHeightmap2D())
                .updateMax(sectionX & 31, sectionZ & 31, colHighest);
    }

    /**
     * Atomically modifies a single voxel in world coordinates and updates the corresponding heightmap.
     *
     * @param worldX  Absolute block world X coordinate
     * @param worldY  Absolute block world Y coordinate
     * @param worldZ  Absolute block world Z coordinate
     * @param solid   True if voxel is solid matter
     * @param blockId 32-bit block type identifier
     */
    public void setVoxel(int worldX, int worldY, int worldZ, boolean solid, int blockId) {
        int chunkX = worldX >> 4;
        int chunkZ = worldZ >> 4;
        long cKey = chunkKey(chunkX, chunkZ);
        VoxelChunkColumn column = columns.computeIfAbsent(cKey, k -> new VoxelChunkColumn(chunkX, chunkZ, minSectionY, maxSectionY));
        column.setVoxel(worldX & 15, worldY, worldZ & 15, solid, blockId);

        if (solid && worldY > highestWorldY) {
            highestWorldY = (short) worldY;
        }

        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        regionHeightmaps.computeIfAbsent(regionKey(rx, rz), k -> new com.pixel.qve.world.RegionHeightmap2D())
                .updateMax(chunkX & 31, chunkZ & 31, column.getHeightmap().getHighestY());
    }

    /**
     * Updates the region heightmap and highest world Y for a chunk column.
     *
     * @param chunkX        Chunk X coordinate
     * @param chunkZ        Chunk Z coordinate
     * @param chunkHighestY Highest Y coordinate in this chunk column
     */
    public void updateChunkHeightmap(int chunkX, int chunkZ, short chunkHighestY) {
        if (chunkHighestY > highestWorldY) {
            highestWorldY = chunkHighestY;
        }
        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        regionHeightmaps.computeIfAbsent(regionKey(rx, rz), k -> new com.pixel.qve.world.RegionHeightmap2D())
                .updateMax(chunkX & 31, chunkZ & 31, chunkHighestY);
    }

    /**
     * Invalidates a single section in the cache.
     *
     * @param sectionX Section X coordinate
     * @param sectionY Section Y coordinate
     * @param sectionZ Section Z coordinate
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
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     */
    public void invalidateChunk(int chunkX, int chunkZ) {
        columns.remove(chunkKey(chunkX, chunkZ));
    }

    /**
     * Retrieves or creates the chunk column at the specified chunk coordinates.
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return VoxelChunkColumn instance
     */
    public VoxelChunkColumn getOrCreateColumn(int chunkX, int chunkZ) {
        return columns.computeIfAbsent(chunkKey(chunkX, chunkZ), k -> new VoxelChunkColumn(chunkX, chunkZ, minSectionY, maxSectionY));
    }

    @Override
    public Heightmap2D getHeightmap(int chunkX, int chunkZ) {
        VoxelChunkColumn column = getColumn(chunkX, chunkZ);
        if (column != null) {
            return column.getHeightmap();
        }
        if (diskFallback != null) {
            return diskFallback.getHeightmap(chunkX, chunkZ);
        }
        return null;
    }

    @Override
    public short getLowestWorldY() {
        return (short) (minSectionY << 4);
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

    @Override
    public com.pixel.qve.state.BlockTraitRegistry getTraitRegistry() {
        return traitRegistry;
    }

    /**
     * Retrieves the underlying BlockIdRegistry.
     *
     * @return BlockIdRegistry instance
     */
    public BlockIdRegistry getBlockIdRegistry() {
        return blockIdRegistry;
    }

    /**
     * Retrieves the underlying disk fallback grid, or null if none.
     *
     * @return Disk IVoxelGrid or null
     */
    public IVoxelWorld getDiskFallback() {
        return diskFallback;
    }

    /**
     * Dynamically sets or replaces the disk fallback provider (e.g. Anvil MCA reader).
     *
     * @param diskFallback Underlying disk provider for uncached sections
     */
    public void setDiskFallback(IVoxelWorld diskFallback) {
        this.diskFallback = diskFallback;
    }

    /**
     * Computes a 64-bit spatial hash key for 2D region coordinates.
     *
     * @param rx Region X coordinate
     * @param rz Region Z coordinate
     * @return 64-bit packed region key
     */
    public static long regionKey(int rx, int rz) {
        return (((long) rx & 0xFFFFFFFFL)) | (((long) rz & 0xFFFFFFFFL) << 32);
    }

    @Override
    public boolean isRegionEmpty(int regionX, int regionZ) {
        long rk = regionKey(regionX, regionZ);
        com.pixel.qve.world.RegionHeightmap2D rHm = regionHeightmaps.get(rk);
        if (rHm != null && rHm.getRegionMaxY() != com.pixel.qve.world.Heightmap2D.VOID_Y) {
            return false;
        }
        if (diskFallback != null) {
            return diskFallback.isRegionEmpty(regionX, regionZ);
        }
        return (rHm == null);
    }

    @Override
    public com.pixel.qve.world.RegionHeightmap2D getRegionHeightmap(int regionX, int regionZ) {
        long rk = regionKey(regionX, regionZ);
        com.pixel.qve.world.RegionHeightmap2D rHm = regionHeightmaps.get(rk);
        if (rHm != null && rHm.getRegionMaxY() != com.pixel.qve.world.Heightmap2D.VOID_Y) {
            return rHm;
        }
        if (diskFallback != null) {
            return diskFallback.getRegionHeightmap(regionX, regionZ);
        }
        return rHm;
    }

    @Override
    public short getRegionMaxY(int regionX, int regionZ) {
        long rk = regionKey(regionX, regionZ);
        com.pixel.qve.world.RegionHeightmap2D rHm = regionHeightmaps.get(rk);
        short cacheMax = (rHm != null) ? rHm.getRegionMaxY() : com.pixel.qve.world.Heightmap2D.VOID_Y;
        if (diskFallback != null) {
            short diskMax = diskFallback.getRegionMaxY(regionX, regionZ);
            if (diskMax > cacheMax && diskMax != Short.MAX_VALUE) {
                cacheMax = diskMax;
            } else if (cacheMax == com.pixel.qve.world.Heightmap2D.VOID_Y) {
                cacheMax = diskMax;
            }
        }
        if (cacheMax != com.pixel.qve.world.Heightmap2D.VOID_Y) {
            return cacheMax;
        }
        return isRegionEmpty(regionX, regionZ) ? com.pixel.qve.world.Heightmap2D.VOID_Y : Short.MAX_VALUE;
    }

    @Override
    public boolean isOutOfBounds(int worldBlockX, int worldBlockZ, int stepX, int stepZ) {
        if (diskFallback != null) {
            return diskFallback.isOutOfBounds(worldBlockX, worldBlockZ, stepX, stepZ);
        }
        return false;
    }

    @Override
    public boolean hasWorldBounds() {
        return diskFallback != null && diskFallback.hasWorldBounds();
    }

    @Override
    public int getWorldMinX() {
        return (diskFallback != null) ? diskFallback.getWorldMinX() : Integer.MIN_VALUE;
    }

    @Override
    public int getWorldMaxX() {
        return (diskFallback != null) ? diskFallback.getWorldMaxX() : Integer.MAX_VALUE;
    }

    @Override
    public int getWorldMinZ() {
        return (diskFallback != null) ? diskFallback.getWorldMinZ() : Integer.MIN_VALUE;
    }

    @Override
    public int getWorldMaxZ() {
        return (diskFallback != null) ? diskFallback.getWorldMaxZ() : Integer.MAX_VALUE;
    }

    /**
     * Gets the total number of chunk columns currently loaded in memory.
     *
     * @return Cached column count
     */
    public int getCachedColumnCount() {
        return columns.size();
    }

    /**
     * Gets the total number of non-empty voxel sections currently cached in memory.
     *
     * @return Cached section count
     */
    public int getCachedSectionCount() {
        int count = 0;
        for (VoxelChunkColumn col : columns.values()) {
            count += col.getCachedSectionCount();
        }
        return count;
    }

    /**
     * Evicts all cached columns and resets the highest world Y tracker.
     */
    public void clear() {
        columns.clear();
        java.util.Arrays.fill(l1Keys, 0L);
        java.util.Arrays.fill(l1Columns, null);
        highestWorldY = Short.MIN_VALUE;
    }

    @Override
    public com.pixel.qve.api.nbt.INbtService getNbtService() {
        return (diskFallback != null) ? diskFallback.getNbtService() : null;
    }
}
