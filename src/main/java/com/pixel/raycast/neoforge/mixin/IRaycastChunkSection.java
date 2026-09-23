package com.pixel.raycast.neoforge.mixin;

import com.pixel.raycast.core.cache.VoxelChunkColumn;
import com.pixel.raycast.core.voxel.VoxelSection;

/**
 * Interface mixed into Minecraft's LevelChunkSection.
 * Allows zero-overhead association between a vanilla LevelChunkSection and
 * the high-performance VoxelSection and VoxelChunkColumn.
 */
public interface IRaycastChunkSection {

    VoxelSection raycast$getVoxelSection();

    void raycast$setVoxelSection(VoxelSection section);

    VoxelChunkColumn raycast$getVoxelColumn();

    void raycast$setVoxelColumn(VoxelChunkColumn column, int sectionY);

    int raycast$getSectionY();
}
