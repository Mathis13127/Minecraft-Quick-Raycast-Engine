package com.pixel.qve.neoforge.bridge.writer;

import com.pixel.qve.neoforge.mixin.ChunkMapAccessor;
import com.pixel.qve.neoforge.mixin.ChunkStorageAccessor;
import com.pixel.qve.neoforge.mixin.IOWorkerAccessor;
import com.pixel.qve.neoforge.mixin.RegionFileStorageAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * High-reliability bridge synchronizing QVE direct disk writes with Minecraft's internal {@link RegionFileStorage}.
 * Evicts cached {@link RegionFile} handles, drains pending unloads, and flushes pending writes to prevent stale
 * in-memory sector offset desync and file handle contention.
 */
public final class MinecraftRegionFileBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftRegionFileBridge.class);
    private static final ReentrantLock EVICTION_LOCK = new ReentrantLock();

    private MinecraftRegionFileBridge() {}

    /**
     * Checks if Minecraft's RegionFileStorage currently has a cached open handle for region (rx, rz).
     * Fast check that inspects the cache map directly.
     *
     * @param serverLevel Minecraft ServerLevel
     * @param rx          Region X coordinate
     * @param rz          Region Z coordinate
     * @return True if region handle is cached in memory
     */
    public static boolean isRegionCached(ServerLevel serverLevel, int rx, int rz) {
        if (serverLevel == null) return false;
        try {
            ChunkMap chunkMap = serverLevel.getChunkSource().chunkMap;
            if (chunkMap == null) return false;
            IOWorker worker = ((ChunkStorageAccessor) chunkMap).qve$getWorker();
            if (worker == null) return false;
            RegionFileStorage storage = ((IOWorkerAccessor) worker).qve$getStorage();
            if (storage == null) return false;
            Long2ObjectLinkedOpenHashMap<RegionFile> cache = ((RegionFileStorageAccessor) (Object) storage).qve$getRegionCache();
            if (cache == null) return false;
            synchronized (cache) {
                return cache.containsKey(ChunkPos.asLong(rx, rz));
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Synchronizes all pending writes in Minecraft's {@link IOWorker}, then closes and evicts the cached
     * {@link RegionFile} for region (rx, rz) from Minecraft's {@link RegionFileStorage}.
     *
     * @param serverLevel Minecraft ServerLevel
     * @param rx          Region X coordinate
     * @param rz          Region Z coordinate
     * @return True if synchronization and eviction completed without error
     */
    public static boolean evictAndFlushRegion(ServerLevel serverLevel, int rx, int rz) {
        if (serverLevel == null) {
            return false;
        }
        return evictAndFlushRegions(serverLevel, List.of(ChunkPos.asLong(rx, rz)));
    }

    /**
     * Synchronizes and evicts multiple regions simultaneously under a global mutual exclusion lock.
     * Drains Minecraft's pending unloads for the targeted regions, flushes pending storage writes,
     * and evicts all targeted region files from cache cleanly to eliminate race conditions.
     *
     * @param serverLevel Minecraft ServerLevel
     * @param regionKeys  Collection of packed region keys (from ChunkPos.asLong(rx, rz))
     * @return True if all evictions completed successfully
     */
    public static boolean evictAndFlushRegions(ServerLevel serverLevel, Collection<Long> regionKeys) {
        if (serverLevel == null || regionKeys == null || regionKeys.isEmpty()) {
            return false;
        }

        EVICTION_LOCK.lock();
        try {
            ChunkMap chunkMap = serverLevel.getChunkSource().chunkMap;
            if (chunkMap == null) {
                return false;
            }

            IOWorker worker = ((ChunkStorageAccessor) chunkMap).qve$getWorker();
            if (worker == null) {
                return false;
            }

            // 1. Drain pending unloads for the targeted regions
            try {
                ChunkMapAccessor accessor = (ChunkMapAccessor) chunkMap;
                Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads = accessor.qve$getPendingUnloads();
                if (pendingUnloads != null && !pendingUnloads.isEmpty()) {
                    synchronized (pendingUnloads) {
                        List<Long> toRemove = new ArrayList<>();
                        for (var entry : pendingUnloads.long2ObjectEntrySet()) {
                            long chunkKey = entry.getLongKey();
                            int cx = ChunkPos.getX(chunkKey);
                            int cz = ChunkPos.getZ(chunkKey);
                            long rKey = ChunkPos.asLong(cx >> 5, cz >> 5);
                            if (regionKeys.contains(rKey)) {
                                ChunkHolder holder = entry.getValue();
                                if (holder != null) {
                                    accessor.qve$saveChunkIfNeeded(holder);
                                }
                                toRemove.add(chunkKey);
                            }
                        }
                        for (Long key : toRemove) {
                            pendingUnloads.remove(key.longValue());
                        }
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("Failed to drain pending unloads for target regions: {}", t.getMessage());
            }

            // 2. Single synchronous drain of all pending writes in IOWorker
            worker.synchronize(true).join();

            // 3. Evict and close all targeted region files
            RegionFileStorage storage = ((IOWorkerAccessor) worker).qve$getStorage();
            if (storage == null) {
                return false;
            }

            Long2ObjectLinkedOpenHashMap<RegionFile> cache = ((RegionFileStorageAccessor) (Object) storage).qve$getRegionCache();
            if (cache != null) {
                synchronized (cache) {
                    for (Long key : regionKeys) {
                        RegionFile regionFile = cache.remove(key.longValue());
                        if (regionFile != null) {
                            try {
                                regionFile.close();
                                int rx = ChunkPos.getX(key);
                                int rz = ChunkPos.getZ(key);
                                LOGGER.info("Evicted and closed active RegionFile handle for r.{}.{}.mca in dimension {}",
                                        rx, rz, serverLevel.dimension().location());
                            } catch (IOException e) {
                                LOGGER.warn("Failed to close evicted RegionFile for key {}: {}", key, e.getMessage(), e);
                            }
                        }
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            LOGGER.error("Failed to batch evict RegionFiles from Minecraft RegionFileStorage: {}", t.getMessage(), t);
            return false;
        } finally {
            EVICTION_LOCK.unlock();
        }
    }
}
