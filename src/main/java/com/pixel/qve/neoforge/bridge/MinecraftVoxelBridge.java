package com.pixel.qve.neoforge.bridge;

import com.pixel.qve.api.IVoxelGrid;


import com.pixel.qve.api.IVoxelWorld;
import com.pixel.qve.world.cache.UnifiedVoxelCache;
import com.pixel.qve.world.VoxelChunkColumn;
import com.pixel.qve.mca.McaVoxelGrid;
import com.pixel.qve.state.ShapeRegistry;
import com.pixel.qve.state.VoxelShape;
import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.state.BlockTraits;
import com.pixel.qve.state.BlockTraitRegistry;
import com.pixel.qve.world.VoxelSection;
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
 * the lock-free UnifiedVoxelCache and sub-voxel ShapeRegistry.
 */
public final class MinecraftVoxelBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftVoxelBridge.class);

    private static final BlockIdRegistry BLOCK_REGISTRY = new BlockIdRegistry();
    private static final ShapeRegistry SHAPE_REGISTRY = new ShapeRegistry();
    private static final BlockTraitRegistry TRAIT_REGISTRY = new BlockTraitRegistry();
    private static final Map<BlockState, Integer> STATE_TO_ID = new ConcurrentHashMap<>();
    private static volatile int[] STATE_ID_ARRAY = new int[32768];
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
     * Retrieves the global BlockTraitRegistry used for physical/optical block traits.
     *
     * @return Global BlockTraitRegistry instance
     */
    public static BlockTraitRegistry getTraitRegistry() {
        return TRAIT_REGISTRY;
    }

    /**
     * Resolves the compact 32-bit block identifier for a Minecraft BlockState in ~1 CPU cycle.
     * Uses direct dense array indexing from Block.getId(state), bypassing hash map lookups.
     *
     * @param state The vanilla BlockState
     * @return 32-bit numeric block ID (0 for air)
     */
    public static int getBlockId(BlockState state) {
        if (state == null || state.isAir()) {
            return BlockIdRegistry.AIR_ID;
        }

        int stateId = Block.getId(state);
        if (stateId > 0) {
            int[] arr = STATE_ID_ARRAY;
            if (stateId < arr.length) {
                int id = arr[stateId];
                if (id != 0) {
                    return id;
                }
            }
        } else {
            Integer mapped = STATE_TO_ID.get(state);
            if (mapped != null) {
                return mapped;
            }
        }

        return registerBlockState(state, stateId);
    }

    private static synchronized int registerBlockState(BlockState state, int stateId) {
        if (stateId > 0) {
            int[] arr = STATE_ID_ARRAY;
            if (stateId < arr.length) {
                int existing = arr[stateId];
                if (existing != 0) {
                    return existing;
                }
            }
        } else {
            Integer mapped = STATE_TO_ID.get(state);
            if (mapped != null) {
                return mapped;
            }
        }

        Block block = state.getBlock();
        String fullStateKey = state.toString();

        int id = BLOCK_REGISTRY.getOrRegister(fullStateKey);
        VoxelShape shape = resolveShapeForState(state, block);
        if (shape != VoxelShape.FULL_CUBE) {
            SHAPE_REGISTRY.registerShape(id, shape);
        }
        byte traits = resolveTraitsForState(state, block, shape);
        TRAIT_REGISTRY.setTraits(id, traits);

        var propRegistry = BLOCK_REGISTRY.getStateDictionary().getPropertyRegistry();
        var values = state.getValues();
        if (!values.isEmpty()) {
            short[] pairs = new short[values.size() * 2];
            int pIdx = 0;
            for (var entry : values.entrySet()) {
                String kName = entry.getKey().getName();
                @SuppressWarnings({"rawtypes", "unchecked"})
                net.minecraft.world.level.block.state.properties.Property prop = entry.getKey();
                @SuppressWarnings("unchecked")
                String vName = prop.getName(entry.getValue());
                short kId = propRegistry.getOrRegisterKey(kName);
                short vId = propRegistry.getOrRegisterValue(kId, vName);
                pairs[pIdx++] = kId;
                pairs[pIdx++] = vId;
            }
            propRegistry.registerBlockProperties(id, pairs);
        }

        if (stateId > 0) {
            int[] arr = STATE_ID_ARRAY;
            if (stateId >= arr.length) {
                int newCap = Math.max(arr.length * 2, stateId + 1024);
                int[] newArr = java.util.Arrays.copyOf(arr, newCap);
                newArr[stateId] = id;
                STATE_ID_ARRAY = newArr;
            } else {
                arr[stateId] = id;
            }
        }

        STATE_TO_ID.put(state, id);
        return id;
    }

    static {
        BLOCK_REGISTRY.getStateDictionary().setRegistrationListener(MinecraftVoxelBridge::onStateDiscovered);
    }

    private static void onStateDiscovered(int blockId, String canonicalState) {
        if (canonicalState == null || canonicalState.isEmpty() || canonicalState.equals(BlockIdRegistry.AIR_NAME)) {
            return;
        }
        try {
            BlockState state = parseBlockStateString(canonicalState);
            if (state != null) {
                VoxelShape shape = resolveShapeForState(state, state.getBlock());
                if (shape != VoxelShape.FULL_CUBE) {
                    SHAPE_REGISTRY.registerShape(blockId, shape);
                }
                byte traits = resolveTraitsForState(state, state.getBlock(), shape);
                TRAIT_REGISTRY.setTraits(blockId, traits);
            }
        } catch (Throwable t) {
            LOGGER.debug("Could not resolve dynamic shape for state {}: {}", canonicalState, t.getMessage());
        }
    }

    private static BlockState parseBlockStateString(String str) {
        int bracketIndex = str.indexOf('[');
        String name = bracketIndex >= 0 ? str.substring(0, bracketIndex) : str;
        net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(name);
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
                    net.minecraft.world.level.block.state.properties.Property<?> prop =
                            block.getStateDefinition().getProperty(k);
                    if (prop != null) {
                        state = applyProperty(state, prop, v);
                    }
                }
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState applyProperty(
            BlockState state, net.minecraft.world.level.block.state.properties.Property<T> prop, String valStr) {
        java.util.Optional<T> parsed = prop.getValue(valStr);
        return parsed.map(t -> state.setValue(prop, t)).orElse(state);
    }

    private static VoxelShape resolveShapeForState(BlockState state, Block block) {
        if (state == null || state.isAir()) {
            return VoxelShape.EMPTY;
        }

        if (!state.getFluidState().isEmpty()) {
            net.minecraft.world.level.material.FluidState fluid = state.getFluidState();
            float fluidHeight = fluid.isSource() ? 0.8888889f : Math.max(0.125f, (float) fluid.getAmount() / 8.0f * 0.8888889f);
            return new VoxelShape(new com.pixel.qve.state.SubBox[]{
                new com.pixel.qve.state.SubBox(0f, 0f, 0f, 1f, fluidHeight, 1f)
            }, false);
        }

        try {
            net.minecraft.world.phys.shapes.VoxelShape mcShape =
                    state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO);

            if (mcShape.isEmpty()) {
                return VoxelShape.EMPTY;
            }

            java.util.List<net.minecraft.world.phys.AABB> aabbs = mcShape.toAabbs();
            if (aabbs.isEmpty()) {
                return VoxelShape.EMPTY;
            }

            if (aabbs.size() == 1) {
                net.minecraft.world.phys.AABB b = aabbs.get(0);
                if (b.minX <= 0.001 && b.minY <= 0.001 && b.minZ <= 0.001
                        && b.maxX >= 0.999 && b.maxY >= 0.999 && b.maxZ >= 0.999) {
                    return VoxelShape.FULL_CUBE;
                }
            }

            com.pixel.qve.state.SubBox[] boxes = new com.pixel.qve.state.SubBox[aabbs.size()];
            for (int i = 0; i < aabbs.size(); i++) {
                net.minecraft.world.phys.AABB b = aabbs.get(i);
                boxes[i] = new com.pixel.qve.state.SubBox(
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

    private static byte resolveTraitsForState(BlockState state, Block block, VoxelShape shape) {
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
        if (state.getRenderShape() == net.minecraft.world.level.block.RenderShape.INVISIBLE) {
            traits |= BlockTraits.INVISIBLE;
        }

        // 3. Collision shape and solidity classification
        try {
            net.minecraft.world.phys.shapes.VoxelShape mcShape =
                    state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO);

            if (mcShape.isEmpty()) {
                // Non-solid pass-through decoration (grass, flowers, torches, rails, saplings, etc.)
                traits |= BlockTraits.PASS_THROUGH;
                if (block instanceof net.minecraft.world.level.block.BushBlock
                        || block instanceof net.minecraft.world.level.block.SugarCaneBlock
                        || state.is(net.minecraft.tags.BlockTags.FLOWERS)
                        || state.is(net.minecraft.tags.BlockTags.CROPS)
                        || state.is(net.minecraft.tags.BlockTags.SAPLINGS)) {
                    traits |= BlockTraits.CROSS_PLANT;
                }
            } else if (state.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO)) {
                // Full 1x1x1 cube (stone, dirt, grass, planks, ores, ice, glass, etc.)
                traits |= BlockTraits.TERRAIN_SOLID;
                if (!state.canOcclude()) {
                    traits |= BlockTraits.TRANSLUCENT;
                }
            } else if (state.is(net.minecraft.tags.BlockTags.LEAVES)) {
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
                    diskFallback = new McaVoxelGrid(BLOCK_REGISTRY, SHAPE_REGISTRY, regionDir, (short) level.getMinBuildHeight());
                    LOGGER.info("Raycast Bridge: Connected Anvil disk fallback for dimension {} at {}",
                            level.dimension().location(), regionDir);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to resolve Anvil region directory for level {}: {}",
                        level.dimension().location(), e.getMessage(), e);
            }
        } else if (level.isClientSide() && net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            diskFallback = resolveClientDiskFallback(level);
        }

        int minSectionY = level.getMinSection();
        int maxSectionY = level.getMaxSection();
        UnifiedVoxelCache cache = new UnifiedVoxelCache(BLOCK_REGISTRY, SHAPE_REGISTRY, TRAIT_REGISTRY, diskFallback, minSectionY, maxSectionY);
        return new MinecraftVoxelGrid(level, cache);
    }

    private static IVoxelGrid resolveClientDiskFallback(Level level) {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
                net.minecraft.server.MinecraftServer server = mc.getSingleplayerServer();
                Path rootPath = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
                Path dimFolder = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(), rootPath);
                Path regionDir = dimFolder.resolve("region");
                if (Files.isDirectory(regionDir)) {
                    LOGGER.info("Raycast Bridge: Connected client singleplayer Anvil disk fallback for dimension {} at {}",
                            level.dimension().location(), regionDir);
                    return new McaVoxelGrid(BLOCK_REGISTRY, SHAPE_REGISTRY, regionDir, (short) level.getMinBuildHeight());
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("Could not resolve client Anvil disk fallback for dimension {}: {}",
                    level.dimension().location(), t.getMessage());
        }
        return null;
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
            VoxelSection empty = VoxelSection.EMPTY;
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
        boolean allFullCubes = true;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = vanillaSection.getBlockState(x, y, z);
                    if (!state.isAir()) {
                        int id = getBlockId(state);
                        compiled.setVoxel(x, y, z, true, id);
                        if (allFullCubes && !SHAPE_REGISTRY.getShape(id).isFullCube()) {
                            allFullCubes = false;
                        }
                    }
                }
            }
        }
        compiled.setAllSolidAreFullCubes(allFullCubes);

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
                if (voxelSection == VoxelSection.EMPTY) {
                    if (newState.isAir()) {
                        return;
                    }
                    voxelSection = new VoxelSection();
                    section.raycast$setVoxelSection(voxelSection);
                    VoxelChunkColumn column = section.raycast$getVoxelColumn();
                    if (column != null) {
                        column.setSection(section.raycast$getSectionY(), voxelSection);
                    }
                }
                boolean solid = !newState.isAir();
                int blockId = solid ? getBlockId(newState) : BlockIdRegistry.AIR_ID;
                voxelSection.setVoxel(localX, localY, localZ, solid, blockId);
                if (solid && !SHAPE_REGISTRY.getShape(blockId).isFullCube()) {
                    voxelSection.setAllSolidAreFullCubes(false);
                }

                VoxelChunkColumn column = section.raycast$getVoxelColumn();
                if (column != null) {
                    int worldY = (section.raycast$getSectionY() << 4) | localY;
                    column.onVoxelChanged(localX, worldY, localZ, solid);
                }
            }
        }
    }

    /**
     * Clears and closes cached resources when a level unloads.
     *
     * @param level Unloaded Level instance
     */
    public static void onLevelUnloaded(Level level) {
        if (level != null) {
            MinecraftVoxelGrid grid = WORLD_GRIDS.remove(level.dimension());
            if (grid != null) {
                try {
                    grid.close();
                } catch (Exception e) {
                    LOGGER.warn("Failed to cleanly close MinecraftVoxelGrid for level {}", level.dimension().location(), e);
                }
            }
        }
    }

    /**
     * Resets all internal caches and cleanly closes all active level grids.
     */
    public static void reset() {
        for (var entry : WORLD_GRIDS.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                LOGGER.warn("Failed to cleanly close MinecraftVoxelGrid for level {}", entry.getKey().location(), e);
            }
        }
        WORLD_GRIDS.clear();
        STATE_TO_ID.clear();
    }
}
