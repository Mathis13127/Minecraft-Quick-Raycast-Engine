package com.pixel.qve.mca;

import com.pixel.qve.api.IVoxelGrid;
import com.pixel.qve.state.ShapeRegistry;


import com.pixel.qve.api.nbt.INbtService;
import com.pixel.qve.api.IVoxelWorld;
import com.pixel.qve.world.VoxelChunkColumn;
import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.world.Heightmap2D;
import com.pixel.qve.world.VoxelSection;

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
    private final OnDemandNbtFetcher nbtFetcher;
    private final Map<Long, VoxelChunkColumn> columnCache = new ConcurrentHashMap<>();
    private static final int L1_SIZE = 16384;
    private static final int L1_MASK = L1_SIZE - 1;
    private final long[] l1Keys = new long[L1_SIZE];
    private final VoxelChunkColumn[] l1Columns = new VoxelChunkColumn[L1_SIZE];
    private final Object[] chunkLocks = new Object[256];

    private final Map<Long, McaRegionReader> regions = new ConcurrentHashMap<>();
    private final Map<Long, com.pixel.qve.world.RegionHeightmap2D> regionHeightmaps = new ConcurrentHashMap<>();
    private final java.util.Set<Long> loadedChunks = ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> missingRegions = ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> existingRegions = ConcurrentHashMap.newKeySet();
    private final com.pixel.qve.state.ShapeRegistry shapeRegistry;
    private final Path regionDirectory;
    private final short minWorldY;
    private final int minSectionY;
    private final int maxSectionY;
    private volatile short highestWorldY = Short.MIN_VALUE;

    public static final int DEFAULT_MAX_OPEN_REGIONS = 32768;
    public static final int DEFAULT_MAX_CACHED_COLUMNS = 262144;

    private final int maxOpenRegions;
    private final int maxCachedColumns;
    private final java.util.concurrent.ConcurrentLinkedDeque<Long> regionEvictionQueue = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final java.util.concurrent.ConcurrentLinkedDeque<Long> columnEvictionQueue = new java.util.concurrent.ConcurrentLinkedDeque<>();

    private volatile int minBlockX = Integer.MAX_VALUE;
    private volatile int maxBlockX = Integer.MIN_VALUE;
    private volatile int minBlockZ = Integer.MAX_VALUE;
    private volatile int maxBlockZ = Integer.MIN_VALUE;

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
    public McaVoxelGrid(BlockIdRegistry registry, com.pixel.qve.state.ShapeRegistry shapeRegistry, Path regionDirectory, short minWorldY) {
        this(registry, shapeRegistry, regionDirectory, minWorldY, DEFAULT_MAX_OPEN_REGIONS, DEFAULT_MAX_CACHED_COLUMNS);
    }

    /**
     * Constructs an McaVoxelGrid with explicit cache limits for memory-constrained environments.
     *
     * @param registry         BlockIdRegistry to map block state names
     * @param shapeRegistry    Optional ShapeRegistry for full-cube classification
     * @param regionDirectory  Root directory containing .mca region files, or null
     * @param minWorldY        Minimum world build height (e.g. -64 for overworld, 0 for nether)
     * @param maxOpenRegions   Maximum simultaneously open MCA region readers before LRU eviction
     * @param maxCachedColumns Maximum cached chunk columns before LRU eviction
     */
    public McaVoxelGrid(BlockIdRegistry registry, com.pixel.qve.state.ShapeRegistry shapeRegistry, Path regionDirectory, short minWorldY, int maxOpenRegions, int maxCachedColumns) {
        this.registry = Objects.requireNonNull(registry, "BlockIdRegistry cannot be null");
        this.shapeRegistry = (shapeRegistry != null) ? shapeRegistry : new com.pixel.qve.state.ShapeRegistry();
        this.regionDirectory = regionDirectory;
        this.minWorldY = minWorldY;
        this.minSectionY = Math.min(-16, minWorldY >> 4);
        this.maxSectionY = Math.max(32, (minWorldY >> 4) + 48);
        this.maxOpenRegions = Math.max(1, maxOpenRegions);
        this.maxCachedColumns = Math.max(16, maxCachedColumns);
        Arrays.fill(this.l1Keys, Long.MIN_VALUE);
        for (int i = 0; i < this.chunkLocks.length; i++) {
            this.chunkLocks[i] = new Object();
        }
        this.nbtFetcher = new OnDemandNbtFetcher(this::getOrOpenRegion);
        scanRegionDirectoryBounds();
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
        VoxelChunkColumn l1Col = l1Columns[slot];
        if (l1Keys[slot] == cKey && l1Col != null && l1Col.getChunkX() == chunkX && l1Col.getChunkZ() == chunkZ) {
            return l1Col;
        }

        VoxelChunkColumn col = columnCache.get(cKey);
        if (col != null) {
            l1Columns[slot] = col;
            l1Keys[slot] = cKey;
            return col;
        }

        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        long rKey = regionKey(rx, rz);
        if (missingRegions.contains(rKey)) {
            return null;
        }

        if (!existingRegions.contains(rKey) && !regions.containsKey(rKey)) {
            if (regionDirectory != null) {
                Path mcaFile = regionDirectory.resolve("r." + rx + "." + rz + ".mca");
                if (!java.nio.file.Files.exists(mcaFile)) {
                    missingRegions.add(rKey);
                    return null;
                }
                existingRegions.add(rKey);
            } else {
                return null;
            }
        }

        try {
            loadChunk(chunkX, chunkZ);
            VoxelChunkColumn loadedCol = columnCache.get(cKey);
            if (loadedCol != null) {
                l1Columns[slot] = loadedCol;
                l1Keys[slot] = cKey;
            }
            return loadedCol;
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Failed to lazy-load chunk ({0}, {1}) from region r.{2}.{3}.mca: {4}",
                    chunkX, chunkZ, rx, rz, e.getMessage());
            return null;
        }
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
    public com.pixel.qve.state.ShapeRegistry getShapeRegistry() {
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
        existingRegions.add(rk);
        updateBounds(reader.getRegionX(), reader.getRegionZ());
    }

    private synchronized void updateBounds(int rx, int rz) {
        int bxMin = rx << 9;
        int bxMax = (rx + 1) << 9;
        int bzMin = rz << 9;
        int bzMax = (rz + 1) << 9;
        if (bxMin < minBlockX) minBlockX = bxMin;
        if (bxMax > maxBlockX) maxBlockX = bxMax;
        if (bzMin < minBlockZ) minBlockZ = bzMin;
        if (bzMax > maxBlockZ) maxBlockZ = bzMax;
    }

    private void scanRegionDirectoryBounds() {
        if (regionDirectory != null && java.nio.file.Files.isDirectory(regionDirectory)) {
            try (java.nio.file.DirectoryStream<Path> stream = java.nio.file.Files.newDirectoryStream(regionDirectory, "r.*.*.mca")) {
                for (Path p : stream) {
                    String name = p.getFileName().toString();
                    String[] parts = name.split("\\.");
                    if (parts.length >= 4) {
                        try {
                            int rx = Integer.parseInt(parts[1]);
                            int rz = Integer.parseInt(parts[2]);
                            existingRegions.add(regionKey(rx, rz));
                            updateBounds(rx, rz);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            } catch (IOException e) {
                LOGGER.log(System.Logger.Level.WARNING, "Failed to scan region directory {0}: {1}", regionDirectory, e.getMessage());
            }
        }
    }

    @Override
    public boolean isRegionEmpty(int regionX, int regionZ) {
        long rk = regionKey(regionX, regionZ);
        if (regions.containsKey(rk) || existingRegions.contains(rk)) {
            return false;
        }
        if (missingRegions.contains(rk)) {
            return true;
        }
        if (regionDirectory != null) {
            Path mcaFile = regionDirectory.resolve("r." + regionX + "." + regionZ + ".mca");
            if (java.nio.file.Files.exists(mcaFile)) {
                existingRegions.add(rk);
                return false;
            }
            missingRegions.add(rk);
            return true;
        }
        return true;
    }

    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        long cKey = chunkKey(chunkX, chunkZ);
        if (columnCache.containsKey(cKey) || loadedChunks.contains(cKey)) {
            return true;
        }
        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        if (isRegionEmpty(rx, rz)) {
            return false;
        }
        McaRegionReader reader = getOrOpenRegion(rx, rz);
        if (reader == null) {
            return false;
        }
        return reader.hasChunk(chunkX & 31, chunkZ & 31);
    }

    @Override
    public boolean hasSolidData(int chunkX, int chunkZ) {
        if (!hasChunk(chunkX, chunkZ)) {
            return false;
        }
        VoxelChunkColumn col = getColumn(chunkX, chunkZ);
        return col != null && !col.isEmpty() && col.getHeightmap() != null && col.getHeightmap().getHighestY() != com.pixel.qve.world.Heightmap2D.VOID_Y;
    }

    @Override
    public com.pixel.qve.world.RegionHeightmap2D getRegionHeightmap(int regionX, int regionZ) {
        return regionHeightmaps.get(regionKey(regionX, regionZ));
    }

    @Override
    public short getRegionMaxY(int regionX, int regionZ) {
        long rKey = regionKey(regionX, regionZ);
        com.pixel.qve.world.RegionHeightmap2D rHm = regionHeightmaps.get(rKey);
        if (rHm != null && rHm.getRegionMaxY() != com.pixel.qve.world.Heightmap2D.VOID_Y) {
            return rHm.getRegionMaxY();
        }
        if (isRegionEmpty(regionX, regionZ)) {
            return com.pixel.qve.world.Heightmap2D.VOID_Y;
        }
        return Short.MAX_VALUE;
    }

    @Override
    public boolean isOutOfBounds(int worldBlockX, int worldBlockZ, int stepX, int stepZ) {
        if (minBlockX > maxBlockX) {
            return false; // No bounded regions registered
        }
        if (stepX > 0 && worldBlockX >= maxBlockX) {
            return true;
        }
        if (stepX < 0 && worldBlockX < minBlockX) {
            return true;
        }
        if (stepZ > 0 && worldBlockZ >= maxBlockZ) {
            return true;
        }
        if (stepZ < 0 && worldBlockZ < minBlockZ) {
            return true;
        }
        return false;
    }

    @Override
    public boolean hasWorldBounds() {
        return minBlockX <= maxBlockX && minBlockZ <= maxBlockZ;
    }

    @Override
    public int getWorldMinX() {
        return minBlockX;
    }

    @Override
    public int getWorldMaxX() {
        return maxBlockX;
    }

    @Override
    public int getWorldMinZ() {
        return minBlockZ;
    }

    @Override
    public int getWorldMaxZ() {
        return maxBlockZ;
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
        long rKey = regionKey(rx, rz);

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
                    short colH = column.getHeightmap().getHighestY();
                    regionHeightmaps.computeIfAbsent(rKey, k -> new com.pixel.qve.world.RegionHeightmap2D())
                            .updateMax(cx, cz, colH);
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
            if (regionDirectory != null && java.nio.file.Files.exists(regionDirectory.resolve("r." + rx + "." + rz + ".mca"))) {
                missingRegions.remove(rKey);
            } else {
                return 0;
            }
        }

        int lockIndex = (int) ((cKey ^ (cKey >>> 8) ^ (cKey >>> 16)) & 0xFF);
        synchronized (chunkLocks[lockIndex]) {
            if (columnCache.containsKey(cKey)) {
                return 0;
            }

            McaRegionReader reader = getOrOpenRegion(rx, rz);
            if (reader == null) {
                return 0;
            }
            updateBounds(rx, rz);

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
                putColumn(cKey, column);
                int slot = (int) ((cKey ^ (cKey >>> 16) ^ (cKey >>> 32)) & L1_MASK);
                l1Columns[slot] = column;
                l1Keys[slot] = cKey;
                loadedChunks.add(cKey);
                short colH = column.getHeightmap().getHighestY();
                regionHeightmaps.computeIfAbsent(rKey, k -> new com.pixel.qve.world.RegionHeightmap2D())
                        .updateMax(localCx, localCz, colH);
            }
            return parsed;
        }
    }

    private void putColumn(long cKey, VoxelChunkColumn column) {
        columnCache.put(cKey, column);
        columnEvictionQueue.offer(cKey);
        while (columnCache.size() > maxCachedColumns) {
            Long oldest = columnEvictionQueue.poll();
            if (oldest != null) {
                columnCache.remove(oldest);
                loadedChunks.remove(oldest);
            } else {
                break;
            }
        }
    }

    /**
     * Invalidates a cached chunk column and refreshes the underlying region reader
     * after external or direct disk writes.
     *
     * @param chunkX World chunk X
     * @param chunkZ World chunk Z
     */
    public void invalidateChunk(int chunkX, int chunkZ) {
        long cKey = chunkKey(chunkX, chunkZ);
        columnCache.remove(cKey);
        loadedChunks.remove(cKey);

        int slot = (int) ((cKey ^ (cKey >>> 16) ^ (cKey >>> 32)) & L1_MASK);
        if (l1Keys[slot] == cKey) {
            l1Columns[slot] = null;
            l1Keys[slot] = Long.MIN_VALUE;
        }

        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        long rKey = regionKey(rx, rz);
        missingRegions.remove(rKey);
        regionHeightmaps.remove(rKey);

        McaRegionReader reader = regions.get(rKey);
        if (reader != null) {
            reader.refreshHeaderIfPossible();
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
        columnEvictionQueue.clear();
        Arrays.fill(l1Keys, Long.MIN_VALUE);
        Arrays.fill(l1Columns, null);
        loadedChunks.clear();
        missingRegions.clear();
        existingRegions.clear();
        regionHeightmaps.clear();
        highestWorldY = Short.MIN_VALUE;
    }

    /**
     * Resolves an open McaRegionReader for the given region coordinates, opening it on demand if necessary.
     * Enforces a bounded active region pool via LRU eviction to prevent file handle exhaustion.
     *
     * @param rx Region X coordinate
     * @param rz Region Z coordinate
     * @return McaRegionReader, or null if region file is absent
     */
    public McaRegionReader getOrOpenRegion(int rx, int rz) {
        long rKey = regionKey(rx, rz);
        McaRegionReader reader = regions.get(rKey);
        if (reader != null) {
            return reader;
        }

        if (missingRegions.contains(rKey)) {
            return null;
        }

        if (regionDirectory != null) {
            Path mcaFile = regionDirectory.resolve("r." + rx + "." + rz + ".mca");
            if (existingRegions.contains(rKey) || java.nio.file.Files.exists(mcaFile)) {
                existingRegions.add(rKey);
                try {
                    McaRegionReader newReader = new McaRegionReader(mcaFile, registry, shapeRegistry);
                    McaRegionReader existing = regions.putIfAbsent(rKey, newReader);
                    if (existing != null) {
                        try {
                            newReader.close();
                        } catch (IOException e) {
                            LOGGER.log(System.Logger.Level.DEBUG, "Failed to close duplicate MCA reader: {0}", e.getMessage());
                        }
                        return existing;
                    }
                    regionEvictionQueue.offer(rKey);
                    while (regions.size() > maxOpenRegions) {
                        Long oldest = regionEvictionQueue.poll();
                        if (oldest != null && !oldest.equals(rKey)) {
                            McaRegionReader evicted = regions.remove(oldest);
                            if (evicted != null) {
                                try {
                                    evicted.close();
                                } catch (IOException e) {
                                    LOGGER.log(System.Logger.Level.WARNING, "Error closing evicted region reader {0}: {1}", oldest, e.getMessage());
                                }
                            }
                        } else if (oldest != null) {
                            regionEvictionQueue.offer(oldest);
                            break;
                        }
                    }
                    return newReader;
                } catch (IOException e) {
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Failed to open MCA region file {0}: {1}", mcaFile, e.getMessage());
                    return null;
                }
            }
        }
        return null;
    }

    @Override
    public INbtService getNbtService() {
        return nbtFetcher;
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
        regionEvictionQueue.clear();
        clearCache();
    }
}
