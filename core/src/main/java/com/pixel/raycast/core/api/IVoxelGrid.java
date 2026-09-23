package com.pixel.raycast.core.api;

import com.pixel.raycast.core.voxel.VoxelSection;

/**
 * Universal interface for accessing 3D voxel sections and spatial occupancy.
 */
public interface IVoxelGrid {

    /**
     * Retrieves the voxel section at the given section coordinates (world block coordinate >> 4).
     *
     * @param sectionX Chunk/Section X coordinate
     * @param sectionY Chunk/Section Y coordinate
     * @param sectionZ Chunk/Section Z coordinate
     * @return The VoxelSection, or null if empty / unloaded
     */
    VoxelSection getSection(int sectionX, int sectionY, int sectionZ);

    /**
     * Checks if the voxel at world block coordinates is solid.
     *
     * @param worldX Absolute world X coordinate
     * @param worldY Absolute world Y coordinate
     * @param worldZ Absolute world Z coordinate
     * @return True if the voxel is solid matter, false if air or unloaded
     */
    default boolean isSolid(int worldX, int worldY, int worldZ) {
        int sx = worldX >> 4;
        int sy = worldY >> 4;
        int sz = worldZ >> 4;
        VoxelSection section = getSection(sx, sy, sz);
        if (section == null || section.isEmpty()) {
            return false;
        }
        return section.isSolid(worldX & 15, worldY & 15, worldZ & 15);
    }

    /**
     * Retrieves the block ID at world block coordinates.
     *
     * @param worldX Absolute world X coordinate
     * @param worldY Absolute world Y coordinate
     * @param worldZ Absolute world Z coordinate
     * @return 16-bit numeric block identifier, or 0 if air or unloaded
     */
    default short getBlockId(int worldX, int worldY, int worldZ) {
        int sx = worldX >> 4;
        int sy = worldY >> 4;
        int sz = worldZ >> 4;
        VoxelSection section = getSection(sx, sy, sz);
        if (section == null || section.isEmpty()) {
            return 0;
        }
        return section.getBlockId(worldX & 15, worldY & 15, worldZ & 15);
    }

    /**
     * Retrieves the optional 2D heightmap for the specified chunk column, or null if uncomputed.
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return Heightmap2D instance for this column, or null if uncomputed
     */
    default com.pixel.raycast.core.voxel.Heightmap2D getHeightmap(int chunkX, int chunkZ) {
        return null;
    }

    /**
     * Retrieves the maximum solid Y coordinate across the entire known world/grid.
     *
     * @return Maximum world Y altitude containing solid blocks
     */
    default short getHighestWorldY() {
        return Short.MAX_VALUE;
    }

    /**
     * Retrieves the optional shape registry for sub-voxel collision shapes.
     *
     * @return ShapeRegistry instance, or null if only full cubes are supported
     */
    default com.pixel.raycast.core.shape.ShapeRegistry getShapeRegistry() {
        return null;
    }
}
