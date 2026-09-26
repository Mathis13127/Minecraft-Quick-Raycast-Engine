package com.pixel.qve.neoforge.api.lighting;

import net.minecraft.world.level.chunk.DataLayer;

/**
 * Immutable spatial container encapsulating deterministic block and sky illumination data
 * computed by Minecraft's official {@link net.minecraft.world.level.lighting.LevelLightEngine}.
 * <p>
 * Provides O(1) primitive queries for sky light [0..15], block light [0..15], packed light
 * values for vertex formats {@code (blockLight << 4) | (skyLight << 20)}, and direct access
 * to raw 2048-byte nibble {@link DataLayer} buffers.
 * </p>
 */
public interface VoxelChunkLighting {

    /**
     * Chunk column X coordinate.
     *
     * @return chunk X
     */
    int getChunkX();

    /**
     * Chunk column Z coordinate.
     *
     * @return chunk Z
     */
    int getChunkZ();

    /**
     * Minimum vertical section Y coordinate covered by this lighting container.
     *
     * @return min section Y (e.g. -4 for build height -64)
     */
    int getMinSectionY();

    /**
     * Maximum vertical section Y coordinate covered by this lighting container.
     *
     * @return max section Y (e.g. 20 for build height 320)
     */
    int getMaxSectionY();

    /**
     * Returns the computed sky light level [0..15] at the specified coordinates.
     *
     * @param localX local X in chunk [0..15]
     * @param worldY world altitude Y
     * @param localZ local Z in chunk [0..15]
     * @return sky light level [0..15]
     */
    int getSkyLight(int localX, int worldY, int localZ);

    /**
     * Returns the computed block light level [0..15] (torches, lava, lanterns) at the specified coordinates.
     *
     * @param localX local X in chunk [0..15]
     * @param worldY world altitude Y
     * @param localZ local Z in chunk [0..15]
     * @return block light level [0..15]
     */
    int getBlockLight(int localX, int worldY, int localZ);

    /**
     * Packs the block and sky light levels into a 32-bit integer matching Minecraft's LightTexture format:
     * {@code (blockLight << 4) | (skyLight << 20)}.
     * <p>
     * Server-safe and zero dependency on client-only classes.
     * </p>
     *
     * @param localX local X in chunk [0..15]
     * @param worldY world altitude Y
     * @param localZ local Z in chunk [0..15]
     * @return packed 32-bit light integer
     */
    default int getPackedLight(int localX, int worldY, int localZ) {
        int b = getBlockLight(localX, worldY, localZ);
        int s = getSkyLight(localX, worldY, localZ);
        return (b << 4) | (s << 20);
    }

    /**
     * Retrieves the raw 2048-byte nibble array for the sky light layer at the specified section Y.
     *
     * @param sectionY vertical section coordinate
     * @return DataLayer or null if section has no sky data (pure dark)
     */
    DataLayer getSkyDataLayer(int sectionY);

    /**
     * Retrieves the raw 2048-byte nibble array for the block light layer at the specified section Y.
     *
     * @param sectionY vertical section coordinate
     * @return DataLayer or null if section has no block light sources
     */
    DataLayer getBlockDataLayer(int sectionY);

    /**
     * Returns true if lighting data exists for the given vertical section Y coordinate.
     *
     * @param sectionY vertical section coordinate
     * @return true if populated
     */
    boolean hasSection(int sectionY);
}
