package com.pixel.raycast.core.mca;

import com.pixel.raycast.core.api.IVoxelGrid;
import com.pixel.raycast.core.cache.VoxelChunkColumn;
import com.pixel.raycast.core.voxel.BlockIdRegistry;
import com.pixel.raycast.core.voxel.Heightmap2D;
import com.pixel.raycast.core.voxel.VoxelSection;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Universal voxel world provider backed by one or more Minecraft Anvil (.mca) region files.
 * Provides on-demand chunk loading and ultra-fast section and column lookup for 3D DDA raycasting.
 */
public final class McaVoxelGrid implements IVoxelGrid, java.io.Closeable {

    private static final System.Logger LOGGER = System.getLogger(McaVoxelGrid.class.getName());

    private final BlockIdRegistry registry;
    private final Map<Long, VoxelChunkColumn> columnCache = new ConcurrentHashMap<>();
    private static final int L1_SIZE = 1024;
    private static final int L1_MASK = L1_SIZE - 1;
    private final long[] l1Keys = new long[L1_SIZE];
    private final VoxelChunkColumn[] l1Columns = new VoxelChunkColumn[L1_SIZE];
    private final Object[] chunkLocks = new Object[256];

    private final Map<Long, McaRegionReader> regions = new ConcurrentHashMap<>();
    private final java.util.Set<Long> loadedChunks = ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> missingRegions = ConcurrentHashMap.newKeySet();
    private final com.pixel.raycast.core.shape.ShapeRegistry shapeRegistry;
    private final Path regionDirectory;
    private final short minWorldY;
    private final int minSectionY;
    private final int maxSectionY;
    private volatile short highestWorldY = Short.MIN_VALUE;

    /**
     * Constructs an McaVoxelGrid with an in-memory block registry and default min build height (-64).
     *
     * @param registry BlockIdRegistry to map block state names
     */
    public McaVoxelGrid(BlockIdRegistry registry) {
        this(registry, null, null, (short) -64);
    }

    /**
     * Constructs an McaVoxelGrid with an optional on-disk region directory and default min build height (-64).
     *
     * @param registry        BlockIdRegistry to map block state names
     * @param regionDirectory Root directory containing .mca region files, or null
     */
    public McaVoxelGrid(BlockIdRegistry registry, Path regionDirectory) {
        this(registry, null, regionDirectory, (short) -64);
    }

    /**
     * Constructs an McaVoxelGrid with an optional on-disk region directory and dynamic min build height.
     *
     * @param registry        BlockIdRegistry to map block state names
     * @param regionDirectory Root directory containing .mca region files, or null
     * @param minWorldY       Minimum world build height (e.g. -64 for overworld, 0 for nether)
     */
    public McaVoxelGrid(BlockIdRegistry registry, Path regionDirectory, short minWorldY) {
        this(registry, null, regionDirectory, minWorldY);
    }

    /**
     * Constructs an McaVoxelGrid with an optional ShapeRegistry for full-cube classification, on-disk region directory and dynamic min build height.
     *
     * @param registry        BlockIdRegistry to map block state names
     * @param shapeRegistry   Optional ShapeRegistry for full-cube collision analysis
     * @param regionDirectory Root directory containing .mca region files, or null
     * @param minWorldY       Minimum world build height (e.g. -64 for overworld, 0 for nether)
     */
    public McaVoxelGrid(BlockIdRegistry registry, com.pixel.raycast.core.shape.ShapeRegistry shapeRegistry, Path regionDirectory, short minWorldY) {
        this.registry = Objects.requireNonNull(registry, "BlockIdRegistry cannot be null");
        this.shapeRegistry = (shapeRegistry != null) ? shapeRegistry : new com.pixel.raycast.core.shape.ShapeRegistry();
        this.regionDirectory = regionDirectory;
        this.minWorldY = minWorldY;
        this.minSectionY = Math.min(-16, minWorldY >> 4);
        this.maxSectionY = Math.max(32, (minWorldY >> 4) + 48);
        Arrays.fill(this.l1Keys, Long.MIN_VALUE);
        for (int i = 0; i < this.chunkLocks.length; i++) {
            this.chunkLocks[i] = new Object();
        }
    }

