package com.pixel.qve.neoforge.bridge.writer;

import com.pixel.qve.neoforge.mixin.ChunkStorageAccessor;
import com.pixel.qve.neoforge.mixin.IOWorkerAccessor;
import com.pixel.qve.neoforge.mixin.RegionFileStorageAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.Objects;

/**
 * High-reliability bridge synchronizing QVE direct disk writes with Minecraft's internal {@link RegionFileStorage}.
 * Evicts cached {@link RegionFile} handles and flushes pending writes to prevent stale in-memory sector offset desync.
 */
public final class MinecraftRegionFileBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftRegionFileBridge.class);

    private MinecraftRegionFileBridge() {}

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

        try {
            ChunkMap chunkMap = serverLevel.getChunkSource().chunkMap;
            if (chunkMap == null) {
                return false;
            }

            IOWorker worker = ((ChunkStorageAccessor) chunkMap).qve$getWorker();
            if (worker == null) {
                return false;
            }

            // 1. Drain all pending writes queued in Minecraft's memory to disk
            worker.synchronize(true).join();

            // 2. Access RegionFileStorage cache
            RegionFileStorage storage = ((IOWorkerAccessor) worker).qve$getStorage();
            if (storage == null) {
                return false;
            }

            Long2ObjectLinkedOpenHashMap<RegionFile> cache = ((RegionFileStorageAccessor) (Object) storage).qve$getRegionCache();
            if (cache != null) {
                synchronized (cache) {
                    long key = ChunkPos.asLong(rx, rz);
                    RegionFile regionFile = cache.remove(key);
                    if (regionFile != null) {
                        try {
                            regionFile.close();
                            LOGGER.info("Evicted and closed active RegionFile handle for r.{}.{}.mca in dimension {}",
                                    rx, rz, serverLevel.dimension().location());
                        } catch (IOException e) {
                            LOGGER.warn("Failed to close evicted RegionFile for r.{}.{}.mca: {}", rx, rz, e.getMessage(), e);
                        }
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            LOGGER.error("Failed to evict RegionFile for r.{}.{}.mca from Minecraft RegionFileStorage: {}",
                    rx, rz, t.getMessage(), t);
            return false;
        }
    }

    /**
     * Synchronizes and evicts multiple regions simultaneously. Drains Minecraft's pending writes once
     * before evicting all specified region files from cache.
     *
     * @param serverLevel Minecraft ServerLevel
     * @param regionKeys  Collection of packed region keys (from ChunkPos.asLong(rx, rz))
     * @return True if all evictions completed successfully
     */
    public static boolean evictAndFlushRegions(ServerLevel serverLevel, Collection<Long> regionKeys) {
        if (serverLevel == null || regionKeys == null || regionKeys.isEmpty()) {
            return false;
        }

        try {
            ChunkMap chunkMap = serverLevel.getChunkSource().chunkMap;
            if (chunkMap == null) {
                return false;
            }

            IOWorker worker = ((ChunkStorageAccessor) chunkMap).qve$getWorker();
            if (worker == null) {
                return false;
            }

            // 1. Single synchronous drain of all pending writes
            worker.synchronize(true).join();

            // 2. Evict and close all targeted region files
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
        }
    }
}
