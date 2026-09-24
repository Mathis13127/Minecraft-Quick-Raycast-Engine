package com.pixel.qve.api;

import com.pixel.qve.state.ShapeRegistry;
import com.pixel.qve.world.Heightmap2D;
import com.pixel.qve.world.VoxelChunkColumn;
import com.pixel.qve.world.VoxelSection;

/**
 * Universal interface for accessing 3D voxel sections and spatial occupancy
 * across loaded in-memory chunks and offline on-disk Anvil MCA regions.
 */
public interface IVoxelWorld {

    /**
     * Retrieves the voxel section at the given section coordinates (world block coordinate &gt;&gt; 4).
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
     * @return 32-bit numeric block identifier, or 0 if air or unloaded
     */
    default int getBlockId(int worldX, int worldY, int worldZ) {
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
    default Heightmap2D getHeightmap(int chunkX, int chunkZ) {
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
     * Retrieves the optional chunk column containing vertical sections and heightmap.
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return VoxelChunkColumn instance, or null if uncomputed or unsupported
     */
    default VoxelChunkColumn getColumn(int chunkX, int chunkZ) {
        return null;
    }

    /**
     * Retrieves the minimum solid Y coordinate across the world/grid (e.g. -64 in modern vanilla Minecraft).
     *
     * @return Minimum world Y altitude containing solid blocks
     */
    default short getLowestWorldY() {
        return Short.MIN_VALUE;
    }

    /**
     * Retrieves the optional shape registry for sub-voxel collision shapes.
     *
     * @return ShapeRegistry instance, or null if only full cubes are supported
     */
    default ShapeRegistry getShapeRegistry() {
        return null;
    }

    /**
     * Retrieves the optional block trait registry for physical/optical block state classification.
     *
     * @return BlockTraitRegistry instance, or null if unsupported
     */
    default com.pixel.qve.state.BlockTraitRegistry getTraitRegistry() {
        return null;
    }

    /**
     * Checks if the specified 512x512 block region (32x32 chunks) is entirely absent or empty.
     *
     * @param regionX Region X coordinate (world block X &gt;&gt; 9)
     * @param regionZ Region Z coordinate (world block Z &gt;&gt; 9)
     * @return True if the entire region contains no solid blocks or is non-existent
     */
    default boolean isRegionEmpty(int regionX, int regionZ) {
        return false;
    }

    /**
     * Retrieves the optional 2D heightmap for the specified 512x512 region, or null if uncomputed.
     *
     * @param regionX Region X coordinate (world block X &gt;&gt; 9)
     * @param regionZ Region Z coordinate (world block Z &gt;&gt; 9)
     * @return RegionHeightmap2D instance, or null if uncomputed
     */
    default com.pixel.qve.world.RegionHeightmap2D getRegionHeightmap(int regionX, int regionZ) {
        return null;
    }

    /**
     * Retrieves the maximum solid block Y altitude in the specified 512x512 region.
     * Returns {@link com.pixel.qve.world.Heightmap2D#VOID_Y} if the region is completely empty.
     * Returns {@link Short#MAX_VALUE} if unconstrained or unknown.
     *
     * @param regionX Region X coordinate (world block X &gt;&gt; 9)
     * @param regionZ Region Z coordinate (world block Z &gt;&gt; 9)
     * @return Maximum solid Y in region
     */
    default short getRegionMaxY(int regionX, int regionZ) {
        if (isRegionEmpty(regionX, regionZ)) {
            return com.pixel.qve.world.Heightmap2D.VOID_Y;
        }
        com.pixel.qve.world.RegionHeightmap2D rHm = getRegionHeightmap(regionX, regionZ);
        return (rHm != null) ? rHm.getRegionMaxY() : Short.MAX_VALUE;
    }

    /**
     * Checks if the given world block coordinate is outside all known populated/disk regions
     * and the ray direction is pointing outward away from any known geometry.
     *
     * @param worldBlockX Current world block X coordinate
     * @param worldBlockZ Current world block Z coordinate
     * @param stepX       Ray horizontal stepping direction along X (-1, 0, +1)
     * @param stepZ       Ray horizontal stepping direction along Z (-1, 0, +1)
     * @return True if the ray has exited the known universe and will never encounter solid matter
     */
    default boolean isOutOfBounds(int worldBlockX, int worldBlockZ, int stepX, int stepZ) {
        return false;
    }

    /**
     * Checks if the world grid has known horizontal boundaries (e.g. from generated regions or simulation radius).
     *
     * @return True if horizontal world bounds are available
     */
    default boolean hasWorldBounds() {
        return false;
    }

    /**
     * Minimum known world block X coordinate containing potential solid geometry.
     *
     * @return Minimum world X
     */
    default int getWorldMinX() {
        return Integer.MIN_VALUE;
    }

    /**
     * Maximum known world block X coordinate containing potential solid geometry.
     *
     * @return Maximum world X
     */
    default int getWorldMaxX() {
        return Integer.MAX_VALUE;
    }

    /**
     * Minimum known world block Z coordinate containing potential solid geometry.
     *
     * @return Minimum world Z
     */
    default int getWorldMinZ() {
        return Integer.MIN_VALUE;
    }

    /**
     * Maximum known world block Z coordinate containing potential solid geometry.
     *
     * @return Maximum world Z
     */
    default int getWorldMaxZ() {
        return Integer.MAX_VALUE;
    }

    /**
     * Retrieves the optional on-demand NBT service for this voxel world.
     *
     * @return INbtService instance, or null if NBT queries are unsupported
     */
    default com.pixel.qve.api.nbt.INbtService getNbtService() {
        return null;
    }

    /**
     * Retrieves the high-throughput raycast service for batch queries.
     *
     * @return IRaycastService instance
     */
    default com.pixel.qve.api.raycast.IRaycastService getRaycastService() {
        return com.pixel.qve.raycast.VoxelRaycastBatch.INSTANCE;
    }
}
