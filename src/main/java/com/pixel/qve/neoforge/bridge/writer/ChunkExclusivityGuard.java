package com.pixel.qve.neoforge.bridge.writer;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Strict safety guard enforcing mutual exclusion between live RAM chunks and direct Anvil disk writes.
 * Prevents race conditions, data loss, and file corruption by verifying that target chunks are NOT
 * currently loaded or being saved by Minecraft's internal chunk pipeline.
 */
public final class ChunkExclusivityGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkExclusivityGuard.class);

    private ChunkExclusivityGuard() {}

    /**
     * Checks if a chunk is currently loaded in memory (RAM).
     *
     * @param level  Minecraft Level
     * @param chunkX Chunk X
     * @param chunkZ Chunk Z
     * @return True if chunk is loaded in RAM
     */
    public static boolean isChunkLoadedInRam(Level level, int chunkX, int chunkZ) {
        if (level == null) return false;

        if (level instanceof ServerLevel serverLevel) {
            ServerChunkCache scc = serverLevel.getChunkSource();
            if (scc == null) {
                return false;
            }
            // 1. Direct check on Server Thread: getChunkNow returns active LevelChunk or null
            if (scc.getChunkNow(chunkX, chunkZ) != null) {
                return true;
            }

            // 2. Thread-safe check: inspect ChunkMap for visible, updating, or pending unload chunks
            ChunkMap chunkMap = scc.chunkMap;
            if (chunkMap != null) {
                long posLong = ChunkPos.asLong(chunkX, chunkZ);
                net.minecraft.server.level.ChunkHolder holder = chunkMap.getVisibleChunkIfPresent(posLong);
                if (holder != null) {
                    if (holder.getTickingChunk() != null || holder.getChunkToSend() != null) {
                        return true;
                    }
                    if (holder.getFullChunkFuture().getNow(net.minecraft.server.level.ChunkHolder.UNLOADED_LEVEL_CHUNK).isSuccess()) {
                        return true;
                    }
                }

                try {
                    com.pixel.qve.neoforge.mixin.ChunkMapAccessor accessor = (com.pixel.qve.neoforge.mixin.ChunkMapAccessor) chunkMap;
                    var updating = accessor.qve$getUpdatingChunkMap();
                    if (updating != null && updating.containsKey(posLong)) {
                        return true;
                    }
                    var unloads = accessor.qve$getPendingUnloads();
                    if (unloads != null && unloads.containsKey(posLong)) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
            return false;
        }

        // Client side singleplayer check
        if (level.isClientSide() && net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            try {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc != null && mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
                    ServerLevel serverLevel = mc.getSingleplayerServer().getLevel(level.dimension());
                    if (serverLevel != null) {
                        return isChunkLoadedInRam(serverLevel, chunkX, chunkZ);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        return false;
    }

    /**
     * Checks if any chunk in the region (rx, rz) is currently loaded in RAM or if Minecraft's
     * RegionFileStorage holds an active open handle for r.rx.rz.mca.
     * If true, this region is in a hybrid/active state and direct MCA disk writes are strictly prohibited.
     *
     * @param level Minecraft Level
     * @param rx    Region X (chunkX >> 5)
     * @param rz    Region Z (chunkZ >> 5)
     * @return True if region has active chunks or open RegionFile handle
     */
    public static boolean isRegionActiveInRam(Level level, int rx, int rz) {
        if (level == null) return false;

        if (level instanceof ServerLevel serverLevel) {
            ServerChunkCache scc = serverLevel.getChunkSource();
            if (scc == null) return false;

            ChunkMap chunkMap = scc.chunkMap;
            if (chunkMap == null) return false;

            // 1. Check if Minecraft's RegionFileStorage currently has an active open handle for this region
            try {
                net.minecraft.world.level.chunk.storage.IOWorker worker = ((com.pixel.qve.neoforge.mixin.ChunkStorageAccessor) chunkMap).qve$getWorker();
                if (worker != null) {
                    net.minecraft.world.level.chunk.storage.RegionFileStorage storage = ((com.pixel.qve.neoforge.mixin.IOWorkerAccessor) worker).qve$getStorage();
                    if (storage != null) {
                        var cache = ((com.pixel.qve.neoforge.mixin.RegionFileStorageAccessor) (Object) storage).qve$getRegionCache();
                        if (cache != null) {
                            long rKey = ChunkPos.asLong(rx, rz);
                            synchronized (cache) {
                                if (cache.containsKey(rKey)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // 2. Check if any chunk tracked in visibleChunkMap, updatingChunkMap, or pendingUnloads falls in this region
            try {
                com.pixel.qve.neoforge.mixin.ChunkMapAccessor accessor = (com.pixel.qve.neoforge.mixin.ChunkMapAccessor) chunkMap;
                var visible = accessor.qve$getVisibleChunkMap();
                if (visible != null && !visible.isEmpty()) {
                    for (long key : visible.keySet()) {
                        int cx = ChunkPos.getX(key);
                        int cz = ChunkPos.getZ(key);
                        if ((cx >> 5) == rx && (cz >> 5) == rz) {
                            return true;
                        }
                    }
                }
                var updating = accessor.qve$getUpdatingChunkMap();
                if (updating != null && !updating.isEmpty()) {
                    for (long key : updating.keySet()) {
                        int cx = ChunkPos.getX(key);
                        int cz = ChunkPos.getZ(key);
                        if ((cx >> 5) == rx && (cz >> 5) == rz) {
                            return true;
                        }
                    }
                }
                var unloads = accessor.qve$getPendingUnloads();
                if (unloads != null && !unloads.isEmpty()) {
                    for (long key : unloads.keySet()) {
                        int cx = ChunkPos.getX(key);
                        int cz = ChunkPos.getZ(key);
                        if ((cx >> 5) == rx && (cz >> 5) == rz) {
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            return false;
        }

        return false;
    }

    /**
     * Checks if a chunk is completely safe for direct Anvil (.mca) disk writing.
     * Requires the chunk to be strictly UNLOADED in RAM.
     *
     * @param level  Minecraft Level
     * @param chunkX Chunk X
     * @param chunkZ Chunk Z
     * @return True if safe for direct disk writing
     */
    public static boolean isSafeForDirectDiskWrite(Level level, int chunkX, int chunkZ) {
        if (level == null) return false;
        return !isChunkLoadedInRam(level, chunkX, chunkZ);
    }

    /**
     * Asserts that a chunk is strictly safe for direct Anvil disk writing.
     * Throws ChunkLoadedInRamException if chunk is resident in RAM.
     *
     * @param level  Minecraft Level
     * @param chunkX Chunk X
     * @param chunkZ Chunk Z
     * @throws ChunkLoadedInRamException If chunk is in RAM
     */
    public static void assertSafeForDirectDiskWrite(Level level, int chunkX, int chunkZ) {
        if (isChunkLoadedInRam(level, chunkX, chunkZ)) {
            String dimName = (level.dimension() != null && level.dimension().location() != null)
                    ? level.dimension().location().toString()
                    : "unknown";
            String msg = String.format("Direct MCA disk write rejected: chunk (%d, %d) in dimension %s is currently LOADED in RAM!",
                    chunkX, chunkZ, dimName);
            LOGGER.error(msg);
            throw new ChunkLoadedInRamException(msg);
        }
    }

    /**
     * Exception thrown when attempting strict direct MCA write on a chunk loaded in RAM.
     */
    public static class ChunkLoadedInRamException extends IllegalStateException {
        public ChunkLoadedInRamException(String message) {
            super(message);
        }
    }
}
