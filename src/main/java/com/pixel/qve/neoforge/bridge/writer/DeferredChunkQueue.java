package com.pixel.qve.neoforge.bridge.writer;

import com.pixel.qve.neoforge.api.ChunkWriteBatch;
import com.pixel.qve.neoforge.api.VoxelWriteAPI;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelGrid;
import com.pixel.qve.mca.writer.PrimitiveMutationBuffer;
import com.pixel.qve.world.VoxelSection;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe queue managing deferred chunk mutations for hybrid regions.
 * When a region is partially loaded in RAM, unloaded chunks within that region are placed
 * into this queue to prevent Minecraft's active RegionFile handles from clobbering direct MCA writes.
 * <p>
 * Queued chunks are applied seamlessly in-memory when loaded via {@code ChunkEvent.Load},
 * or flushed directly to disk when the server stops and region handles are cleanly closed.
 */
public final class DeferredChunkQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeferredChunkQueue.class);

    private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<ChunkWriteBatch.ChunkEdits>> QUEUE = new ConcurrentHashMap<>();

    private DeferredChunkQueue() {}

    private static Long2ObjectOpenHashMap<ChunkWriteBatch.ChunkEdits> getDimensionMap(ResourceKey<Level> dim) {
        return QUEUE.computeIfAbsent(dim, k -> new Long2ObjectOpenHashMap<>());
    }

    /**
     * Enqueues pending ChunkEdits for an offline chunk in a hybrid region.
     *
     * @param level Target Level
     * @param edits ChunkEdits container
     */
    public static void enqueue(Level level, ChunkWriteBatch.ChunkEdits edits) {
        if (level == null || edits == null) return;
        ResourceKey<Level> dim = level.dimension();
        long key = ChunkPos.asLong(edits.getChunkX(), edits.getChunkZ());

        Long2ObjectOpenHashMap<ChunkWriteBatch.ChunkEdits> map = getDimensionMap(dim);
        synchronized (map) {
            ChunkWriteBatch.ChunkEdits existing = map.get(key);
            if (existing != null) {
                // Merge edits into existing entry
                for (var secEntry : edits.getWholeSections().entrySet()) {
                    existing.setSection(secEntry.getKey(), secEntry.getValue());
                }
                PrimitiveMutationBuffer srcBuf = edits.getMutationBuffer();
                if (srcBuf != null && !srcBuf.isEmpty()) {
                    for (int mi = 0, sz = srcBuf.size(); mi < sz; mi++) {
                        existing.addBox(srcBuf.minX(mi), srcBuf.minY(mi), srcBuf.minZ(mi),
                                srcBuf.maxX(mi), srcBuf.maxY(mi), srcBuf.maxZ(mi),
                                srcBuf.targetBlockId(mi), srcBuf.filterBlockId(mi), srcBuf.rawNbt(mi));
                    }
                } else {
                    for (ChunkWriteBatch.BlockMutation m : edits.getMutations()) {
                        existing.addMutation(m);
                    }
                }
            } else {
                map.put(key, edits);
            }
        }

        LOGGER.debug("Enqueued deferred chunk edits for ({}, {}) in dimension {}",
                edits.getChunkX(), edits.getChunkZ(), dim.location());
    }

    /**
     * Checks if deferred chunk edits exist for the given chunk coordinates.
     *
     * @param level  Target Level
     * @param chunkX Chunk X
     * @param chunkZ Chunk Z
     * @return True if edits are queued
     */
    public static boolean hasEdits(Level level, int chunkX, int chunkZ) {
        if (level == null) return false;
        Long2ObjectOpenHashMap<ChunkWriteBatch.ChunkEdits> map = QUEUE.get(level.dimension());
        if (map == null) return false;

        long key = ChunkPos.asLong(chunkX, chunkZ);
        synchronized (map) {
            return map.containsKey(key);
        }
    }

    /**
     * Retrieves and removes the queued ChunkEdits for the given chunk coordinates.
     *
     * @param level  Target Level
     * @param chunkX Chunk X
     * @param chunkZ Chunk Z
     * @return ChunkEdits if present, or null
     */
    public static ChunkWriteBatch.ChunkEdits pollEdits(Level level, int chunkX, int chunkZ) {
        if (level == null) return false ? null : null;
        Long2ObjectOpenHashMap<ChunkWriteBatch.ChunkEdits> map = QUEUE.get(level.dimension());
        if (map == null) return null;

        long key = ChunkPos.asLong(chunkX, chunkZ);
        synchronized (map) {
            return map.remove(key);
        }
    }

    /**
     * Peeks at the queued ChunkEdits without removing them.
     *
     * @param level  Target Level
     * @param chunkX Chunk X
     * @param chunkZ Chunk Z
     * @return ChunkEdits if present, or null
     */
    public static ChunkWriteBatch.ChunkEdits peekEdits(Level level, int chunkX, int chunkZ) {
        if (level == null) return null;
        Long2ObjectOpenHashMap<ChunkWriteBatch.ChunkEdits> map = QUEUE.get(level.dimension());
        if (map == null) return null;

        long key = ChunkPos.asLong(chunkX, chunkZ);
        synchronized (map) {
            return map.get(key);
        }
    }

    /**
     * Queries the expected block ID at the given world coordinates from the deferred queue.
     * Used by raycasting and voxel cache to overlay pending mutations before chunk load.
     *
     * @param level Target Level
     * @param x     World X
     * @param y     World Y
     * @param z     World Z
     * @return Block ID if an override exists in the queue, or 0 if none
     */
    public static int getBlockId(Level level, int x, int y, int z) {
        if (level == null) return 0;
        int cx = x >> 4;
        int cz = z >> 4;
        ChunkWriteBatch.ChunkEdits edits = peekEdits(level, cx, cz);
        if (edits == null) return 0;

        int secY = y >> 4;
        VoxelSection ws = edits.getWholeSections().get(secY);
        if (ws != null) {
            return ws.getBlockId(x & 15, y & 15, z & 15);
        }

        PrimitiveMutationBuffer srcBuf = edits.getMutationBuffer();
        if (srcBuf != null && !srcBuf.isEmpty()) {
            int lx = x & 15;
            int lz = z & 15;
            for (int i = srcBuf.size() - 1; i >= 0; i--) {
                if (srcBuf.contains(i, lx, y, lz)) {
                    return srcBuf.targetBlockId(i);
                }
            }
        } else {
            List<ChunkWriteBatch.BlockMutation> mutations = edits.getMutations();
            for (int i = mutations.size() - 1; i >= 0; i--) {
                ChunkWriteBatch.BlockMutation m = mutations.get(i);
                if (m.worldX() == x && m.worldY() == y && m.worldZ() == z) {
                    return m.targetBlockId();
                }
            }
        }

        return 0;
    }

    /**
     * Applies pending ChunkEdits directly into a newly loaded LevelChunk in RAM.
     * Must be called on server thread or during chunk load event.
     *
     * @param chunk Target LevelChunk
     * @param edits Queued ChunkEdits
     * @return Number of blocks mutated
     */
    public static int applyToChunk(LevelChunk chunk, ChunkWriteBatch.ChunkEdits edits) {
        if (chunk == null || edits == null) return 0;
        int appliedCount = ChunkEditsApplicator.applyToLoadingChunk(chunk, edits);
        LOGGER.info("Applied {} deferred block mutations to chunk ({}, {}) on load in {}",
                appliedCount, edits.getChunkX(), edits.getChunkZ(), chunk.getLevel().dimension().location());
        return appliedCount;
    }

    /**
     * Flushes all remaining queued chunk edits directly to disk during server shutdown or level unload,
     * once Minecraft has flushed its in-memory chunks and closed its RegionFile handles.
     * All region writes are dispatched concurrently to the disk thread pool before joining.
     *
     * @param server MinecraftServer instance
     */
    public static void flushAllToDisk(MinecraftServer server) {
        if (server == null || QUEUE.isEmpty()) return;

        LOGGER.info("Flushing all pending deferred chunk edits to disk on server stop...");
        List<CompletableFuture<Void>> allFutures = new ArrayList<>();

        for (Map.Entry<ResourceKey<Level>, Long2ObjectOpenHashMap<ChunkWriteBatch.ChunkEdits>> entry : QUEUE.entrySet()) {
            ResourceKey<Level> dim = entry.getKey();
            Long2ObjectOpenHashMap<ChunkWriteBatch.ChunkEdits> map = entry.getValue();
            ServerLevel level = server.getLevel(dim);
            if (level == null) continue;

            List<ChunkWriteBatch.ChunkEdits> remaining;
            synchronized (map) {
                remaining = new ArrayList<>(map.values());
                map.clear();
            }

            if (remaining.isEmpty()) continue;

            // Group by region
            Map<Long, List<ChunkWriteBatch.ChunkEdits>> byRegion = new HashMap<>();
            for (ChunkWriteBatch.ChunkEdits edits : remaining) {
                int rx = edits.getChunkX() >> 5;
                int rz = edits.getChunkZ() >> 5;
                long rKey = ChunkPos.asLong(rx, rz);
                byRegion.computeIfAbsent(rKey, k -> new ArrayList<>()).add(edits);
            }

            MinecraftVoxelWriter writer = VoxelWriteAPI.getWriter(level);
            if (writer != null) {
                for (Map.Entry<Long, List<ChunkWriteBatch.ChunkEdits>> regEntry : byRegion.entrySet()) {
                    long rKey = regEntry.getKey();
                    int rx = ChunkPos.getX(rKey);
                    int rz = ChunkPos.getZ(rKey);
                    List<ChunkWriteBatch.ChunkEdits> list = regEntry.getValue();
                    CompletableFuture<Void> f = writer.writeRegionBatchAsync(rx, rz, list, null)
                            .thenAccept(res -> LOGGER.info("Flushed {} deferred chunks to r.{}.{}.mca for dimension {}",
                                    list.size(), rx, rz, dim.location()))
                            .exceptionally(t -> {
                                LOGGER.error("Failed to flush deferred chunks for r.{}.{}.mca: {}", rx, rz, t.getMessage(), t);
                                return null;
                            });
                    allFutures.add(f);
                }
            }
        }

        if (!allFutures.isEmpty()) {
            CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[0])).join();
        }
        LOGGER.info("Completed flushing deferred chunk edits to disk.");
    }

    /**
     * Returns the total number of chunks currently waiting in the deferred queue across all dimensions.
     *
     * @return Queued chunk count
     */
    public static int getTotalQueuedChunks() {
        int count = 0;
        for (var map : QUEUE.values()) {
            synchronized (map) {
                count += map.size();
            }
        }
        return count;
    }

    /**
     * Clears all queued edits (primarily for testing and cache reset).
     */
    public static void clear() {
        QUEUE.clear();
    }
}
