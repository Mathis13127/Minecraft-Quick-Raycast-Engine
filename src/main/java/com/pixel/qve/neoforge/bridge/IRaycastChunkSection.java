package com.pixel.qve.neoforge.bridge;

import com.pixel.qve.world.VoxelChunkColumn;
import com.pixel.qve.world.VoxelSection;

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
     * Gets the vertical section Y coordinate.
     *
     * @return Section Y coordinate
     */
    int raycast$getSectionY();

    /**
     * Retrieves the underlying vanilla PalettedContainer for block states.
     *
     * @return PalettedContainer instance, or null if unallocated
     */
    default net.minecraft.world.level.chunk.PalettedContainer<net.minecraft.world.level.block.state.BlockState> raycast$getStates() {
        return null;
    }

    /**
     * Atomically swaps the underlying vanilla PalettedContainer for block states in 1 CPU cycle.
     *
     * @param states New PalettedContainer instance
     */
    default void raycast$setStates(net.minecraft.world.level.chunk.PalettedContainer<net.minecraft.world.level.block.state.BlockState> states) {
    }
}
