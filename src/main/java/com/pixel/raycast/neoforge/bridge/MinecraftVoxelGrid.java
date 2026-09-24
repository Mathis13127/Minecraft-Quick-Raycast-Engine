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
            VoxelChunkColumn newCol = cache.getOrCreateColumn(chunkX, chunkZ);
            populateHeightmapIfEmpty(chunk, newCol, chunkX, chunkZ);
            return newCol;
        }

        IVoxelGrid diskFallback = cache.getDiskFallback();
        if (diskFallback != null) {
            return diskFallback.getColumn(chunkX, chunkZ);
        }

        return null;
    }

    private void populateHeightmapIfEmpty(LevelChunk chunk, VoxelChunkColumn col, int chunkX, int chunkZ) {
        Heightmap2D colHm = col.getHeightmap();
        if (colHm.getHighestY() == Heightmap2D.VOID_Y) {
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
            populateHeightmapIfEmpty(chunk, col, chunkX, chunkZ);
            return col.getHeightmap();
        }

        IVoxelGrid diskFallback = cache.getDiskFallback();
        if (diskFallback != null) {
            return diskFallback.getHeightmap(chunkX, chunkZ);
        }

        return null;
    }

    @Override
    public boolean isRegionEmpty(int regionX, int regionZ) {
        if (hasLiveChunksInRegion(regionX, regionZ)) {
            return false;
        }
        return cache.isRegionEmpty(regionX, regionZ);
    }

    @Override
    public boolean isOutOfBounds(int worldBlockX, int worldBlockZ, int stepX, int stepZ) {
        if (isNearLiveChunks(worldBlockX, worldBlockZ)) {
            return false;
        }
        return cache.isOutOfBounds(worldBlockX, worldBlockZ, stepX, stepZ);
    }

    private static final class PlayerBounds {
        final int minX, maxX, minZ, maxZ;
        final long gameTime;

        PlayerBounds(int minX, int maxX, int minZ, int maxZ, long gameTime) {
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.gameTime = gameTime;
        }
    }

    private volatile PlayerBounds cachedPlayerBounds = new PlayerBounds(0, 0, 0, 0, -1);

    private PlayerBounds getOrComputePlayerBounds() {
        if (!(level instanceof ServerLevel sl)) {
            return cachedPlayerBounds;
        }
        long currentTime = sl.getGameTime();
        PlayerBounds current = cachedPlayerBounds;
        if (current.gameTime == currentTime) {
            return current;
        }

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (net.minecraft.server.level.ServerPlayer player : sl.players()) {
            int px = player.getBlockX();
            int pz = player.getBlockZ();
            if (px < minX) minX = px;
            if (px > maxX) maxX = px;
            if (pz < minZ) minZ = pz;
            if (pz > maxZ) maxZ = pz;
        }

        net.minecraft.core.BlockPos spawn = sl.getSharedSpawnPos();
        int sx = spawn.getX();
        int sz = spawn.getZ();
        if (sx < minX) minX = sx;
        if (sx > maxX) maxX = sx;
        if (sz < minZ) minZ = sz;
        if (sz > maxZ) maxZ = sz;

        // Expand bounds by 1024 blocks (simulation/render distance buffer)
        PlayerBounds updated = new PlayerBounds(minX - 1024, maxX + 1024, minZ - 1024, maxZ + 1024, currentTime);
        cachedPlayerBounds = updated;
        return updated;
    }

    private boolean hasLiveChunksInRegion(int regionX, int regionZ) {
        if (level instanceof ServerLevel) {
            PlayerBounds b = getOrComputePlayerBounds();
            int regMinX = regionX << 9;
            int regMaxX = (regionX + 1) << 9;
            int regMinZ = regionZ << 9;
            int regMaxZ = (regionZ + 1) << 9;
            return !(regMaxX <= b.minX || regMinX >= b.maxX || regMaxZ <= b.minZ || regMinZ >= b.maxZ);
        }
        return false;
    }

    private boolean isNearLiveChunks(int worldBlockX, int worldBlockZ) {
        if (level instanceof ServerLevel) {
            PlayerBounds b = getOrComputePlayerBounds();
            return worldBlockX >= b.minX && worldBlockX <= b.maxX &&
                   worldBlockZ >= b.minZ && worldBlockZ <= b.maxZ;
        }
        return false;
    }

    @Override
    public boolean hasWorldBounds() {
        return (level instanceof ServerLevel) || cache.hasWorldBounds();
    }

    @Override
    public int getWorldMinX() {
        int diskMin = cache.getWorldMinX();
        if (level instanceof ServerLevel) {
            PlayerBounds b = getOrComputePlayerBounds();
            return Math.min(diskMin, b.minX);
        }
        return diskMin;
    }

    @Override
    public int getWorldMaxX() {
        int diskMax = cache.getWorldMaxX();
        if (level instanceof ServerLevel) {
            PlayerBounds b = getOrComputePlayerBounds();
            return Math.max(diskMax, b.maxX);
        }
        return diskMax;
    }

    @Override
    public int getWorldMinZ() {
        int diskMin = cache.getWorldMinZ();
        if (level instanceof ServerLevel) {
            PlayerBounds b = getOrComputePlayerBounds();
            return Math.min(diskMin, b.minZ);
        }
        return diskMin;
    }

    @Override
    public int getWorldMaxZ() {
        int diskMax = cache.getWorldMaxZ();
        if (level instanceof ServerLevel) {
            PlayerBounds b = getOrComputePlayerBounds();
            return Math.max(diskMax, b.maxZ);
        }
        return diskMax;
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