    /**
     * Computes a 64-bit spatial hash key for 3D section coordinates.
     *
     * @param sx Section X coordinate
     * @param sy Section Y coordinate
     * @param sz Section Z coordinate
     * @return 64-bit packed section key
     */
    public static long sectionKey(int sx, int sy, int sz) {
        return (((long) sx & 0x3FFFFFL)) | (((long) sz & 0x3FFFFFL) << 22) | (((long) sy & 0xFFFFFL) << 44);
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

    /**
     * Computes a 64-bit spatial hash key for 2D chunk coordinates.
     *
     * @param cx Chunk column X coordinate
     * @param cz Chunk column Z coordinate
     * @return 64-bit packed chunk key
     */
    public static long chunkKey(int cx, int cz) {
        return (((long) cx & 0xFFFFFFFFL)) | (((long) cz & 0xFFFFFFFFL) << 32);
    }

    @Override
    public VoxelChunkColumn getColumn(int chunkX, int chunkZ) {
        long cKey = chunkKey(chunkX, chunkZ);
        int slot = (int) ((cKey ^ (cKey >>> 16) ^ (cKey >>> 32)) & L1_MASK);
        if (l1Keys[slot] == cKey) {
            return l1Columns[slot];
        }

        VoxelChunkColumn col = columnCache.get(cKey);
        if (col != null) {
            l1Keys[slot] = cKey;
            l1Columns[slot] = col;
            return col;
        }

        if (loadedChunks.contains(cKey)) {
            return null; // Chunk is already loaded and empty/non-existent
        }

        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        long rKey = regionKey(rx, rz);
        if (missingRegions.contains(rKey)) {
            loadedChunks.add(cKey);
            return null;
        }

        if (regions.containsKey(rKey) || regionDirectory != null) {
            try {
                loadChunk(chunkX, chunkZ);
                VoxelChunkColumn loadedCol = columnCache.get(cKey);
                if (loadedCol != null) {
                    l1Keys[slot] = cKey;
                    l1Columns[slot] = loadedCol;
                }
                return loadedCol;
            } catch (Exception e) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Failed to lazy-load chunk ({0}, {1}) from region r.{2}.{3}.mca: {4}",
                        chunkX, chunkZ, rx, rz, e.getMessage());
                return null;
            }
        }
        return null;
    }

    @Override
    public Heightmap2D getHeightmap(int chunkX, int chunkZ) {
        VoxelChunkColumn column = getColumn(chunkX, chunkZ);
        return (column != null) ? column.getHeightmap() : null;
    }

    @Override
    public short getHighestWorldY() {
        return highestWorldY;
    }

    @Override
    public com.pixel.raycast.core.shape.ShapeRegistry getShapeRegistry() {
        return shapeRegistry;
    }

    /**
     * Gets the underlying block identifier registry.
     *
     * @return BlockIdRegistry instance
     */
    public BlockIdRegistry getRegistry() {
        return registry;
    }

    /**
     * Registers an open MCA region file into this voxel grid.
     *
     * @param reader Region reader instance to register
     */
    public void registerRegion(McaRegionReader reader) {
        Objects.requireNonNull(reader, "McaRegionReader cannot be null");
        long rk = regionKey(reader.getRegionX(), reader.getRegionZ());
        regions.put(rk, reader);
        missingRegions.remove(rk);
    }

    /**
     * Pre-loads all chunks from a region into the in-memory cache for maximum raycast speed.
     *
     * @param reader Region reader to load
     * @return Total number of sections loaded into memory
     * @throws IOException If I/O or decompression fails
     */
    public int preloadRegion(McaRegionReader reader) throws IOException {
        registerRegion(reader);
        int totalLoaded = 0;
        int rx = reader.getRegionX();
        int rz = reader.getRegionZ();

        for (int cz = 0; cz < 32; cz++) {
            for (int cx = 0; cx < 32; cx++) {
                int worldCx = (rx << 5) | cx;
                int worldCz = (rz << 5) | cz;
                long cKey = chunkKey(worldCx, worldCz);
                loadedChunks.add(cKey);

                if (!reader.hasChunk(cx, cz)) continue;

                VoxelChunkColumn column = new VoxelChunkColumn(worldCx, worldCz, minSectionY, maxSectionY);
                int loaded;
                synchronized (reader) {
                    loaded = reader.readChunk(cx, cz, (sectionY, section) -> {
                        column.setSection(sectionY, section);
                        if (section != null && !section.isEmpty()) {
                            short h = column.getHeightmap().getHighestY();
                            if (h > highestWorldY) {
                                highestWorldY = h;
                            }
                        }
                    });
                }
                if (loaded > 0) {
                    columnCache.put(cKey, column);
                    int slot = (int) ((cKey ^ (cKey >>> 16) ^ (cKey >>> 32)) & L1_MASK);
                    l1Keys[slot] = cKey;
                    l1Columns[slot] = column;
                }
                totalLoaded += loaded;
            }
        }
        return totalLoaded;
    }

    /**
     * Loads a specific world chunk into cache from registered regions.
     *
     * @param chunkX World chunk column X
     * @param chunkZ World chunk column Z
     * @return Number of sections parsed and cached
     * @throws IOException If reading from disk fails
     */
    public int loadChunk(int chunkX, int chunkZ) throws IOException {
        long cKey = chunkKey(chunkX, chunkZ);
        VoxelChunkColumn existing = columnCache.get(cKey);
        if (existing != null) {
            return 0;
        }
        if (loadedChunks.contains(cKey)) {
            return 0;
        }

        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        long rKey = regionKey(rx, rz);
        if (missingRegions.contains(rKey)) {
            loadedChunks.add(cKey);
            return 0;
        }

        int lockIndex = (int) ((cKey ^ (cKey >>> 8) ^ (cKey >>> 16)) & 0xFF);
        synchronized (chunkLocks[lockIndex]) {
            if (columnCache.containsKey(cKey) || loadedChunks.contains(cKey)) {
                return 0;
            }

            McaRegionReader reader = regions.computeIfAbsent(rKey, k -> {
                if (regionDirectory != null) {
                    Path mcaFile = regionDirectory.resolve("r." + rx + "." + rz + ".mca");
                    if (java.nio.file.Files.exists(mcaFile)) {
                        try {
                            return new McaRegionReader(mcaFile, registry, shapeRegistry);
                        } catch (IOException e) {
                            LOGGER.log(System.Logger.Level.WARNING,
                                    "Failed to open MCA region file {0}: {1}", mcaFile, e.getMessage());
                            missingRegions.add(k);
                            return null;
                        }
                    } else {
                        missingRegions.add(k);
                    }
                }
                return null;
            });

            if (reader == null) {
                loadedChunks.add(cKey);
                return 0;
            }

            int localCx = chunkX & 31;
            int localCz = chunkZ & 31;
            VoxelChunkColumn column = new VoxelChunkColumn(chunkX, chunkZ, minSectionY, maxSectionY);
            int parsed;
            synchronized (reader) {
                parsed = reader.readChunk(localCx, localCz, (sectionY, section) -> {
                    column.setSection(sectionY, section);
                    if (section != null && !section.isEmpty()) {
                        short h = column.getHeightmap().getHighestY();
                        if (h > highestWorldY) {
                            highestWorldY = h;
                        }
                    }
                });
            }

            if (parsed > 0) {
                columnCache.put(cKey, column);
                int slot = (int) ((cKey ^ (cKey >>> 16) ^ (cKey >>> 32)) & L1_MASK);
                l1Keys[slot] = cKey;
                l1Columns[slot] = column;
            }
            loadedChunks.add(cKey);
            return parsed;
        }
    }

    @Override
    public VoxelSection getSection(int sectionX, int sectionY, int sectionZ) {
        VoxelChunkColumn column = getColumn(sectionX, sectionZ);
        return (column != null) ? column.getSection(sectionY) : null;
    }

    @Override
    public short getLowestWorldY() {
        return minWorldY;
    }

    /**
     * Gets the total number of non-empty voxel sections currently in memory.
     *
     * @return Cached section count
     */
    public int getCachedSectionCount() {
        int count = 0;
        for (VoxelChunkColumn column : columnCache.values()) {
            if (column != null) {
                count += column.getCachedSectionCount();
            }
        }
        return count;
    }

    /**
     * Clears all cached voxel sections from memory.
     */
    public void clearCache() {
        columnCache.clear();
        Arrays.fill(l1Keys, Long.MIN_VALUE);
        Arrays.fill(l1Columns, null);
        loadedChunks.clear();
        missingRegions.clear();
    }

    @Override
    public void close() {
        for (McaRegionReader reader : regions.values()) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    LOGGER.log(System.Logger.Level.WARNING, "Error closing region reader: {0}", e.getMessage());
                }
            }
        }
        regions.clear();
        clearCache();
    }
}
