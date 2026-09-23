package com.pixel.raycast.core.mca;

import com.pixel.raycast.core.api.IVoxelGrid;
import com.pixel.raycast.core.voxel.BlockIdRegistry;
import com.pixel.raycast.core.voxel.VoxelSection;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Universal voxel world provider backed by one or more Minecraft Anvil (.mca) region files.
 * Provides on-demand chunk loading and ultra-fast section lookup for 3D DDA raycasting.
 */
public final class McaVoxelGrid implements IVoxelGrid {

    private static final System.Logger LOGGER = System.getLogger(McaVoxelGrid.class.getName());

    private final BlockIdRegistry registry;
    private final Map<Long, VoxelSection> sectionCache = new ConcurrentHashMap<>();
    private final Map<Long, com.pixel.raycast.core.voxel.Heightmap2D> heightmaps = new ConcurrentHashMap<>();
    private final Map<Long, McaRegionReader> regions = new ConcurrentHashMap<>();
    private final Path regionDirectory;
    private short highestWorldY = Short.MIN_VALUE;

    /**
     * Constructs an McaVoxelGrid with an in-memory block registry.
     *
     * @param registry BlockIdRegistry to map block state names
     */
    public McaVoxelGrid(BlockIdRegistry registry) {
        this(registry, null);
    }

    /**
     * Constructs an McaVoxelGrid with an optional on-disk region directory for lazy-loading.
     *
     * @param registry        BlockIdRegistry to map block state names
     * @param regionDirectory Root directory containing .mca region files, or null
     */
    public McaVoxelGrid(BlockIdRegistry registry, Path regionDirectory) {
        this.registry = Objects.requireNonNull(registry, "BlockIdRegistry cannot be null");
        this.regionDirectory = regionDirectory;
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
    public com.pixel.raycast.core.voxel.Heightmap2D getHeightmap(int chunkX, int chunkZ) {
        return heightmaps.get(chunkKey(chunkX, chunkZ));
    }

    @Override
    public short getHighestWorldY() {
        return highestWorldY;
    }

    private final com.pixel.raycast.core.shape.ShapeRegistry shapeRegistry = new com.pixel.raycast.core.shape.ShapeRegistry();

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
                if (!reader.hasChunk(cx, cz)) continue;
                int worldCx = (rx << 5) | cx;
                int worldCz = (rz << 5) | cz;

                int loaded = reader.readChunk(cx, cz, (sectionY, section) -> {
                    long key = sectionKey(worldCx, sectionY, worldCz);
                    sectionCache.put(key, section);
                    if (section != null && !section.isEmpty()) {
                        long ck = chunkKey(worldCx, worldCz);
                        com.pixel.raycast.core.voxel.Heightmap2D hm = heightmaps.computeIfAbsent(ck, k -> new com.pixel.raycast.core.voxel.Heightmap2D());
                        hm.updateFromSection(sectionY, section);
                        short h = hm.getHighestY();
                        if (h > highestWorldY) {
                            highestWorldY = h;
                        }
                    }
                });
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
        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        long rKey = regionKey(rx, rz);
        McaRegionReader reader = regions.get(rKey);
        if (reader == null && regionDirectory != null) {
            Path mcaFile = regionDirectory.resolve("r." + rx + "." + rz + ".mca");
            if (java.nio.file.Files.exists(mcaFile)) {
                reader = new McaRegionReader(mcaFile, registry);
                regions.put(rKey, reader);
            }
        }
        if (reader == null) {
            return 0;
        }

        int localCx = chunkX & 31;
        int localCz = chunkZ & 31;
        return reader.readChunk(localCx, localCz, (sectionY, section) -> {
            long key = sectionKey(chunkX, sectionY, chunkZ);
            sectionCache.put(key, section);
            if (section != null && !section.isEmpty()) {
                long ck = chunkKey(chunkX, chunkZ);
                com.pixel.raycast.core.voxel.Heightmap2D hm = heightmaps.computeIfAbsent(ck, k -> new com.pixel.raycast.core.voxel.Heightmap2D());
                hm.updateFromSection(sectionY, section);
                short h = hm.getHighestY();
                if (h > highestWorldY) {
                    highestWorldY = h;
                }
            }
        });
    }

    @Override
    public VoxelSection getSection(int sectionX, int sectionY, int sectionZ) {
        long key = sectionKey(sectionX, sectionY, sectionZ);
        VoxelSection cached = sectionCache.get(key);
        if (cached != null) {
            return cached;
        }

        // Lazy-load chunk if region is registered or regionDirectory is configured
        int rx = sectionX >> 5;
        int rz = sectionZ >> 5;
        long rKey = regionKey(rx, rz);
        if (regions.containsKey(rKey) || regionDirectory != null) {
            try {
                loadChunk(sectionX, sectionZ);
                return sectionCache.get(key);
            } catch (Exception e) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Failed to lazy-load chunk ({0}, {1}) from region r.{2}.{3}.mca: {4}",
                        sectionX, sectionZ, rx, rz, e.getMessage());
                return null;
            }
        }

        return null;
    }

    /**
     * Gets the total number of non-empty voxel sections currently in memory.
     *
     * @return Cached section count
     */
    public int getCachedSectionCount() {
        return sectionCache.size();
    }

    /**
     * Clears all cached voxel sections from memory.
     */
    public void clearCache() {
        sectionCache.clear();
    }
}
