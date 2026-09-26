package com.pixel.qve.state;

import com.pixel.qve.state.BlockIdRegistry;

import java.util.Arrays;
import java.util.Objects;

/**
 * High-speed flat-array registry mapping 16-bit block IDs to collision shapes (VoxelShape).
 * Resolves shapes in 1 CPU cycle via direct array indexing.
 */
public final class ShapeRegistry {

    private static final int INITIAL_CAPACITY = 256;
    private volatile VoxelShape[] shapes;
    private volatile long[] isFullCubeBits;

    /**
     * Constructs a ShapeRegistry initialized to full cubes, with air reserved as empty.
     */
    public ShapeRegistry() {
        this.shapes = new VoxelShape[INITIAL_CAPACITY];
        this.isFullCubeBits = new long[(INITIAL_CAPACITY + 63) / 64];
        Arrays.fill(shapes, VoxelShape.FULL_CUBE);
        Arrays.fill(isFullCubeBits, ~0L);
        shapes[BlockIdRegistry.AIR_ID] = VoxelShape.EMPTY;
        isFullCubeBits[BlockIdRegistry.AIR_ID >>> 6] &= ~(1L << (BlockIdRegistry.AIR_ID & 63));
    }

    /**
     * Associates a 32-bit block ID with a specific sub-box collision shape.
     *
     * @param blockId 32-bit block ID
     * @param shape   VoxelShape collision model
     */
    public synchronized void registerShape(int blockId, VoxelShape shape) {
        Objects.requireNonNull(shape, "VoxelShape cannot be null");
        if (blockId < 0) return;
        int index = blockId;
        ensureCapacity(index + 1);
        shapes[index] = shape;
        int word = index >>> 6;
        long bit = 1L << (index & 63);
        if (shape.isFullCube()) {
            isFullCubeBits[word] |= bit;
        } else {
            isFullCubeBits[word] &= ~bit;
        }
    }

    /**
     * Fast-path check: returns whether a block ID has a full 1x1x1 cube collision shape
     * in a single CPU cycle, bypassing VoxelShape object inspection.
     *
     * @param blockId 32-bit block ID
     * @return True if the block is a solid full cube
     */
    public boolean isFullCube(int blockId) {
        if (blockId == BlockIdRegistry.AIR_ID) {
            return false;
        }
        int index = blockId;
        long[] local = this.isFullCubeBits;
        int word = index >>> 6;
        if (word >= 0 && word < local.length) {
            return (local[word] & (1L << (index & 63))) != 0L;
        }
        return true; // Unmapped blocks default to full cube
    }

    /**
     * Retrieves the collision shape for a given block ID.
     *
     * @param blockId 32-bit block ID
     * @return Associated VoxelShape, or FULL_CUBE if unmapped
     */
    public VoxelShape getShape(int blockId) {
        if (blockId == BlockIdRegistry.AIR_ID) {
            return VoxelShape.EMPTY;
        }
        int index = blockId;
        VoxelShape[] localShapes = this.shapes;
        if (index >= 0 && index < localShapes.length) {
            VoxelShape shape = localShapes[index];
            return (shape != null) ? shape : VoxelShape.FULL_CUBE;
        }
        return VoxelShape.FULL_CUBE;
    }

    /**
     * Fast-path check: returns the constituent SubBox array for a block ID in 1 CPU cycle.
     *
     * @param blockId 32-bit block ID
     * @return Array of SubBoxes representing this block
     */
    public SubBox[] getBoxes(int blockId) {
        return getShape(blockId).getBoxes();
    }

    /**
     * Automatically registers standard Minecraft shapes by matching block name patterns.
     *
     * @param blockRegistry Source BlockIdRegistry to inspect
     */
    public void registerDefaultVanillaShapes(BlockIdRegistry blockRegistry) {
        for (int id = 1; id < blockRegistry.size(); id++) {
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

    /**
     * Counts the number of custom non-full-cube shapes registered.
     *
     * @return Number of custom shapes
     */
    public int getCustomShapeCount() {
        int count = 0;
        VoxelShape[] local = this.shapes;
        for (int i = 0; i < local.length; i++) {
            if (local[i] != null && !local[i].isFullCube() && i != BlockIdRegistry.AIR_ID) {
                count++;
            }
        }
        return count;
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity > shapes.length) {
            int newCap = Math.max(shapes.length * 2, minCapacity);
            VoxelShape[] newShapes = Arrays.copyOf(shapes, newCap);
            int newWords = (newCap + 63) / 64;
            long[] newFullCubeBits = Arrays.copyOf(isFullCubeBits, newWords);
            // Default new entries to FULL_CUBE (all bits set to 1)
            int oldWords = isFullCubeBits.length;
            if (newWords > oldWords) {
                Arrays.fill(newFullCubeBits, oldWords, newWords, ~0L);
            }
            for (int i = shapes.length; i < newCap; i++) {
                newShapes[i] = VoxelShape.FULL_CUBE;
            }
            this.shapes = newShapes;
            this.isFullCubeBits = newFullCubeBits;
        }
    }
}
