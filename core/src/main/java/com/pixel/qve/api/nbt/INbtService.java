package com.pixel.qve.api.nbt;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Universal service for fetching block entity (tile entity) NBT data on demand.
 * Maintains zero persistent NBT overhead in memory, streaming NBT only when explicitly requested.
 */
public interface INbtService {

    /**
     * Retrieves the raw NBT compound bytes for a block entity at world coordinates.
     *
     * @param worldX Absolute world X coordinate
     * @param worldY Absolute world Y coordinate
     * @param worldZ Absolute world Z coordinate
     * @return ByteBuffer containing the NBT TAG_Compound, or null if absent or unloaded
     */
    ByteBuffer getBlockEntityRawNbt(int worldX, int worldY, int worldZ);

    /**
     * Retrieves parsed key-value data for a block entity at world coordinates.
     *
     * @param worldX Absolute world X coordinate
     * @param worldY Absolute world Y coordinate
     * @param worldZ Absolute world Z coordinate
     * @return Key-value map representing the block entity NBT, or null if absent
     */
    Map<String, Object> getBlockEntityData(int worldX, int worldY, int worldZ);
}
