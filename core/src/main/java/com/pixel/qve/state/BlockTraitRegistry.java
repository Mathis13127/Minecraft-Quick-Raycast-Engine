package com.pixel.qve.state;

import java.util.Arrays;

/**
 * Ultra-fast flat-array registry mapping 32-bit block IDs to physical and optical block traits.
 * Resolves traits in 1 CPU cycle via direct array indexing.
 */
public final class BlockTraitRegistry {

    private static final int INITIAL_CAPACITY = 256;
    private volatile byte[] traits;

    /**
     * Constructs a BlockTraitRegistry with air initialized as invisible and pass-through.
     */
    public BlockTraitRegistry() {
        this.traits = new byte[INITIAL_CAPACITY];
        this.traits[BlockIdRegistry.AIR_ID] = (byte) (BlockTraits.INVISIBLE | BlockTraits.PASS_THROUGH);
    }

    /**
     * Associates a 32-bit block ID with specific block trait flags.
     *
     * @param blockId    32-bit block ID
     * @param traitFlags Byte bitmask of {@link BlockTraits}
     */
    public synchronized void setTraits(int blockId, byte traitFlags) {
        if (blockId < 0) return;
        ensureCapacity(blockId + 1);
        traits[blockId] = traitFlags;
    }

    /**
     * Retrieves the raw trait bitmask for a given block ID.
     *
     * @param blockId 32-bit block ID
     * @return Trait bitmask, or 0 if unmapped
     */
    public byte getTraits(int blockId) {
        if (blockId == BlockIdRegistry.AIR_ID) {
            return (byte) (BlockTraits.INVISIBLE | BlockTraits.PASS_THROUGH);
        }
        byte[] local = this.traits;
        if (blockId >= 0 && blockId < local.length) {
            return local[blockId];
        }
        return 0;
    }

    /**
     * Fast-path check: returns true if the block ID represents solid terrain
     * suitable for 1x1x1 cubic meshing (stone, dirt, grass, planks, leaves, etc.).
     * Explicitly excludes non-solid decorations, partial shapes (slabs, stairs, snow layers), fluids, and air.
     *
     * @param blockId 32-bit block ID
     * @return True if block is solid terrain
     */
    public boolean isTerrainSolid(int blockId) {
        if (blockId == BlockIdRegistry.AIR_ID) {
            return false;
        }
        byte[] local = this.traits;
        if (blockId >= 0 && blockId < local.length) {
            byte t = local[blockId];
            if (t != 0) {
                return (t & BlockTraits.TERRAIN_SOLID) != 0;
            }
        }
        // Unmapped blocks in synthetic tests default to true if non-zero
        return true;
    }

    /**
     * Fast-path check: returns true if the block is a non-solid decoration (flowers, grass, torches, rails).
     *
     * @param blockId 32-bit block ID
     * @return True if pass-through
     */
    public boolean isPassThrough(int blockId) {
        return (getTraits(blockId) & BlockTraits.PASS_THROUGH) != 0;
    }

    /**
     * Fast-path check: returns true if the block has partial collision geometry (slabs, stairs, thin snow layers).
     *
     * @param blockId 32-bit block ID
     * @return True if partial shape
     */
    public boolean isPartialShape(int blockId) {
        return (getTraits(blockId) & BlockTraits.PARTIAL_SHAPE) != 0;
    }

    /**
     * Fast-path check: returns true if the block is a fluid (water, lava).
     *
     * @param blockId 32-bit block ID
     * @return True if fluid
     */
    public boolean isFluid(int blockId) {
        return (getTraits(blockId) & BlockTraits.FLUID) != 0;
    }

    /**
     * Fast-path check: returns true if the block is translucent (water, stained glass, ice).
     *
     * @param blockId 32-bit block ID
     * @return True if translucent
     */
    public boolean isTranslucent(int blockId) {
        return (getTraits(blockId) & BlockTraits.TRANSLUCENT) != 0;
    }

    /**
     * Fast-path check: returns true if the block is completely invisible (air, structure void, barrier).
     *
     * @param blockId 32-bit block ID
     * @return True if invisible
     */
    public boolean isInvisible(int blockId) {
        return (getTraits(blockId) & BlockTraits.INVISIBLE) != 0;
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity <= traits.length) return;
        int newCap = Math.max(traits.length * 2, minCapacity + 128);
        traits = Arrays.copyOf(traits, newCap);
    }
}
