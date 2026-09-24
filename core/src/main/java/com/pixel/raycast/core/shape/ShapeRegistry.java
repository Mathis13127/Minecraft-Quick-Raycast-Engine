package com.pixel.raycast.core.shape;

import com.pixel.raycast.core.voxel.BlockIdRegistry;

import java.util.Arrays;
import java.util.Objects;

/**
 * High-speed flat-array registry mapping 16-bit block IDs to collision shapes (VoxelShape).
 * Resolves shapes in 1 CPU cycle via direct array indexing.
 */
public final class ShapeRegistry {

    private static final int INITIAL_CAPACITY = 256;
    private volatile VoxelShape[] shapes;

    /**
     * Constructs a ShapeRegistry initialized to full cubes, with air reserved as empty.
     */
    public ShapeRegistry() {
        this.shapes = new VoxelShape[INITIAL_CAPACITY];
        Arrays.fill(shapes, VoxelShape.FULL_CUBE);
        shapes[BlockIdRegistry.AIR_ID] = VoxelShape.EMPTY;
    }

    /**
     * Associates a 16-bit block ID with a specific sub-box collision shape.
     *
     * @param blockId 16-bit block ID
     * @param shape   VoxelShape collision model
     */
    public synchronized void registerShape(short blockId, VoxelShape shape) {
        Objects.requireNonNull(shape, "VoxelShape cannot be null");
        int index = blockId & 0xFFFF;
        ensureCapacity(index + 1);
        shapes[index] = shape;
    }

    /**
     * Retrieves the collision shape for a given block ID.
     *
     * @param blockId 16-bit block ID
     * @return Associated VoxelShape, or FULL_CUBE if unmapped
     */
    public VoxelShape getShape(short blockId) {
        if (blockId == BlockIdRegistry.AIR_ID) {
            return VoxelShape.EMPTY;
        }
        int index = blockId & 0xFFFF;
        VoxelShape[] localShapes = this.shapes;
        if (index < localShapes.length) {
            VoxelShape shape = localShapes[index];
            return (shape != null) ? shape : VoxelShape.FULL_CUBE;
        }
        return VoxelShape.FULL_CUBE;
    }

    /**
     * Automatically registers standard Minecraft shapes by matching block name patterns.
     *
     * @param blockRegistry Source BlockIdRegistry to inspect
     */
    public void registerDefaultVanillaShapes(BlockIdRegistry blockRegistry) {
        for (short id = 1; id < blockRegistry.size(); id++) {
            String name = blockRegistry.getName(id);
            if (name == null) continue;

            if (name.contains("_slab") && !name.contains("double")) {
                registerShape(id, VoxelShape.SLAB_BOTTOM);
            } else if (name.contains("_stairs")) {
                registerShape(id, VoxelShape.STAIRS_NORTH_BOTTOM);
            } else if (name.contains("_pane") || name.contains("iron_bars")) {
                registerShape(id, VoxelShape.PANE_CROSS);
            } else if (name.contains("_trapdoor")) {
                registerShape(id, VoxelShape.TRAPDOOR_BOTTOM);
            }
        }
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity > shapes.length) {
            int newCap = Math.max(shapes.length * 2, minCapacity);
            VoxelShape[] newShapes = Arrays.copyOf(shapes, newCap);
            // Default new entries to FULL_CUBE
            for (int i = shapes.length; i < newCap; i++) {
                newShapes[i] = VoxelShape.FULL_CUBE;
            }
            this.shapes = newShapes;
        }
    }
}
