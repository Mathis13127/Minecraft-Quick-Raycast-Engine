package com.pixel.qve.neoforge.terrain;

import com.pixel.qve.neoforge.api.terrain.VirtualChunk;
import net.minecraft.world.level.ChunkPos;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, thread-safe LRU cache storing recently generated in-memory {@link VirtualChunk} instances.
 * <p>
 * Eliminates repeated multi-hundred-millisecond procedural 3D noise generation when the same source
 * chunk coordinates are repeatedly stamped, sampled, or queried across world editing commands.
 * </p>
 */
public final class VirtualChunkCache {

    /** Maximum number of virtual chunks held in memory (~8–12 MB total RAM footprint). */
    public static final int MAX_ENTRIES = 64;

    private static final Map<Long, VirtualChunk> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, VirtualChunk> eldest) {
                    return size() > MAX_ENTRIES;
                }
            }
    );

    private VirtualChunkCache() {}

    /**
     * Retrieves a cached {@link VirtualChunk} for the specified coordinates and stage.
     *
     * @param chunkX Source chunk X
     * @param chunkZ Source chunk Z
     * @param stage  Generation cutoff stage
     * @return Cached VirtualChunk or null if miss
     */
    public static VirtualChunk get(int chunkX, int chunkZ, TerrainStage stage) {
        if (stage == null) return null;
        long key = packKey(chunkX, chunkZ, stage);
        return CACHE.get(key);
    }

    /**
     * Stores a generated {@link VirtualChunk} into the LRU cache.
     *
     * @param chunkX Source chunk X
     * @param chunkZ Source chunk Z
     * @param stage  Generation cutoff stage
     * @param chunk  VirtualChunk instance
     */
    public static void put(int chunkX, int chunkZ, TerrainStage stage, VirtualChunk chunk) {
        if (stage == null || chunk == null) return;
        long key = packKey(chunkX, chunkZ, stage);
        CACHE.put(key, chunk);
    }

    /**
     * Checks if a virtual chunk is currently present in the cache.
     *
     * @param chunkX Source chunk X
     * @param chunkZ Source chunk Z
     * @param stage  Generation cutoff stage
     * @return True if present
     */
    public static boolean contains(int chunkX, int chunkZ, TerrainStage stage) {
        if (stage == null) return false;
        long key = packKey(chunkX, chunkZ, stage);
        return CACHE.containsKey(key);
    }

    /**
     * Returns the current number of cached virtual chunks.
     *
     * @return Number of entries
     */
    public static int size() {
        return CACHE.size();
    }

    /**
     * Clears all cached virtual chunks from memory.
     */
    public static void clear() {
        CACHE.clear();
    }

    /**
     * Packs chunk coordinates and generation stage into a single 64-bit primitive key.
     */
    public static long packKey(int chunkX, int chunkZ, TerrainStage stage) {
        Objects.requireNonNull(stage, "stage cannot be null");
        long pos = ChunkPos.asLong(chunkX, chunkZ);
        return (pos << 3) | (stage.ordinal() & 7L);
    }
}
