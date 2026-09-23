package com.pixel.raycast.core.voxel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe bidirectional registry mapping Minecraft block resource locations (e.g. "minecraft:stone")
 * to compact 16-bit numeric identifiers (short).
 * ID 0 is strictly reserved for non-solid air blocks.
 */
public final class BlockIdRegistry {

    /** Numeric identifier reserved for non-solid air (0). */
    public static final short AIR_ID = 0;
    /** Standard vanilla air identifier string. */
    public static final String AIR_NAME = "minecraft:air";

    private final ConcurrentHashMap<String, Short> nameToId = new ConcurrentHashMap<>();
    private final List<String> idToName = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger nextId = new AtomicInteger(1);

    /**
     * Constructs a BlockIdRegistry with air pre-registered at ID 0.
     */
    public BlockIdRegistry() {
        // Reserve index 0 for air
        nameToId.put(AIR_NAME, AIR_ID);
        nameToId.put("minecraft:cave_air", AIR_ID);
        nameToId.put("minecraft:void_air", AIR_ID);
        idToName.add(AIR_NAME);
    }

    /**
     * Resolves an existing block ID or registers a new identifier atomically.
     *
     * @param blockName Resource location string (e.g. "minecraft:grass_block")
     * @return 16-bit short block identifier
     */
    public short getOrRegister(String blockName) {
        Objects.requireNonNull(blockName, "Block name cannot be null");
        Short existing = nameToId.get(blockName);
        if (existing != null) {
            return existing;
        }

        synchronized (this) {
            existing = nameToId.get(blockName);
            if (existing != null) {
                return existing;
            }

            int idInt = nextId.getAndIncrement();
            if (idInt > Short.MAX_VALUE) {
                throw new IllegalStateException("BlockIdRegistry exhausted: exceeded maximum of " + Short.MAX_VALUE + " unique blocks");
            }

            short id = (short) idInt;
            idToName.add(blockName);
            nameToId.put(blockName, id);
            return id;
        }
    }

    /**
     * Resolves the block resource name associated with a numeric ID.
     *
     * @param blockId 16-bit numeric ID
     * @return Block name, or "minecraft:air" if 0, or null if unregistered
     */
    public String getName(short blockId) {
        if (blockId == AIR_ID) {
            return AIR_NAME;
        }
        int index = blockId & 0xFFFF;
        if (index >= 0 && index < idToName.size()) {
            return idToName.get(index);
        }
        return null;
    }

    /**
     * Checks if the given numeric ID corresponds to an air (non-solid) block.
     *
     * @param blockId 16-bit block ID to check
     * @return True if air (ID 0)
     */
     public static boolean isAir(short blockId) {
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
