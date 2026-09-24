package com.pixel.qve.state;

/**
 * High-performance bit flags defining physical, optical, and structural traits of block states.
 * Pre-indexed in O(1) CPU array during warmup or state discovery.
 */
public final class BlockTraits {

    /** Fully opaque, solid 1x1x1 cube (stone, dirt, planks, etc.). Suitable for solid terrain LOD. */
    public static final byte TERRAIN_SOLID    = 1 << 0;

    /** Non-solid pass-through decoration without collision (grass, flowers, torches, saplings, rails, vines). */
    public static final byte PASS_THROUGH     = 1 << 1;

    /** Partial or non-cube collision shape (slabs, stairs, fences, walls, thin snow layers). Explicitly ignored for simple 1x1x1 meshing. */
    public static final byte PARTIAL_SHAPE    = 1 << 2;

    /** Fluid matter (water, lava). */
    public static final byte FLUID            = 1 << 3;

    /** Translucent block requiring alpha blending or sorting (water, stained glass, ice, slime). */
    public static final byte TRANSLUCENT      = 1 << 4;

    /** Foliage / Leaves block (full cube or dense cutout canopy). */
    public static final byte FOLIAGE          = 1 << 5;

    /** Completely invisible block producing no visual quads (air, structure void, light block, barrier). */
    public static final byte INVISIBLE        = 1 << 6;

    private BlockTraits() {}
}
