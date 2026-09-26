package com.pixel.qve.neoforge.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.pixel.qve.api.raycast.RayHitResult;
import com.pixel.qve.neoforge.api.VoxelRaycastAPI;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelGrid;
import com.pixel.qve.state.VoxelShape;
import com.pixel.qve.world.cache.UnifiedVoxelCache;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Handles diagnostic inspection, on-demand NBT inspection, cache metrics, and pre-warmup commands under {@code /qve}.
 */
public final class QveInspectCommand {

    private QveInspectCommand() {}

    /**
     * Registers inspection, NBT, cache, stats, purge, and warmup commands onto the root QVE builder.
     *
     * @param root Root {@code /qve} literal argument builder
     */
    public static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("stats")
                .executes(QveInspectCommand::executeStats));

        root.then(Commands.literal("inspect")
                .executes(QveInspectCommand::executeInspectCrosshair)
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(QveInspectCommand::executeInspectPos)));

        root.then(Commands.literal("nbt")
                .executes(QveInspectCommand::executeNbtCrosshair)
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(QveInspectCommand::executeNbtPos)));

        root.then(Commands.literal("purge")
                .requires(source -> source.hasPermission(2))
                .executes(QveInspectCommand::executePurge));

        root.then(Commands.literal("warmup")
                .requires(source -> source.hasPermission(2))
                .executes(QveInspectCommand::executeWarmup));

        root.then(Commands.literal("cache")
                .then(Commands.literal("stats")
                        .executes(QveInspectCommand::executeStats))
                .then(Commands.literal("clear")
                        .requires(source -> source.hasPermission(2))
                        .executes(QveInspectCommand::executePurge)));
    }

    private static int executeStats(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
        UnifiedVoxelCache cache = grid.getCache();

        int columns = cache.getCachedColumnCount();
        int sections = cache.getCachedSectionCount();
        double memoryEstimateKb = (sections * 9.0) + (columns * 1.0);
        double memoryEstimateMb = memoryEstimateKb / 1024.0;

        int registeredBlocks = MinecraftVoxelBridge.getBlockRegistry().size();
        int registeredStates = MinecraftVoxelBridge.getBlockRegistry().getStateDictionary().size();
        int registeredShapes = MinecraftVoxelBridge.getShapeRegistry().getCustomShapeCount();
        int registeredKeys = MinecraftVoxelBridge.getBlockRegistry().getStateDictionary().getPropertyRegistry().getKeyCount();

        source.sendSuccess(() -> Component.literal(String.format(
                "§6=== [Quick Voxel Engine: Cache Telemetry] ===\n" +
                "§7Dimension: §f%s\n" +
                "§7Cached Columns: §e%,d §7| Cached Sections: §b%,d\n" +
                "§7Estimated RAM Footprint: §a%.2f KB §7(§a%.2f MB§7)\n" +
                "§7Block Types: §f%d §7| State Variants: §f%d §7| Property Keys: §e%d §7| Custom Shapes: §f%d",
                level.dimension().location(),
                columns, sections,
                memoryEstimateKb, memoryEstimateMb,
                registeredBlocks, registeredStates, registeredKeys, registeredShapes
        )), false);

        return 1;
    }

    private static int executeInspectCrosshair(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos targetPos;

        if (source.getEntity() instanceof ServerPlayer player) {
            Vec3 start = player.getEyePosition();
            Vec3 end = start.add(player.getLookAngle().normalize().scale(64.0));
            RayHitResult hit = new RayHitResult();
            VoxelRaycastAPI.raycast(level, start, end, hit);
            if (hit.isHit()) {
                targetPos = new BlockPos(hit.getBlockX(), hit.getBlockY(), hit.getBlockZ());
            } else {
                source.sendFailure(Component.literal("§c[QVE] No voxel in line of sight within 64m. Provide coordinates: /qve inspect <x y z>"));
                return 0;
            }
        } else {
            targetPos = BlockPos.containing(source.getPosition());
        }

        return inspectPosition(source, level, targetPos);
    }

    private static int executeInspectPos(CommandContext<CommandSourceStack> ctx) {
        try {
            BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
            return inspectPosition(ctx.getSource(), ctx.getSource().getLevel(), pos);
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§c[QVE] Position error: " + e.getMessage()));
            return 0;
        }
    }

    private static int inspectPosition(CommandSourceStack source, ServerLevel level, BlockPos pos) {
        MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
        int blockId = grid.getBlockId(pos.getX(), pos.getY(), pos.getZ());
        boolean solid = grid.isSolid(pos.getX(), pos.getY(), pos.getZ());

        String qveBlockName = MinecraftVoxelBridge.getBlockRegistry().getName(blockId);
        BlockState state = level.isLoaded(pos) ? level.getBlockState(pos) : null;
        String blockKey = (state != null) ? BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString() : null;
        String displayName = (qveBlockName != null && !qveBlockName.equals("minecraft:air"))
                ? qveBlockName
                : (blockKey != null ? blockKey : "minecraft:air");

        var shapeRegistry = MinecraftVoxelBridge.getShapeRegistry();
        var shape = shapeRegistry.getShape(blockId);
        String shapeType = (shape == null || shape.isFullCube()) ? "FULL_CUBE" : (shape == VoxelShape.EMPTY ? "EMPTY" : "SUB_VOXEL");
        int subBoxCount = (shape == null || shape.getBoxes() == null) ? 0 : shape.getBoxes().length;

        boolean isLoadedInRam = level.isLoaded(pos);
        boolean hasBlockEntity = (state != null) && (level.getBlockEntity(pos) != null);

        String formattedProps = MinecraftVoxelBridge.getBlockRegistry().getStateDictionary().formatProperties(blockId);
        String propsDisplay = formattedProps.isEmpty() ? "§7None" : "§e" + formattedProps;

        source.sendSuccess(() -> Component.literal(String.format(
                "§6=== [Quick Voxel Engine: Inspect @ %d, %d, %d] ===\n" +
                "§7Block: §b%s §7(ID: §e%d§7)\n" +
                "§7Properties: %s\n" +
                "§7Solid: %s §7| RAM Loaded: %s §7| BlockEntity: %s\n" +
                "§7Collision Model: §6%s §7(Sub-Boxes: §f%d§7)",
                pos.getX(), pos.getY(), pos.getZ(),
                displayName, blockId,
                propsDisplay,
                solid ? "§aYES" : "§cNO",
                isLoadedInRam ? "§aYES" : "§7NO (Offline/Disk)",
                hasBlockEntity ? "§aYES" : "§7NO",
                shapeType, subBoxCount
        )), false);

        return 1;
    }

    private static int executeNbtCrosshair(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos targetPos;

        if (source.getEntity() instanceof ServerPlayer player) {
            Vec3 start = player.getEyePosition();
            Vec3 end = start.add(player.getLookAngle().normalize().scale(64.0));
            RayHitResult hit = new RayHitResult();
            VoxelRaycastAPI.raycast(level, start, end, hit);
            if (hit.isHit()) {
                targetPos = new BlockPos(hit.getBlockX(), hit.getBlockY(), hit.getBlockZ());
            } else {
                source.sendFailure(Component.literal("§c[QVE] No voxel in line of sight within 64m. Provide coordinates: /qve nbt <x y z>"));
                return 0;
            }
        } else {
            targetPos = BlockPos.containing(source.getPosition());
        }

        return inspectNbtPosition(source, level, targetPos);
    }

    private static int executeNbtPos(CommandContext<CommandSourceStack> ctx) {
        try {
            BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
            return inspectNbtPosition(ctx.getSource(), ctx.getSource().getLevel(), pos);
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§c[QVE] Position error: " + e.getMessage()));
            return 0;
        }
    }

    private static int inspectNbtPosition(CommandSourceStack source, ServerLevel level, BlockPos pos) {
        MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
        long t0 = System.nanoTime();
        CompoundTag tag = grid.getBlockEntityCompoundTag(pos);
        long elapsedNs = System.nanoTime() - t0;

        if (tag == null) {
            source.sendFailure(Component.literal(String.format(
                    "§6[QVE NBT] §7No BlockEntity / NBT data found at §e[%d, %d, %d] §7(queried in §f%d ns§7).",
                    pos.getX(), pos.getY(), pos.getZ(), elapsedNs
            )));
            return 0;
        }

        String beId = tag.getString("id");
        int keyCount = tag.getAllKeys().size();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("§6=== [Quick Voxel Engine: NBT @ %d, %d, %d] ===§r\n", pos.getX(), pos.getY(), pos.getZ()));
        sb.append(String.format("§7ID: §b%s §7| Keys: §f%d §7| Query Time: §d%d ns§r\n", beId.isEmpty() ? "Unknown" : beId, keyCount, elapsedNs));

        int shown = 0;
        for (String key : tag.getAllKeys()) {
            if (shown >= 8) {
                sb.append(String.format("  §7... and %d more keys\n", keyCount - shown));
                break;
            }
            var tagValue = tag.get(key);
            String valStr = (tagValue != null) ? tagValue.getAsString() : "null";
            if (valStr.length() > 60) {
                valStr = valStr.substring(0, 57) + "...";
            }
            sb.append(String.format("  §e%s§7: §f%s\n", key, valStr));
            shown++;
        }

        source.sendSuccess(() -> Component.literal(sb.toString().trim()), false);
        return 1;
    }

    private static int executePurge(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        long t0 = System.nanoTime();
        MinecraftVoxelBridge.reset();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        source.sendSuccess(() -> Component.literal(String.format(
                "§6[QVE Purge] §aAll voxel grids and internal caches purged in %d ms.", elapsedMs
        )), true);
        return 1;
    }

    private static int executeWarmup(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("§6[QVE Warmup] §7Starting BlockState pre-indexing in background..."), false);
        com.pixel.qve.neoforge.warmup.VoxelWarmupEngine.reset();
        com.pixel.qve.neoforge.warmup.VoxelWarmupEngine.startAsyncWarmup().thenAccept(stats -> {
            if (stats != null) {
                source.sendSuccess(() -> Component.literal(String.format(
                        "§6[QVE Warmup] §aComplete in §f%.2f ms§a! Pre-indexed §f%,d§a states across §f%,d§a blocks (§e%d§a property keys).",
                        stats.elapsedMs(), stats.stateCount(), stats.blockCount(), stats.propertyKeyCount()
                )), true);
            }
        });
        return 1;
    }
}
