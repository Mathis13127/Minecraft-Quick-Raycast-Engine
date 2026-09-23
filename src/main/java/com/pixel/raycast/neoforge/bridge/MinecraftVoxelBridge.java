package com.pixel.raycast.neoforge.bridge;

import com.pixel.raycast.core.api.IVoxelGrid;
import com.pixel.raycast.core.cache.VoxelCache;
import com.pixel.raycast.core.cache.VoxelChunkColumn;
import com.pixel.raycast.core.mca.McaVoxelGrid;
import com.pixel.raycast.core.shape.ShapeRegistry;
import com.pixel.raycast.core.shape.VoxelShape;
import com.pixel.raycast.core.voxel.BlockIdRegistry;
import com.pixel.raycast.core.voxel.VoxelSection;
import com.pixel.raycast.neoforge.mixin.IRaycastChunkSection;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance bridge synchronizing the live Minecraft world state with
 * the lock-free VoxelCache and sub-voxel ShapeRegistry.
 */
public final class MinecraftVoxelBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftVoxelBridge.class);

    private static final BlockIdRegistry BLOCK_REGISTRY = new BlockIdRegistry();
    private static final ShapeRegistry SHAPE_REGISTRY = new ShapeRegistry();
    private static final Map<BlockState, Short> STATE_TO_ID = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, MinecraftVoxelGrid> WORLD_GRIDS = new ConcurrentHashMap<>();

    private MinecraftVoxelBridge() {}

    /**
     * Retrieves the global BlockIdRegistry used across Minecraft levels.
     *
     * @return Global BlockIdRegistry instance
     */
    public static BlockIdRegistry getBlockRegistry() {
        return BLOCK_REGISTRY;
    }

    /**
     * Retrieves the global ShapeRegistry used for sub-voxel collision shapes.
     *
     * @return Global ShapeRegistry instance
     */
    public static ShapeRegistry getShapeRegistry() {
        return SHAPE_REGISTRY;
    }

    /**
     * Resolves the compact 16-bit block identifier for a Minecraft BlockState in ~3 nanoseconds.
     * Automatically extracts sub-voxel shapes for slabs, stairs, panes, and trapdoors.
     *
     * @param state The vanilla BlockState
     * @return 16-bit numeric block ID (0 for air)
     */
    public static short getBlockId(BlockState state) {
        if (state == null || state.isAir()) {
            return BlockIdRegistry.AIR_ID;
        }

        Short cachedId = STATE_TO_ID.get(state);
        if (cachedId != null) {
            return cachedId;
        }

        return registerBlockState(state);
    }

    private static synchronized short registerBlockState(BlockState state) {
        Short existing = STATE_TO_ID.get(state);
        if (existing != null) {
            return existing;
        }

        Block block = state.getBlock();
        String blockKey = BuiltInRegistries.BLOCK.getKey(block).toString();
        String fullStateKey = state.toString();

        short id = BLOCK_REGISTRY.getOrRegister(fullStateKey);
        VoxelShape shape = resolveShapeForState(state, block);
        if (shape != VoxelShape.FULL_CUBE) {
            SHAPE_REGISTRY.registerShape(id, shape);
        }

        STATE_TO_ID.put(state, id);
        return id;
    }

    private static VoxelShape resolveShapeForState(BlockState state, Block block) {
        if (block instanceof SlabBlock && state.hasProperty(SlabBlock.TYPE)) {
            SlabType type = state.getValue(SlabBlock.TYPE);
            if (type == SlabType.BOTTOM) {
                return VoxelShape.SLAB_BOTTOM;
            } else if (type == SlabType.TOP) {
                return VoxelShape.SLAB_TOP;
            } else {
                return VoxelShape.FULL_CUBE;
            }
        }

        if (block instanceof StairBlock && state.hasProperty(StairBlock.FACING) && state.hasProperty(StairBlock.HALF)) {
            Direction facing = state.getValue(StairBlock.FACING);
            Half half = state.getValue(StairBlock.HALF);
            if (half == Half.BOTTOM) {
                return switch (facing) {
                    case NORTH -> VoxelShape.STAIRS_NORTH_BOTTOM;
                    case SOUTH -> VoxelShape.STAIRS_SOUTH_BOTTOM;
                    case WEST -> VoxelShape.STAIRS_WEST_BOTTOM;
                    default -> VoxelShape.STAIRS_EAST_BOTTOM;
                };
            } else {
                return switch (facing) {
                    case NORTH -> VoxelShape.STAIRS_NORTH_TOP;
                    case SOUTH -> VoxelShape.STAIRS_SOUTH_TOP;
                    case WEST -> VoxelShape.STAIRS_WEST_TOP;
                    default -> VoxelShape.STAIRS_EAST_TOP;
                };
            }
        }

        if (block instanceof IronBarsBlock) {
            return VoxelShape.PANE_CROSS;
        }

        if (block instanceof TrapDoorBlock && state.hasProperty(TrapDoorBlock.HALF)) {
            Half half = state.getValue(TrapDoorBlock.HALF);
            return half == Half.BOTTOM ? VoxelShape.TRAPDOOR_BOTTOM : VoxelShape.TRAPDOOR_TOP;
        }

        return VoxelShape.FULL_CUBE;
    }

    /**
     * Retrieves or creates the unified MinecraftVoxelGrid for the specified Level.
     * Automatically hooks up Anvil MCA disk loading if the Level is a ServerLevel with region files.
     *
     * @param level Minecraft Level instance
     * @return MinecraftVoxelGrid bound to this dimension
     */
    public static MinecraftVoxelGrid getOrCreateGrid(Level level) {
        ResourceKey<Level> key = level.dimension();
        return WORLD_GRIDS.computeIfAbsent(key, k -> createGridForLevel(level));
    }

    private static MinecraftVoxelGrid createGridForLevel(Level level) {
        IVoxelGrid diskFallback = null;
        if (level instanceof ServerLevel serverLevel) {
            try {
                Path rootPath = serverLevel.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
                Path dimFolder = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(serverLevel.dimension(), rootPath);
                Path regionDir = dimFolder.resolve("region");
                if (Files.isDirectory(regionDir)) {
                    diskFallback = new McaVoxelGrid(BLOCK_REGISTRY, regionDir);
                    LOGGER.info("Raycast Bridge: Connected Anvil disk fallback for dimension {} at {}",
                            level.dimension().location(), regionDir);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to resolve Anvil region directory for level {}: {}",
                        level.dimension().location(), e.getMessage(), e);
            }
        }

        int minSectionY = level.getMinSection();
        int maxSectionY = level.getMaxSection();
        VoxelCache cache = new VoxelCache(BLOCK_REGISTRY, SHAPE_REGISTRY, diskFallback, minSectionY, maxSectionY);
        return new MinecraftVoxelGrid(level, cache);
    }

    /**
     * Compiles a vanilla LevelChunkSection into an optimized VoxelSection and binds it
     * to the LevelChunkSection via IRaycastChunkSection.
     *
     * @param vanillaSection Vanilla chunk section instance
     * @param column         Owning VoxelChunkColumn
     * @param sectionY       Vertical section index
     * @return Newly compiled VoxelSection
     */
    public static VoxelSection compileSection(LevelChunkSection vanillaSection, VoxelChunkColumn column, int sectionY) {
        if (vanillaSection == null || vanillaSection.hasOnlyAir()) {
            VoxelSection empty = new VoxelSection();
            if (column != null) {
                column.setSection(sectionY, empty);
            }
            if (vanillaSection instanceof IRaycastChunkSection bridge) {
                bridge.raycast$setVoxelSection(empty);
                bridge.raycast$setVoxelColumn(column, sectionY);
            }
            return empty;
        }

        VoxelSection compiled = new VoxelSection();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = vanillaSection.getBlockState(x, y, z);
                    if (!state.isAir()) {
                        short id = getBlockId(state);
                        compiled.setVoxel(x, y, z, true, id);
                    }
                }
            }
        }

        if (column != null) {
            column.setSection(sectionY, compiled);
        }
        if (vanillaSection instanceof IRaycastChunkSection bridge) {
            bridge.raycast$setVoxelSection(compiled);
            bridge.raycast$setVoxelColumn(column, sectionY);
        }
        return compiled;
    }

    /**
     * Called by the LevelChunkSection mixin on setBlockState to perform instant lock-free dirty tracking.
     *
     * @param section  Modified chunk section
     * @param localX   Block local X [0..15]
     * @param localY   Block local Y [0..15]
     * @param localZ   Block local Z [0..15]
     * @param newState Newly assigned BlockState
     */
    public static void onBlockStateChanged(IRaycastChunkSection section, int localX, int localY, int localZ, BlockState newState) {
        if (section != null) {
            VoxelSection voxelSection = section.raycast$getVoxelSection();
            if (voxelSection != null) {
                boolean solid = !newState.isAir();
                short blockId = solid ? getBlockId(newState) : BlockIdRegistry.AIR_ID;
                voxelSection.setVoxel(localX, localY, localZ, solid, blockId);

                VoxelChunkColumn column = section.raycast$getVoxelColumn();
                if (column != null) {
                    int worldY = (section.raycast$getSectionY() << 4) | localY;
                    column.onVoxelChanged(localX, worldY, localZ, solid);
                }
            }
        }
    }

    /**
     * Clears cached resources when a level unloads.
     *
     * @param level Unloaded Level instance
     */
    public static void onLevelUnloaded(Level level) {
        WORLD_GRIDS.remove(level.dimension());
    }

    /**
     * Resets all internal caches. Used during testing and server reloads.
     */
    public static void reset() {
        WORLD_GRIDS.clear();
        STATE_TO_ID.clear();
    }
}
