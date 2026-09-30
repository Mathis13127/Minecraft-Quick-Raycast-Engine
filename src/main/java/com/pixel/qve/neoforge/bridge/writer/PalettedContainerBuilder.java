package com.pixel.qve.neoforge.bridge.writer;

import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.world.VoxelSection;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * High-performance, zero-allocation builder constructing isolated {@link PalettedContainer} instances
 * directly from QVE {@link VoxelSection} and primitive mutation buffers on worker threads.
 */
public final class PalettedContainerBuilder {

    private PalettedContainerBuilder() {}

    /**
     * Builds an isolated, thread-safe PalettedContainer from a VoxelSection.
     * Uses zero-allocation fast-paths for homogeneous and empty sections.
     *
     * @param voxelSection Source VoxelSection
     * @return Newly constructed PalettedContainer holding block states
     */
    public static PalettedContainer<BlockState> buildFromVoxelSection(VoxelSection voxelSection) {
        if (voxelSection == null || voxelSection.isEmpty()) {
            return new PalettedContainer<>(
                    Block.BLOCK_STATE_REGISTRY,
                    Blocks.AIR.defaultBlockState(),
                    PalettedContainer.Strategy.SECTION_STATES
            );
        }

        // Fast-path 1: Homogeneous section (100% single block, zero long[] allocation)
        if (voxelSection.isHomogeneous()) {
            int singleId = voxelSection.getSingleBlockId();
            BlockState state = (singleId != BlockIdRegistry.AIR_ID)
                    ? MinecraftVoxelBridge.getBlockState(singleId)
                    : Blocks.AIR.defaultBlockState();
            if (state == null) {
                state = Blocks.AIR.defaultBlockState();
            }
            return new PalettedContainer<>(
                    Block.BLOCK_STATE_REGISTRY,
                    state,
                    PalettedContainer.Strategy.SECTION_STATES
            );
        }

        // Fast-path 2: Heterogeneous section using linear flat index traversal
        PalettedContainer<BlockState> container = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES
        );

        int[] blockIds = voxelSection.getBlockIds();
        if (blockIds != null) {
            int lastId = -1;
            BlockState lastState = null;
            for (int y = 0; y < 16; y++) {
                int yOffset = y << 8;
                for (int z = 0; z < 16; z++) {
                    int yzOffset = yOffset | (z << 4);
                    for (int x = 0; x < 16; x++) {
                        int idx = yzOffset | x;
                        int id = blockIds[idx];
                        if (id != BlockIdRegistry.AIR_ID) {
                            BlockState state;
                            if (id == lastId) {
                                state = lastState;
                            } else {
                                state = MinecraftVoxelBridge.getBlockState(id);
                                lastId = id;
                                lastState = state;
                            }
                            if (state != null && !state.isAir()) {
                                container.set(x, y, z, state);
                            }
                        }
                    }
                }
            }
        }
        return container;
    }

    /**
     * Creates an isolated clone of an existing PalettedContainer and applies block mutations in worker threads.
     * <p>
     * <b>Note on Mojang SingleValuePalette bug:</b> Calling {@code base.copy()} on a container holding a
     * {@link net.minecraft.world.level.chunk.SingleValuePalette} returns a clone sharing the exact same
     * palette instance whose resize handler points to the original container. If mutated, the clone's
     * ZeroBitStorage is never resized and throws an {@link IllegalArgumentException}.
     * To prevent this, single-valued or empty containers are recreated afresh via {@link PalettedContainer#recreate()}.
     * </p>
     *
     * @param base Existing PalettedContainer to clone, or null if creating from scratch
     * @return Cloned PalettedContainer ready for worker thread mutation
     */
    public static PalettedContainer<BlockState> cloneOrNew(PalettedContainer<BlockState> base) {
        if (base != null) {
            try {
                int[] distinctStates = new int[1];
                base.count((state, count) -> distinctStates[0]++);
                if (distinctStates[0] <= 1) {
                    return base.recreate();
                }
            } catch (Throwable ignored) {
            }
            return base.copy();
        }
        return new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES
        );
    }
}
