package com.pixel.qve.neoforge.bridge;

import com.pixel.qve.api.IVoxelGrid;


import com.pixel.qve.api.IVoxelWorld;
import com.pixel.qve.world.cache.UnifiedVoxelCache;
import com.pixel.qve.world.VoxelChunkColumn;
import com.pixel.qve.state.ShapeRegistry;
import com.pixel.qve.world.Heightmap2D;
import com.pixel.qve.world.VoxelSection;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

import com.pixel.qve.mca.writer.PrimitiveMutationBuffer;
import com.pixel.qve.neoforge.api.ChunkWriteBatch;
import com.pixel.qve.neoforge.bridge.writer.DeferredChunkQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Live Minecraft IVoxelGrid implementation backed by UnifiedVoxelCache and on-demand
 * section compilation. Seamlessly unites live chunks in RAM and offline chunks on disk.
 */
public final class MinecraftVoxelGrid implements IVoxelGrid, AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(MinecraftVoxelGrid.class.getName());

    private final Level level;
    private final UnifiedVoxelCache cache;

    /**
     * Constructs a MinecraftVoxelGrid binding a live Level to a UnifiedVoxelCache.
     *
     * @param level Live Minecraft level
     * @param cache Backing spatial cache
     */
    public MinecraftVoxelGrid(Level level, UnifiedVoxelCache cache) {
        this.level = Objects.requireNonNull(level, "Level cannot be null");
        this.cache = Objects.requireNonNull(cache, "UnifiedVoxelCache cannot be null");
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
     * Retrieves the backing UnifiedVoxelCache.
     *
     * @return UnifiedVoxelCache instance
     */
    public UnifiedVoxelCache getCache() {
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
            if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
                try {
                    net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc != null && mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
                        ServerLevel serverLevel = mc.getSingleplayerServer().getLevel(level.dimension());
                        if (serverLevel != null) {
                            return serverLevel.getChunkSource().getChunkNow(chunkX, chunkZ);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    @Override
    public VoxelSection getSection(int sectionX, int sectionY, int sectionZ) {
        // 1. Check in-memory UnifiedVoxelCache columns first (lock-free)
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
        IVoxelWorld diskFallback = cache.getDiskFallback();
        VoxelSection diskSection = null;
        if (diskFallback != null) {
            diskSection = diskFallback.getSection(sectionX, sectionY, sectionZ);
        }

        // Overlay deferred mutations from DeferredChunkQueue if present
        if (DeferredChunkQueue.hasEdits(level, sectionX, sectionZ)) {
            diskSection = overlayDeferredEdits(sectionX, sectionY, sectionZ, diskSection);
        }

        if (diskSection != null) {
            cache.putSection(sectionX, sectionY, sectionZ, diskSection);
            return diskSection;
        }

        return null;
    }

    private VoxelSection overlayDeferredEdits(int sectionX, int sectionY, int sectionZ, VoxelSection baseSection) {
        ChunkWriteBatch.ChunkEdits edits = DeferredChunkQueue.peekEdits(level, sectionX, sectionZ);
        if (edits == null) {
            return baseSection;
        }

        VoxelSection whole = edits.getWholeSections().get(sectionY);
        if (whole != null) {
            return whole;
        }

        PrimitiveMutationBuffer pmb = edits.getMutationBuffer();
        if (pmb != null && !pmb.isEmpty()) {
            int bit = sectionY + 16;
            if (bit >= 0 && bit < 32 && (pmb.getModifiedSectionMask() & (1 << bit)) == 0) {
                return baseSection;
            }
            VoxelSection copy = (baseSection != null) ? baseSection.copy() : new VoxelSection();
            int secMinY = sectionY << 4;
            int secMaxY = secMinY + 15;
            for (int i = 0, size = pmb.size(); i < size; i++) {
                if (pmb.intersectsSection(i, sectionY)) {
                    int bMinX = pmb.minX(i);
                    int bMaxX = pmb.maxX(i);
                    int bMinZ = pmb.minZ(i);
                    int bMaxZ = pmb.maxZ(i);
                    int bMinY = Math.max(secMinY, pmb.minY(i));
                    int bMaxY = Math.min(secMaxY, pmb.maxY(i));
                    int targetId = pmb.targetBlockId(i);
                    int filterId = pmb.filterBlockId(i);
                    int localMinY = bMinY & 15;
                    int localMaxY = bMaxY & 15;

                    for (int y = localMinY; y <= localMaxY; y++) {
                        for (int z = bMinZ; z <= bMaxZ; z++) {
                            for (int x = bMinX; x <= bMaxX; x++) {
                                int curId = copy.getBlockId(x, y, z);
                                if (filterId < 0 || curId == filterId) {
                                    copy.setVoxel(x, y, z, targetId != 0, targetId);
                                }
                            }
                        }
                    }
                }
            }
            return copy;
        }

        List<ChunkWriteBatch.BlockMutation> mutations = edits.getMutations();
        boolean hasAny = false;
        for (int i = 0, size = mutations.size(); i < size; i++) {
            if ((mutations.get(i).worldY() >> 4) == sectionY) {
                hasAny = true;
                break;
            }
        }

        if (!hasAny) {
            return baseSection;
        }

        VoxelSection copy = (baseSection != null) ? baseSection.copy() : new VoxelSection();
        for (int i = 0, size = mutations.size(); i < size; i++) {
            ChunkWriteBatch.BlockMutation m = mutations.get(i);
            if ((m.worldY() >> 4) == sectionY) {
                int lx = m.worldX() & 15;
                int ly = m.worldY() & 15;
                int lz = m.worldZ() & 15;
                int curId = copy.getBlockId(lx, ly, lz);
                if (m.matchesFilter(curId, null)) {
                    copy.setVoxel(lx, ly, lz, m.targetBlockId() != 0, m.targetBlockId());
                }
            }
        }
        return copy;
    }

    @Override
    public VoxelChunkColumn getColumn(int chunkX, int chunkZ) {
        VoxelChunkColumn col = cache.getColumn(chunkX, chunkZ);
        if (col != null) {
            return col;
        }

        LevelChunk chunk = getChunkSafe(chunkX, chunkZ);
        if (chunk != null) {
            return ingestChunk(chunk);
        }

        IVoxelWorld diskFallback = cache.getDiskFallback();
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
                    int h = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
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
        short highest = colHm.getHighestY();
        if (highest != Heightmap2D.VOID_Y) {
            cache.updateChunkHeightmap(chunkX, chunkZ, highest);
        }
    }

    /**
     * Ingests a live Minecraft LevelChunk directly into the UnifiedVoxelCache,
     * populating the heightmap and compiling all non-empty sections.
     *
     * @param chunk Live LevelChunk
     * @return The populated VoxelChunkColumn, or null if chunk is null
     */
    public VoxelChunkColumn ingestChunk(LevelChunk chunk) {
        if (chunk == null) {
            return null;
        }
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        VoxelChunkColumn newCol = cache.getOrCreateColumn(chunkX, chunkZ);
        populateHeightmapIfEmpty(chunk, newCol, chunkX, chunkZ);

        int minSecY = level.getMinSection();
        int maxSecY = level.getMaxSection();
        LevelChunkSection[] sections = chunk.getSections();
        for (int sy = minSecY; sy <= maxSecY; sy++) {
            int blockY = sy << 4;
            int secIdx = chunk.getSectionIndex(blockY);
            if (secIdx >= 0 && secIdx < sections.length) {
                LevelChunkSection sec = sections[secIdx];
                if (sec != null && !sec.hasOnlyAir()) {
                    MinecraftVoxelBridge.compileSection(sec, newCol, sy);
                }
            }
        }
        return newCol;
    }

    @Override
    public com.pixel.qve.world.RegionHeightmap2D getRegionHeightmap(int regionX, int regionZ) {
        return cache.getRegionHeightmap(regionX, regionZ);
    }

    @Override
    public short getRegionMaxY(int regionX, int regionZ) {
        return cache.getRegionMaxY(regionX, regionZ);
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

        IVoxelWorld diskFallback = cache.getDiskFallback();
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
        if (cache.hasWorldBounds()) {
            return cache.isOutOfBounds(worldBlockX, worldBlockZ, stepX, stepZ);
        }
        if (level instanceof ServerLevel) {
            PlayerBounds b = getOrComputePlayerBounds();
            if (stepX > 0 && worldBlockX > b.maxX) return true;
            if (stepX < 0 && worldBlockX < b.minX) return true;
            if (stepZ > 0 && worldBlockZ > b.maxZ) return true;
            if (stepZ < 0 && worldBlockZ < b.minZ) return true;
        }
        return false;
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
        if (cache.hasWorldBounds()) {
            int diskMin = cache.getWorldMinX();
            if (level instanceof ServerLevel) {
                PlayerBounds b = getOrComputePlayerBounds();
                return Math.min(diskMin, b.minX);
            }
            return diskMin;
        } else if (level instanceof ServerLevel) {
            return getOrComputePlayerBounds().minX;
        }
        return Integer.MIN_VALUE;
    }

    @Override
    public int getWorldMaxX() {
        if (cache.hasWorldBounds()) {
            int diskMax = cache.getWorldMaxX();
            if (level instanceof ServerLevel) {
                PlayerBounds b = getOrComputePlayerBounds();
                return Math.max(diskMax, b.maxX);
            }
            return diskMax;
        } else if (level instanceof ServerLevel) {
            return getOrComputePlayerBounds().maxX;
        }
        return Integer.MAX_VALUE;
    }

    @Override
    public int getWorldMinZ() {
        if (cache.hasWorldBounds()) {
            int diskMin = cache.getWorldMinZ();
            if (level instanceof ServerLevel) {
                PlayerBounds b = getOrComputePlayerBounds();
                return Math.min(diskMin, b.minZ);
            }
            return diskMin;
        } else if (level instanceof ServerLevel) {
            return getOrComputePlayerBounds().minZ;
        }
        return Integer.MIN_VALUE;
    }

    @Override
    public int getWorldMaxZ() {
        if (cache.hasWorldBounds()) {
            int diskMax = cache.getWorldMaxZ();
            if (level instanceof ServerLevel) {
                PlayerBounds b = getOrComputePlayerBounds();
                return Math.max(diskMax, b.maxZ);
            }
            return diskMax;
        } else if (level instanceof ServerLevel) {
            return getOrComputePlayerBounds().maxZ;
        }
        return Integer.MAX_VALUE;
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

    @Override
    public com.pixel.qve.state.BlockTraitRegistry getTraitRegistry() {
        return cache.getTraitRegistry();
    }

    @Override
    public com.pixel.qve.api.nbt.INbtService getNbtService() {
        return new com.pixel.qve.api.nbt.INbtService() {
            @Override
            public java.nio.ByteBuffer getBlockEntityRawNbt(int worldX, int worldY, int worldZ) {
                int chunkX = worldX >> 4;
                int chunkZ = worldZ >> 4;
                LevelChunk chunk = getChunkSafe(chunkX, chunkZ);
                if (chunk != null) {
                    net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(worldX, worldY, worldZ);
                    net.minecraft.world.level.block.entity.BlockEntity be = chunk.getBlockEntity(pos);
                    if (be != null) {
                        net.minecraft.nbt.CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
                        try {
                            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                            net.minecraft.nbt.NbtIo.write(tag, new java.io.DataOutputStream(baos));
                            return java.nio.ByteBuffer.wrap(baos.toByteArray());
                        } catch (java.io.IOException e) {
                            LOGGER.log(System.Logger.Level.WARNING, "Failed to serialize BlockEntity NBT at (" + worldX + ", " + worldY + ", " + worldZ + ")", e);
                            return null;
                        }
                    }
                    return null;
                }

                IVoxelWorld disk = cache.getDiskFallback();
                if (disk != null && disk.getNbtService() != null) {
                    return disk.getNbtService().getBlockEntityRawNbt(worldX, worldY, worldZ);
                }
                return null;
            }

            @Override
            public java.util.Map<String, Object> getBlockEntityData(int worldX, int worldY, int worldZ) {
                java.nio.ByteBuffer raw = getBlockEntityRawNbt(worldX, worldY, worldZ);
                if (raw != null) {
                    return com.pixel.qve.mca.FastNbtReader.parseCompound(raw);
                }
                return null;
            }
        };
    }

    /**
     * Resolves the Minecraft CompoundTag for a block entity at the given BlockPos.
     * Checks live RAM chunk first; if unloaded, fetches directly from Anvil MCA on disk.
     *
     * @param pos Block position
     * @return CompoundTag, or null if absent or unloaded
     */
    public net.minecraft.nbt.CompoundTag getBlockEntityCompoundTag(net.minecraft.core.BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        LevelChunk chunk = getChunkSafe(chunkX, chunkZ);
        if (chunk != null) {
            net.minecraft.world.level.block.entity.BlockEntity be = chunk.getBlockEntity(pos);
            if (be != null) {
                return be.saveWithFullMetadata(level.registryAccess());
            }
            return null;
        }

        IVoxelWorld disk = cache.getDiskFallback();
        if (disk != null && disk.getNbtService() != null) {
            java.nio.ByteBuffer raw = disk.getNbtService().getBlockEntityRawNbt(pos.getX(), pos.getY(), pos.getZ());
            if (raw != null) {
                try {
                    byte[] bytes = new byte[raw.remaining()];
                    raw.get(bytes);
                    return net.minecraft.nbt.NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes)));
                } catch (java.io.IOException e) {
                    LOGGER.log(System.Logger.Level.WARNING, "Failed to parse NBT CompoundTag for block entity at " + pos, e);
                    return null;
                }
            }
        }
        return null;
    }

    @Override
    public void close() {
        cache.close();
    }
}
