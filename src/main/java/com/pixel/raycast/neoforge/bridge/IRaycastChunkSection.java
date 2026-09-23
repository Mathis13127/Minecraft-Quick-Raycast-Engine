package com.pixel.raycast.neoforge.bridge;

import com.pixel.raycast.core.cache.VoxelChunkColumn;
import com.pixel.raycast.core.voxel.VoxelSection;

/**
 * Interface mixed into Minecraft's {@code LevelChunkSection}.
 * Allows zero-overhead association between a vanilla {@code LevelChunkSection} and
 * the high-performance {@link VoxelSection} and {@link VoxelChunkColumn}.
 */
public interface IRaycastChunkSection {

    /**
     * Retrieves the high-performance {@link VoxelSection} associated with this chunk section.
     *
     * @return VoxelSection instance, or null if uncompiled
     */
    VoxelSection raycast$getVoxelSection();

    /**
     * Binds a compiled {@link VoxelSection} to this chunk section.
     *
     * @param section VoxelSection instance
     */
    void raycast$setVoxelSection(VoxelSection section);

    /**
     * Retrieves the owning {@link VoxelChunkColumn}.
     *
     * @return VoxelChunkColumn instance, or null
     */
    VoxelChunkColumn raycast$getVoxelColumn();

    /**
     * Binds the owning {@link VoxelChunkColumn} and section Y index.
     *
     * @param column   Owning column
     * @param sectionY Vertical section index
     */
    void raycast$setVoxelColumn(VoxelChunkColumn column, int sectionY);

    /**
     * Gets the vertical section Y index.
     *
     * @return Section Y coordinate
     */
    int raycast$getSectionY();
}
