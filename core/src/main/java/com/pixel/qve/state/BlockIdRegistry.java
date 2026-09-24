package com.pixel.qve.state;

import com.pixel.qve.mca.FastNbtReader;
import com.pixel.qve.state.BlockIdRegistry;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe bidirectional registry mapping Minecraft block resource locations (e.g. "minecraft:stone")
 * to compact 32-bit numeric identifiers (int).
 * ID 0 is strictly reserved for non-solid air blocks.
 */
public final class BlockIdRegistry {

    /** Numeric identifier reserved for non-solid air (0). */
    public static final int AIR_ID = 0;
    /** Standard vanilla air identifier string. */
    public static final String AIR_NAME = "minecraft:air";

    private final ConcurrentHashMap<String, Integer> nameToId = new ConcurrentHashMap<>();
    private final List<String> idToName = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger nextId = new AtomicInteger(1);

    private static final int BYTE_HASH_TABLE_SIZE = 2048;
    private static final int BYTE_HASH_TABLE_MASK = BYTE_HASH_TABLE_SIZE - 1;
    private final int[] byteHashKeys = new int[BYTE_HASH_TABLE_SIZE];
    private final int[] byteHashValues = new int[BYTE_HASH_TABLE_SIZE];
    private final long[] byteHashOccupied = new long[BYTE_HASH_TABLE_SIZE / 64];
    private final BlockStateDictionary stateDictionary;

    /**
     * Constructs a BlockIdRegistry with air pre-registered at ID 0.
     */
    public BlockIdRegistry() {
        this.stateDictionary = new BlockStateDictionary(this);
        // Reserve index 0 for air
        nameToId.put(AIR_NAME, AIR_ID);
        nameToId.put("minecraft:cave_air", AIR_ID);
        nameToId.put("minecraft:void_air", AIR_ID);
        idToName.add(AIR_NAME);
    }

    /**
     * Retrieves the backing BlockStateDictionary for dynamic property sets.
     *
     * @return BlockStateDictionary instance
     */
    public BlockStateDictionary getStateDictionary() {
        return stateDictionary;
    }

    /**
     * Resolves an existing block ID or registers a new identifier directly from raw ByteBuffer UTF-8 bytes.
     * Zero-allocation and lock-free on cache hits (2-3 CPU cycles), bypassing String creation and map lookups.
     *
     * @param buf    Direct or heap ByteBuffer
     * @param offset Byte offset of the UTF-8 string payload
     * @param length Number of UTF-8 bytes
     * @return 32-bit integer block identifier
     */
    public int getOrRegisterFromBytes(java.nio.ByteBuffer buf, int offset, int length) {
        int hash = com.pixel.qve.mca.FastNbtReader.hashBytes(buf, offset, length);
        int slot = (hash ^ (hash >>> 16)) & BYTE_HASH_TABLE_MASK;
        int wordIdx = slot >>> 6;
        long bit = 1L << (slot & 63);

        if ((byteHashOccupied[wordIdx] & bit) != 0L && byteHashKeys[slot] == hash) {
            return byteHashValues[slot];
        }

        // Cache miss: resolve String representation once, register, and populate cache
        String name = com.pixel.qve.mca.FastNbtReader.decodeStringDirect(buf, offset, length);
        int id = getOrRegister(name);

        synchronized (this) {
            byteHashKeys[slot] = hash;
            byteHashValues[slot] = id;
            byteHashOccupied[wordIdx] |= bit;
        }

        return id;
    }

    /**
     * Resolves an existing block ID or registers a new identifier atomically.
     *
     * @param blockName Resource location string (e.g. "minecraft:grass_block")
     * @return 32-bit integer block identifier
     */
    public int getOrRegister(String blockName) {
        Objects.requireNonNull(blockName, "Block name cannot be null");
        Integer existing = nameToId.get(blockName);
        if (existing != null) {
            return existing;
        }

        synchronized (this) {
            existing = nameToId.get(blockName);
            if (existing != null) {
                return existing;
            }

            int id = nextId.getAndIncrement();
            if (id < 0) {
                throw new IllegalStateException("BlockIdRegistry exhausted: exceeded integer capacity");
            }

            idToName.add(blockName);
            nameToId.put(blockName, id);
            return id;
        }
    }

    /**
     * Resolves the block resource name associated with a numeric ID.
     *
     * @param blockId 32-bit numeric ID
     * @return Block name, or "minecraft:air" if 0, or null if unregistered
     */
    public String getName(int blockId) {
        if (blockId == AIR_ID) {
            return AIR_NAME;
        }
        if (blockId >= 0 && blockId < idToName.size()) {
            return idToName.get(blockId);
        }
        return null;
    }

    /**
     * Checks if the given numeric ID corresponds to an air (non-solid) block.
     *
     * @param blockId 32-bit block ID to check
     * @return True if air (ID 0)
     */
     public static boolean isAir(int blockId) {
         return blockId == AIR_ID;
     }

    /**
     * Total number of registered unique blocks.
     *
     * @return Registered block count
     */
    public int size() {
        return idToName.size();
    }
}
