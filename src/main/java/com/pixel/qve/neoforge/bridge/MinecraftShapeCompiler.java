package com.pixel.qve.neoforge.bridge;

import com.pixel.qve.state.BlockTraits;
import com.pixel.qve.state.SubBox;
import com.pixel.qve.state.VoxelShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Dedicated compiler transforming Minecraft {@link BlockState} and collision models
 * into lightweight, SIMD-friendly {@link VoxelShape} geometry and bitwise {@link BlockTraits}.
 */
public final class MinecraftShapeCompiler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftShapeCompiler.class);

    private MinecraftShapeCompiler() {}

    /**
     * Resolves the sub-voxel collision model {@link VoxelShape} for a given {@link BlockState}.
     *
     * @param state Target BlockState
     * @param block Target Block instance
     * @return Compact VoxelShape instance (EMPTY, FULL_CUBE, or custom SubBox array)
     */
    public static VoxelShape resolveShapeForState(BlockState state, Block block) {
        if (state == null || state.isAir()) {
            return VoxelShape.EMPTY;
        }

        if (state.getFluidState() != null && !state.getFluidState().isEmpty()) {
            FluidState fluid = state.getFluidState();
            float fluidHeight = fluid.isSource() ? 0.8888889f : Math.max(0.125f, (float) fluid.getAmount() / 8.0f * 0.8888889f);
            return new VoxelShape(new SubBox[]{
                    new SubBox(0f, 0f, 0f, 1f, fluidHeight, 1f)
            }, false);
        }

        try {
            net.minecraft.world.phys.shapes.VoxelShape mcShape =
                    state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);

            if (mcShape.isEmpty()) {
                return VoxelShape.EMPTY;
            }

            List<AABB> aabbs = mcShape.toAabbs();
            if (aabbs.isEmpty()) {
                return VoxelShape.EMPTY;
            }

            if (aabbs.size() == 1) {
                AABB b = aabbs.get(0);
                if (b.minX <= 0.001 && b.minY <= 0.001 && b.minZ <= 0.001
                        && b.maxX >= 0.999 && b.maxY >= 0.999 && b.maxZ >= 0.999) {
                    return VoxelShape.FULL_CUBE;
                }
            }

            SubBox[] boxes = new SubBox[aabbs.size()];
            for (int i = 0; i < aabbs.size(); i++) {
                AABB b = aabbs.get(i);
                boxes[i] = new SubBox(
                        (float) Math.max(0.0, Math.min(1.0, b.minX)),
                        (float) Math.max(0.0, Math.min(1.0, b.minY)),
                        (float) Math.max(0.0, Math.min(1.0, b.minZ)),
                        (float) Math.max(0.0, Math.min(1.0, b.maxX)),
                        (float) Math.max(0.0, Math.min(1.0, b.maxY)),
                        (float) Math.max(0.0, Math.min(1.0, b.maxZ))
                );
            }
            return new VoxelShape(boxes);
        } catch (Throwable t) {
            LOGGER.warn("Failed to dynamically compute collision shape for state {}: {}", state, t.getMessage());
            return VoxelShape.FULL_CUBE;
        }
    }

    /**
     * Resolves physical and optical classification flags {@link BlockTraits} for a given {@link BlockState}.
     *
     * @param state Target BlockState
     * @param block Target Block instance
     * @param shape Pre-computed VoxelShape
     * @return Bitwise traits byte
     */
    public static byte resolveTraitsForState(BlockState state, Block block, VoxelShape shape) {
        if (state == null || state.isAir()) {
            return (byte) (BlockTraits.INVISIBLE | BlockTraits.PASS_THROUGH);
        }

        byte traits = 0;

        // 1. Fluid matter (fluids are visible matter, never invisible)
        if (!state.getFluidState().isEmpty()) {
            traits |= BlockTraits.FLUID;
            traits |= BlockTraits.TRANSLUCENT;
            traits |= BlockTraits.PASS_THROUGH;
            return traits;
        }

        // 2. Invisible render shape (air, structure void, barrier, light block)
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            traits |= BlockTraits.INVISIBLE;
        }

        // 3. Collision shape and solidity classification
        try {
            net.minecraft.world.phys.shapes.VoxelShape mcShape =
                    state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);

            if (mcShape.isEmpty()) {
                // Non-solid pass-through decoration (grass, flowers, torches, rails, saplings, etc.)
                traits |= BlockTraits.PASS_THROUGH;
                if (block instanceof BushBlock
                        || block instanceof SugarCaneBlock
                        || state.is(BlockTags.FLOWERS)
                        || state.is(BlockTags.CROPS)
                        || state.is(BlockTags.SAPLINGS)) {
                    traits |= BlockTraits.CROSS_PLANT;
                }
            } else if (state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
                // Full 1x1x1 cube (stone, dirt, grass, planks, ores, ice, glass, etc.)
                traits |= BlockTraits.TERRAIN_SOLID;
                if (!state.canOcclude()) {
                    traits |= BlockTraits.TRANSLUCENT;
                }
            } else if (state.is(BlockTags.LEAVES)) {
                // Tree canopy foliage: full cube with cutout texture
                traits |= (BlockTraits.TERRAIN_SOLID | BlockTraits.FOLIAGE);
            } else {
                // Partial geometry (slabs, stairs, fences, walls, thin snow layers, trapdoors)
                traits |= BlockTraits.PARTIAL_SHAPE;
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to dynamically compute block traits for state {}: {}", state, t.getMessage());
            traits |= BlockTraits.TERRAIN_SOLID;
        }

        return traits;
    }

    /**
     * Parses a canonical Minecraft state string (e.g., "minecraft:oak_stairs[facing=east,half=bottom]")
     * into a live {@link BlockState}.
     *
     * @param str Canonical block state string
     * @return BlockState instance, or null if unknown
     */
    public static BlockState parseBlockStateString(String str) {
        if (str == null || str.isEmpty()) {
            return null;
        }
        int bracketIndex = str.indexOf('[');
        String name = bracketIndex >= 0 ? str.substring(0, bracketIndex) : str;
        ResourceLocation rl = ResourceLocation.tryParse(name);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
            return null;
        }
        Block block = BuiltInRegistries.BLOCK.get(rl);
        BlockState state = block.defaultBlockState();

        if (bracketIndex >= 0 && str.endsWith("]")) {
            String propsStr = str.substring(bracketIndex + 1, str.length() - 1);
            String[] pairs = propsStr.split(",");
            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String k = pair.substring(0, eq).trim();
                    String v = pair.substring(eq + 1).trim();
                    Property<?> prop = block.getStateDefinition().getProperty(k);
                    if (prop != null) {
                        state = applyProperty(state, prop, v);
                    }
                }
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState applyProperty(
            BlockState state, Property<T> prop, String valStr) {
        Optional<T> parsed = prop.getValue(valStr);
        return parsed.map(t -> state.setValue(prop, t)).orElse(state);
    }
}
