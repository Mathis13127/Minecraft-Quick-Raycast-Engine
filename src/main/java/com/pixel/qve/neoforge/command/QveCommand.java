package com.pixel.qve.neoforge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.pixel.qve.api.raycast.RayHitResult;
import com.pixel.qve.world.cache.UnifiedVoxelCache;
import com.pixel.qve.mca.McaRegionReader;
import com.pixel.qve.mca.McaVoxelGrid;
import com.pixel.qve.raycast.RaycastThreadPool;
import com.pixel.qve.raycast.VoxelDDA;
import com.pixel.qve.state.VoxelShape;
import com.pixel.qve.neoforge.api.VoxelRaycastAPI;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelGrid;
import com.pixel.qve.neoforge.hud.RaycastHudTracker;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import com.pixel.qve.mca.writer.WriteOptions;
import com.pixel.qve.neoforge.api.ChunkWriteBatch;
import com.pixel.qve.neoforge.api.VoxelWriteMode;
import com.pixel.qve.neoforge.api.WriteResult;

/**
 * Diagnostic, inspection, benchmarking, and telemetry commands for the Quick Voxel Engine (QVE).
 * Provides sub-millimeter block inspection, on-demand NBT queries, real-time line-of-sight testing,
 * multi-threaded stress tests, offline MCA region benchmarks, and UnifiedVoxelCache memory telemetry.
 */
public final class QveCommand {

    private QveCommand() {}

    /**
     * Registers the root {@code /qve} command and aliases {@code /raycast} and {@code /qre}.
     *
     * @param dispatcher Command dispatcher instance
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        register(dispatcher, null);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, net.minecraft.commands.CommandBuildContext buildContext) {
        var writeNode = Commands.literal("write")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("mode")
                        .executes(QveCommand::executeWriteModeStatus)
                        .then(Commands.literal("unified")
                                .executes(ctx -> executeSetWriteMode(ctx, VoxelWriteMode.UNIFIED)))
                        .then(Commands.literal("strict")
                                .executes(ctx -> executeSetWriteMode(ctx, VoxelWriteMode.STRICT_DIRECT)))
                        .then(Commands.literal("direct")
                                .executes(ctx -> executeSetWriteMode(ctx, VoxelWriteMode.STRICT_DIRECT))))
                .then(Commands.literal("dump")
                        .then(Commands.argument("chunkX", IntegerArgumentType.integer())
                                .then(Commands.argument("chunkZ", IntegerArgumentType.integer())
                                        .executes(QveCommand::executeWriteDump))))
                .then(Commands.literal("inspect")
                        .then(Commands.argument("chunkX", IntegerArgumentType.integer())
                                .then(Commands.argument("chunkZ", IntegerArgumentType.integer())
                                        .executes(QveCommand::executeWriteInspect))));

        if (buildContext != null) {
            var setblockArg = Commands.argument("block", net.minecraft.commands.arguments.blocks.BlockStateArgument.block(buildContext))
                    .executes(ctx -> executeWriteBlockWithMode(ctx, currentMode))
                    .then(Commands.literal("unified").executes(ctx -> executeWriteBlockWithMode(ctx, VoxelWriteMode.UNIFIED)))
                    .then(Commands.literal("strict").executes(ctx -> executeWriteBlockWithMode(ctx, VoxelWriteMode.STRICT_DIRECT)))
                    .then(Commands.literal("direct").executes(ctx -> executeWriteBlockWithMode(ctx, VoxelWriteMode.STRICT_DIRECT)));

            var fillBlockArg = Commands.argument("block", net.minecraft.commands.arguments.blocks.BlockStateArgument.block(buildContext))
                    .executes(ctx -> executeWriteFill(ctx, currentMode, null))
                    .then(Commands.literal("unified").executes(ctx -> executeWriteFill(ctx, VoxelWriteMode.UNIFIED, null)))
                    .then(Commands.literal("strict").executes(ctx -> executeWriteFill(ctx, VoxelWriteMode.STRICT_DIRECT, null)))
                    .then(Commands.literal("direct").executes(ctx -> executeWriteFill(ctx, VoxelWriteMode.STRICT_DIRECT, null)))
                    .then(Commands.literal("replace")
                            .then(Commands.argument("filter", net.minecraft.commands.arguments.blocks.BlockStateArgument.block(buildContext))
                                    .executes(ctx -> executeWriteFill(ctx, currentMode, net.minecraft.commands.arguments.blocks.BlockStateArgument.getBlock(ctx, "filter").getState()))
                                    .then(Commands.literal("unified").executes(ctx -> executeWriteFill(ctx, VoxelWriteMode.UNIFIED, net.minecraft.commands.arguments.blocks.BlockStateArgument.getBlock(ctx, "filter").getState())))
                                    .then(Commands.literal("strict").executes(ctx -> executeWriteFill(ctx, VoxelWriteMode.STRICT_DIRECT, net.minecraft.commands.arguments.blocks.BlockStateArgument.getBlock(ctx, "filter").getState())))
                                    .then(Commands.literal("direct").executes(ctx -> executeWriteFill(ctx, VoxelWriteMode.STRICT_DIRECT, net.minecraft.commands.arguments.blocks.BlockStateArgument.getBlock(ctx, "filter").getState())))));

            writeNode.then(Commands.literal("setblock")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                            .then(setblockArg)))
            .then(Commands.literal("block")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                            .then(setblockArg)))
            .then(Commands.literal("fill")
                    .then(Commands.argument("from", BlockPosArgument.blockPos())
                            .then(Commands.argument("to", BlockPosArgument.blockPos())
                                    .then(fillBlockArg))))
            .then(Commands.literal("unified")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                            .then(Commands.argument("block", net.minecraft.commands.arguments.blocks.BlockStateArgument.block(buildContext))
                                    .executes(ctx -> executeWriteBlockWithMode(ctx, VoxelWriteMode.UNIFIED)))))
            .then(Commands.literal("direct")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                            .then(Commands.argument("block", net.minecraft.commands.arguments.blocks.BlockStateArgument.block(buildContext))
                                    .executes(ctx -> executeWriteBlockWithMode(ctx, VoxelWriteMode.STRICT_DIRECT)))));
        }

        var qveRoot = Commands.literal("qve")
                .requires(source -> source.hasPermission(0))
                .then(writeNode)
                .then(Commands.literal("stats")
                        .executes(QveCommand::executeStats))
                .then(Commands.literal("inspect")
                        .executes(QveCommand::executeInspectCrosshair)
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(QveCommand::executeInspectPos)))
                .then(Commands.literal("nbt")
                        .executes(QveCommand::executeNbtCrosshair)
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(QveCommand::executeNbtPos)))
                .then(Commands.literal("purge")
                        .requires(source -> source.hasPermission(2))
                        .executes(QveCommand::executePurge))
                .then(Commands.literal("warmup")
                        .requires(source -> source.hasPermission(2))
                        .executes(QveCommand::executeWarmup))
                .then(Commands.literal("cache")
                        .then(Commands.literal("stats")
                                .executes(QveCommand::executeStats))
                        .then(Commands.literal("clear")
                                .requires(source -> source.hasPermission(2))
                                .executes(QveCommand::executePurge)))
                .then(Commands.literal("test")
                        .executes(ctx -> executeTest(ctx, 256.0))
                        .then(Commands.argument("distance", DoubleArgumentType.doubleArg(0.1))
                                .executes(ctx -> executeTest(ctx, DoubleArgumentType.getDouble(ctx, "distance")))))
                .then(Commands.literal("benchmark")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("rays", IntegerArgumentType.integer(100, 10_000_000))
                                .executes(ctx -> executeBenchmark(ctx, IntegerArgumentType.getInteger(ctx, "rays"), 256.0))
                                .then(Commands.argument("distance", DoubleArgumentType.doubleArg(0.1))
                                        .executes(ctx -> executeBenchmark(ctx,
                                                IntegerArgumentType.getInteger(ctx, "rays"),
                                                DoubleArgumentType.getDouble(ctx, "distance"))))))
                .then(Commands.literal("benchmark_mca")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("regionX", IntegerArgumentType.integer())
                                .then(Commands.argument("regionZ", IntegerArgumentType.integer())
                                        .then(Commands.argument("rays", IntegerArgumentType.integer(100, 10_000_000))
                                                .executes(ctx -> executeBenchmarkMca(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "regionX"),
                                                        IntegerArgumentType.getInteger(ctx, "regionZ"),
                                                        IntegerArgumentType.getInteger(ctx, "rays")))))))
                .then(Commands.literal("hud")
                        .executes(RaycastHudTracker::executeToggleDefault)
                        .then(Commands.literal("off")
                                .executes(RaycastHudTracker::executeOff))
                        .then(Commands.literal("on")
                                .executes(ctx -> RaycastHudTracker.executeOn(ctx, RaycastHudTracker.DEFAULT_MAX_DISTANCE))
                                .then(Commands.argument("distance", DoubleArgumentType.doubleArg(0.1, 1_000_000.0))
                                        .executes(ctx -> RaycastHudTracker.executeOn(ctx, DoubleArgumentType.getDouble(ctx, "distance")))))
                        .then(Commands.argument("distance", DoubleArgumentType.doubleArg(0.1, 1_000_000.0))
                                .executes(ctx -> RaycastHudTracker.executeToggleDistance(ctx, DoubleArgumentType.getDouble(ctx, "distance")))));

        var qveNode = dispatcher.register(qveRoot);

        // Register backward-compatible aliases redirecting to /qve
        dispatcher.register(Commands.literal("raycast").redirect(qveNode));
        dispatcher.register(Commands.literal("qre").redirect(qveNode));
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

    private static int executeTest(CommandContext<CommandSourceStack> ctx, double distance) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        net.minecraft.server.MinecraftServer server = level.getServer();
        Vec3 start;
        Vec3 look;

        if (source.getEntity() instanceof ServerPlayer player) {
            start = player.getEyePosition();
            look = player.getLookAngle().normalize();
        } else {
            start = source.getPosition();
            look = new Vec3(0, -1, 0); // default straight down for console/blocks
        }

        Vec3 end = start.add(look.scale(distance));
        ServerPlayer player = (source.getEntity() instanceof ServerPlayer sp) ? sp : null;

        RaycastThreadPool.submit(() -> {
            try {
                RayHitResult hit = new RayHitResult();
                long t0 = System.nanoTime();
                VoxelRaycastAPI.raycast(level, start, end, hit);
                long elapsedNs = System.nanoTime() - t0;

                server.execute(() -> {
                    if (player != null && !player.hasDisconnected()) {
                        double rayLength = hit.isHit() ? hit.getDistance() : distance;
                        int particleCount = (int) Math.min(rayLength, 96.0);
                        for (int i = 1; i <= particleCount; i++) {
                            Vec3 p = start.add(look.scale(i));
                            level.sendParticles(player, ParticleTypes.CRIT, false, p.x, p.y, p.z, 1, 0, 0, 0, 0);
                        }
                    }

                    if (hit.isHit()) {
                        BlockPos pos = new BlockPos(hit.getBlockX(), hit.getBlockY(), hit.getBlockZ());
                        BlockState state = level.getBlockState(pos);
                        String blockName = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

                        source.sendSuccess(() -> Component.literal(String.format(
                                "§6[QVE] §aHIT §7at §e[%d, %d, %d] §7(§b%s§7, face: §f%s§7, dist: §f%.2fm§7, time: §d%,d ns§7)",
                                hit.getBlockX(), hit.getBlockY(), hit.getBlockZ(),
                                blockName,
                                hit.getFace(),
                                hit.getDistance(),
                                elapsedNs
                        )), false);
                    } else {
                        source.sendSuccess(() -> Component.literal(String.format(
                                "§6[QVE] §eMISS §7(Clear line of sight for §f%.1fm§7, time: §d%,d ns§7)",
                                distance,
                                elapsedNs
                        )), false);
                    }
                });
            } catch (Exception e) {
                server.execute(() -> source.sendFailure(Component.literal("§c[QVE] Raycast error: " + e.getMessage())));
            }
        });

        return 1;
    }

    private static int executeBenchmark(CommandContext<CommandSourceStack> ctx, int rays, double distance) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        net.minecraft.server.MinecraftServer server = level.getServer();
        Vec3 origin = source.getPosition();

        float ox = (float) origin.x;
        float oy = (float) origin.y;
        float oz = (float) origin.z;
        float dist = (float) distance;

        source.sendSuccess(() -> Component.literal(String.format(
                "§6[QVE] §7Starting background benchmark: §f%,d rays §7at §f%.1fm§7...",
                rays, distance
        )), false);

        MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
        RaycastThreadPool.submit(() -> {
            try {
                LongAdder hitCounter = new LongAdder();
                LongAdder missCounter = new LongAdder();
                long t0 = System.nanoTime();
                RaycastThreadPool.parallelFor(0, rays, i -> {
                    RaycastThreadPool.RaycastContext context = RaycastThreadPool.getThreadLocalContext();
                    // Fibonacci sphere distribution around origin
                    double phi = Math.acos(1.0 - 2.0 * (i + 0.5) / rays);
                    double theta = Math.PI * (1.0 + Math.sqrt(5.0)) * i;
                    float dx = (float) (Math.sin(phi) * Math.cos(theta));
                    float dy = (float) (Math.sin(phi) * Math.sin(theta));
                    float dz = (float) Math.cos(phi);

                    float tx = ox + dx * dist;
                    float ty = oy + dy * dist;
                    float tz = oz + dz * dist;

                    var ray = context.ray.setFromPoints(ox, oy, oz, tx, ty, tz);
                    RayHitResult result = context.hitResult;
                    VoxelDDA.trace(ray, grid, result);
                    if (result.isHit()) {
                        hitCounter.increment();
                    } else {
                        missCounter.increment();
                    }
                });
                long elapsedNs = System.nanoTime() - t0;

                double elapsedMs = elapsedNs / 1_000_000.0;
                double elapsedSec = elapsedNs / 1_000_000_000.0;
                double throughput = (double) rays / Math.max(elapsedSec, 1e-9);
                double latencyNs = (double) elapsedNs / rays;

                server.execute(() -> source.sendSuccess(() -> Component.literal(String.format(
                        "§6=== [Quick Voxel Engine: Live Benchmark] ===\n" +
                        "§7Total Rays: §f%,d §7| Distance: §f%.1fm\n" +
                        "§7Duration: §f%.2f ms §7| Latency: §d%.1f ns/ray\n" +
                        "§7Throughput: §a§l%,.0f rays/second\n" +
                        "§7Hits: §e%,d §7| Misses: §b%,d",
                        rays, distance,
                        elapsedMs, latencyNs,
                        throughput,
                        hitCounter.sum(), missCounter.sum()
                )), true));
            } catch (Exception e) {
                server.execute(() -> source.sendFailure(Component.literal("§c[QVE] Benchmark error: " + e.getMessage())));
            }
        });

        return 1;
    }

    private static int executeBenchmarkMca(CommandContext<CommandSourceStack> ctx, int rx, int rz, int rays) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        net.minecraft.server.MinecraftServer server = level.getServer();

        Path worldDir = level.getServer().getWorldPath(LevelResource.ROOT);
        Path dimDir = DimensionType.getStorageFolder(level.dimension(), worldDir);
        Path regionPath = dimDir.resolve("region").resolve("r." + rx + "." + rz + ".mca");

        if (!Files.exists(regionPath)) {
            // Also fallback to root region folder
            regionPath = worldDir.resolve("region").resolve("r." + rx + "." + rz + ".mca");
        }

        if (!Files.exists(regionPath)) {
            final Path searched = regionPath;
            source.sendFailure(Component.literal("§c[QVE] Region file r." + rx + "." + rz + ".mca not found on disk at: " + searched));
            return 0;
        }

        final Path targetRegion = regionPath;
        source.sendSuccess(() -> Component.literal(String.format(
                "§6[QVE MCA Benchmark] §7Streaming unloaded region r.%d.%d.mca from disk...",
                rx, rz
        )), false);

        RaycastThreadPool.submit(() -> {
            var registry = MinecraftVoxelBridge.getBlockRegistry();
            try (McaVoxelGrid mcaGrid = new McaVoxelGrid(registry);
                 McaRegionReader reader = new McaRegionReader(targetRegion, registry)) {

                long tLoad0 = System.nanoTime();
                int sectionsLoaded = mcaGrid.preloadRegion(reader);
                long loadMs = (System.nanoTime() - tLoad0) / 1_000_000;

                int minX = rx << 9;
                int minZ = rz << 9;
                LongAdder hits = new LongAdder();
                LongAdder misses = new LongAdder();

                long t0 = System.nanoTime();
                RaycastThreadPool.parallelFor(0, rays, i -> {
                    RaycastThreadPool.RaycastContext context = RaycastThreadPool.getThreadLocalContext();
                    float sx = minX + (float) ((i * 17) % 512);
                    float sz = minZ + (float) ((i * 31) % 512);
                    float sy = 320.0f;
                    float ex = sx + 20.0f;
                    float ez = sz + 20.0f;
                    float ey = -64.0f;

                    var ray = context.ray.setFromPoints(sx, sy, sz, ex, ey, ez);
                    RayHitResult result = context.hitResult;
                    VoxelDDA.trace(ray, mcaGrid, result);
                    if (result.isHit()) {
                        hits.increment();
                    } else {
                        misses.increment();
                    }
                });
                long elapsedNs = System.nanoTime() - t0;

                double elapsedMs = elapsedNs / 1_000_000.0;
                double elapsedSec = elapsedNs / 1_000_000_000.0;
                double throughput = (double) rays / Math.max(elapsedSec, 1e-9);
                double latencyNs = (double) elapsedNs / rays;

                server.execute(() -> source.sendSuccess(() -> Component.literal(String.format(
                        "§6=== [Quick Voxel Engine: Unloaded MCA Benchmark] ===\n" +
                        "§7Region File: §fr.%d.%d.mca §7(Sections: §f%d §7loaded in §f%d ms§7)\n" +
                        "§7Rays: §f%,d §7| Duration: §f%.2f ms §7| Latency: §d%.1f ns/ray\n" +
                        "§7Throughput: §a§l%,.0f rays/second\n" +
                        "§7Hits: §e%,d §7| Misses: §b%,d",
                        rx, rz, sectionsLoaded, loadMs,
                        rays, elapsedMs, latencyNs,
                        throughput,
                        hits.sum(), misses.sum()
                )), true));
            } catch (Exception e) {
                server.execute(() -> source.sendFailure(Component.literal("§c[QVE] Error running MCA benchmark: " + e.getMessage())));
            }
        });

        return 1;
    }

    private static int executeWriteInspect(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        int cx = IntegerArgumentType.getInteger(ctx, "chunkX");
        int cz = IntegerArgumentType.getInteger(ctx, "chunkZ");

        boolean loaded = com.pixel.qve.neoforge.api.VoxelWriteAPI.isLoadedInRam(level, cx, cz);
        boolean safe = com.pixel.qve.neoforge.api.VoxelWriteAPI.isSafeForDirectDiskWrite(level, cx, cz);

        int rx = cx >> 5;
        int rz = cz >> 5;
        Path regionDir = com.pixel.qve.neoforge.bridge.writer.MinecraftVoxelWriter.resolveRegionDirectory(level);
        Path mcaFile = (regionDir != null) ? regionDir.resolve("r." + rx + "." + rz + ".mca") : null;
        boolean fileExists = (mcaFile != null && Files.isRegularFile(mcaFile));

        long fileSize = fileExists ? mcaFile.toFile().length() : 0L;

        source.sendSuccess(() -> Component.literal(String.format(
                "§6=== [Quick Voxel Engine: Chunk Write Inspection] ===\n" +
                "§7Chunk: §f(%d, %d) §7in §e%s\n" +
                "§7Loaded in RAM: %s §7| Safe for Direct Disk Write: %s\n" +
                "§7Region File: §f%s §7(Exists: %s, Size: §e%,d bytes§7)",
                cx, cz, level.dimension().location(),
                loaded ? "§cYES (Active in RAM)" : "§aNO (Unloaded)",
                safe ? "§aYES" : "§cNO",
                (mcaFile != null ? mcaFile.getFileName().toString() : "N/A"),
                fileExists ? "§aYES" : "§7NO",
                fileSize
        )), false);

        return 1;
    }

    private static volatile VoxelWriteMode currentMode = VoxelWriteMode.UNIFIED;
    private static volatile WriteOptions currentWriteOptions = WriteOptions.DEFAULT;

    public static VoxelWriteMode getWriteMode() {
        return currentMode;
    }

    public static void setWriteMode(VoxelWriteMode mode) {
        currentMode = Objects.requireNonNull(mode, "mode cannot be null");
        currentWriteOptions = (mode == VoxelWriteMode.STRICT_DIRECT) ? WriteOptions.STRICT : WriteOptions.DEFAULT;
    }

    public static WriteOptions getWriteOptions() {
        return currentWriteOptions;
    }

    public static void setWriteOptions(WriteOptions options) {
        currentWriteOptions = Objects.requireNonNull(options, "options cannot be null");
        currentMode = options.isStrict() ? VoxelWriteMode.STRICT_DIRECT : VoxelWriteMode.UNIFIED;
    }

    private static <T extends com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, T>> T attachFillOptionFlags(
            T builder,
            java.util.function.Function<WriteOptions, com.mojang.brigadier.Command<CommandSourceStack>> commandSupplier) {

        builder.then(Commands.literal("unified").executes(commandSupplier.apply(WriteOptions.DEFAULT))
                .then(Commands.literal("existing_only").executes(commandSupplier.apply(WriteOptions.EXISTING_ONLY)))
                .then(Commands.literal("--existing-only").executes(commandSupplier.apply(WriteOptions.EXISTING_ONLY))));

        builder.then(Commands.literal("--unified").executes(commandSupplier.apply(WriteOptions.DEFAULT))
                .then(Commands.literal("existing_only").executes(commandSupplier.apply(WriteOptions.EXISTING_ONLY)))
                .then(Commands.literal("--existing-only").executes(commandSupplier.apply(WriteOptions.EXISTING_ONLY))));

        builder.then(Commands.literal("strict").executes(commandSupplier.apply(WriteOptions.STRICT))
                .then(Commands.literal("existing_only").executes(commandSupplier.apply(WriteOptions.STRICT_EXISTING_ONLY)))
                .then(Commands.literal("--existing-only").executes(commandSupplier.apply(WriteOptions.STRICT_EXISTING_ONLY))));

        builder.then(Commands.literal("--strict").executes(commandSupplier.apply(WriteOptions.STRICT))
                .then(Commands.literal("existing_only").executes(commandSupplier.apply(WriteOptions.STRICT_EXISTING_ONLY)))
                .then(Commands.literal("--existing-only").executes(commandSupplier.apply(WriteOptions.STRICT_EXISTING_ONLY))));

        builder.then(Commands.literal("direct").executes(commandSupplier.apply(WriteOptions.STRICT))
                .then(Commands.literal("existing_only").executes(commandSupplier.apply(WriteOptions.STRICT_EXISTING_ONLY)))
                .then(Commands.literal("--existing-only").executes(commandSupplier.apply(WriteOptions.STRICT_EXISTING_ONLY))));

        builder.then(Commands.literal("existing_only").executes(commandSupplier.apply(WriteOptions.EXISTING_ONLY))
                .then(Commands.literal("strict").executes(commandSupplier.apply(WriteOptions.STRICT_EXISTING_ONLY)))
                .then(Commands.literal("--strict").executes(commandSupplier.apply(WriteOptions.STRICT_EXISTING_ONLY))));

        builder.then(Commands.literal("--existing-only").executes(commandSupplier.apply(WriteOptions.EXISTING_ONLY))
                .then(Commands.literal("strict").executes(commandSupplier.apply(WriteOptions.STRICT_EXISTING_ONLY)))
                .then(Commands.literal("--strict").executes(commandSupplier.apply(WriteOptions.STRICT_EXISTING_ONLY))));

        return builder;
    }

    private static int executeWriteModeStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        VoxelWriteMode mode = currentMode;
        source.sendSuccess(() -> Component.literal(String.format(
                "§6=== [Quick Voxel Engine: Write Mode] ===\n" +
                "§7Active Mode: %s§l%s §7(%s)\n" +
                "§7Active Options: §fExecution=%s§7, Creation=%s\n" +
                "§7Use §f/qve write mode <unified|strict>§7 to switch modes.",
                mode == VoxelWriteMode.UNIFIED ? "§a" : "§e",
                mode.getDisplayName(),
                mode.getDescription(),
                currentWriteOptions.executionPolicy(),
                currentWriteOptions.creationPolicy()
        )), false);
        return 1;
    }

    private static int executeSetWriteMode(CommandContext<CommandSourceStack> ctx, VoxelWriteMode newMode) {
        CommandSourceStack source = ctx.getSource();
        setWriteMode(newMode);
        source.sendSuccess(() -> Component.literal(String.format(
                "§a[QVE] Write mode set to: %s§l%s §7(%s)",
                newMode == VoxelWriteMode.UNIFIED ? "§a" : "§e",
                newMode.getDisplayName(),
                newMode.getDescription()
        )), true);
        return 1;
    }

    private static int executeWriteBlock(CommandContext<CommandSourceStack> ctx, WriteOptions options) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
        BlockState state = net.minecraft.commands.arguments.blocks.BlockStateArgument.getBlock(ctx, "block").getState();

        boolean loadedInRam = level.isLoaded(pos);
        String targetBackend = !options.isStrict()
                ? (loadedInRam ? "§aRAM Live (LevelChunk)" : "§bMCA Disk (Offline)")
                : "§eMCA Disk (Strict Direct)";

        source.sendSuccess(() -> Component.literal(String.format(
                "§7[QVE] Dispatching write in §f[%s] §7mode (Target: %s§7) for §f%s §7at §f%s...",
                options.isStrict() ? "Strict" : "Unified",
                targetBackend,
                state.getBlock().getName().getString(),
                pos.toShortString()
        )), false);

        var future = options.isStrict()
                ? com.pixel.qve.neoforge.api.VoxelWriteAPI.setBlockDirectAsync(level, pos, state, null)
                : com.pixel.qve.neoforge.api.VoxelWriteAPI.setBlockUnifiedAsync(level, pos, state);

        future.thenAccept(res -> {
            level.getServer().execute(() -> {
                if (res.isSuccess()) {
                    double ms = res.durationNanos() / 1_000_000.0;
                    String backendUsed = (res.status() == com.pixel.qve.neoforge.api.WriteStatus.SUCCESS_RAM)
                            ? "§aRAM Live (LevelChunk)"
                            : "§bAnvil MCA Disk (Offline)";

                    String verifyStr;
                    if (res.isVerified()) {
                        verifyStr = String.format("§aMATCH [§f%s§a]", res.verifiedBlock() != null ? res.verifiedBlock() : "OK");
                    } else if (res.verifiedBlock() != null) {
                        verifyStr = String.format("§cMISMATCH (found: §f%s§c)", res.verifiedBlock());
                    } else {
                        verifyStr = "§7Unverified";
                    }

                    source.sendSuccess(() -> Component.literal(String.format(
                            "§a=== [Quick Voxel Engine: Write SUCCESS] ===\n" +
                            "§7Position: §f%s §7(Chunk: §e%d, %d§7)\n" +
                            "§7Policy: §f%s §7| Routed Backend: %s\n" +
                            "§7Auto-Verification: %s\n" +
                            "§7Duration: §f%.2f ms §7| Status: §e%s §7| Sector: §b%d §7| Bytes: §b%,d",
                            pos.toShortString(), res.chunkX(), res.chunkZ(),
                            options.isStrict() ? "Strict" : "Unified", backendUsed,
                            verifyStr,
                            ms, res.status(), res.sectorOffset(), res.compressedBytes()
                    )), true);
                } else {
                    source.sendFailure(Component.literal(String.format(
                            "§c[QVE] Write FAILED in mode [%s]: %s (Status: %s)",
                            options.isStrict() ? "Strict" : "Unified", res.errorMessage(), res.status()
                    )));
                }
            });
        });

        return 1;
    }

    private static int executeWriteBlockWithMode(CommandContext<CommandSourceStack> ctx, VoxelWriteMode mode) {
        WriteOptions opts = (mode == VoxelWriteMode.STRICT_DIRECT) ? WriteOptions.STRICT : WriteOptions.DEFAULT;
        return executeWriteBlock(ctx, opts);
    }

    private static int executeWriteFill(CommandContext<CommandSourceStack> ctx, WriteOptions options, BlockState replaceFilter) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos from = BlockPosArgument.getBlockPos(ctx, "from");
        BlockPos to = BlockPosArgument.getBlockPos(ctx, "to");
        BlockState state = net.minecraft.commands.arguments.blocks.BlockStateArgument.getBlock(ctx, "block").getState();

        int minX = Math.min(from.getX(), to.getX());
        int maxX = Math.max(from.getX(), to.getX());
        int minY = Math.min(from.getY(), to.getY());
        int maxY = Math.max(from.getY(), to.getY());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxZ = Math.max(from.getZ(), to.getZ());

        long countX = (maxX - minX + 1);
        long countY = (maxY - minY + 1);
        long countZ = (maxZ - minZ + 1);
        long volume = countX * countY * countZ;

        String policyDesc = options.isStrict() ? "Strict Direct" : "Unified Transparent";
        String creationDesc = options.canCreateIfMissing() ? "Auto-Create" : "Existing-Only";

        source.sendSuccess(() -> Component.literal(String.format(
                "§7[QVE] Dispatching fill in §f[%s] §7mode (§b%s§7) for §e%,d voxels §7(§b%s§7) from §f%s §7to §f%s%s...",
                policyDesc,
                creationDesc,
                volume,
                state.getBlock().getName().getString(),
                from.toShortString(),
                to.toShortString(),
                replaceFilter != null ? " replacing §e" + replaceFilter.getBlock().getName().getString() : ""
        )), false);

        ChunkWriteBatch batch = new ChunkWriteBatch(level);
        batch.fill(from, to, state, replaceFilter);

        batch.executeAsync(options).thenAccept(res -> {
            level.getServer().execute(() -> {
                if (res.isAllSuccessful()) {
                    double ms = res.durationMs();
                    double throughput = res.throughputBlocksPerSecond();
                    source.sendSuccess(() -> Component.literal(String.format(
                            "§a=== [Quick Voxel Engine: Batch Fill SUCCESS] ===\n" +
                            "§7Total Voxels: §f%,d §7| Chunks: §e%,d §7(RAM: §a%d§7, Disk: §b%d§7)\n" +
                            "§7Policy: §f%s §7(§b%s§7) | Duration: §f%.2f ms §7(§a§l%,.0f voxels/s§7)",
                            res.totalBlocks(),
                            res.totalSubmitted(),
                            res.ramChunkCount(),
                            res.diskChunkCount(),
                            policyDesc,
                            creationDesc,
                            ms,
                            throughput
                    )), true);
                } else {
                    boolean isPreFlight = res.ramChunkCount() == 0 && res.diskChunkCount() == 0;
                    String prefix = isPreFlight
                            ? "§c[QVE] Batch Fill PRE-FLIGHT REJECTED (Fail-Fast, 0 blocks written)"
                            : "§c[QVE] Batch Fill FAILED";
                    source.sendFailure(Component.literal(String.format(
                            "%s: %d/%d chunks failed.\n§cFirst error: %s",
                            prefix,
                            res.totalFailed(),
                            res.totalSubmitted(),
                            res.results().stream().filter(r -> !r.isSuccess()).map(WriteResult::errorMessage).findFirst().orElse("Unknown error")
                    )));
                }
            });
        });

        return 1;
    }

    private static int executeWriteFill(CommandContext<CommandSourceStack> ctx, VoxelWriteMode mode, BlockState replaceFilter) {
        WriteOptions opts = (mode == VoxelWriteMode.STRICT_DIRECT) ? WriteOptions.STRICT : WriteOptions.DEFAULT;
        return executeWriteFill(ctx, opts, replaceFilter);
    }

    private static int executeWriteDump(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        int chunkX = IntegerArgumentType.getInteger(ctx, "chunkX");
        int chunkZ = IntegerArgumentType.getInteger(ctx, "chunkZ");

        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        int localX = chunkX & 31;
        int localZ = chunkZ & 31;

        Path regionDir = com.pixel.qve.neoforge.bridge.writer.MinecraftVoxelWriter.resolveRegionDirectory(level);
        if (regionDir == null) {
            source.sendFailure(Component.literal("§c[QVE Dump] Failed to resolve region directory for dimension."));
            return 0;
        }

        Path mcaFile = regionDir.resolve("r." + rx + "." + rz + ".mca");
        if (!Files.isRegularFile(mcaFile)) {
            source.sendFailure(Component.literal(String.format(
                    "§c[QVE Dump] Region file not found on disk: %s", mcaFile.getFileName()
            )));
            return 0;
        }

        RaycastThreadPool.submit(() -> {
            try (McaRegionReader reader = new McaRegionReader(mcaFile, MinecraftVoxelBridge.getBlockRegistry())) {
                if (!reader.hasChunk(localX, localZ)) {
                    level.getServer().execute(() -> source.sendFailure(Component.literal(String.format(
                            "§c[QVE Dump] Chunk (%d, %d) is not allocated or generated in %s.",
                            chunkX, chunkZ, mcaFile.getFileName()
                    ))));
                    return;
                }

                ByteBuffer decompressed = reader.decompressChunk(localX, localZ);
                if (decompressed == null) {
                    level.getServer().execute(() -> source.sendFailure(Component.literal(
                            "§c[QVE Dump] Failed to decompress chunk NBT payload."
                    )));
                    return;
                }

                byte[] nbtBytes = new byte[decompressed.remaining()];
                decompressed.get(nbtBytes);
                CompoundTag tag = net.minecraft.nbt.NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(nbtBytes)));

                if (tag == null) {
                    level.getServer().execute(() -> source.sendFailure(Component.literal(
                            "§c[QVE Dump] Failed to deserialize root CompoundTag."
                    )));
                    return;
                }

                String status = tag.getString("Status");
                int dataVersion = tag.getInt("DataVersion");
                long inhabitedTime = tag.getLong("InhabitedTime");
                net.minecraft.nbt.ListTag sectionsTag = tag.getList("sections", 10);
                net.minecraft.nbt.ListTag blockEntitiesTag = tag.getList("block_entities", 10);

                int minBlockX = chunkX << 4;
                int maxBlockX = minBlockX + 15;
                int minBlockZ = chunkZ << 4;
                int maxBlockZ = minBlockZ + 15;

                StringBuilder dumpText = new StringBuilder();
                dumpText.append("================================================================================\n");
                dumpText.append(String.format("QUICK VOXEL ENGINE - CHUNK NBT DUMP: (%d, %d)\n", chunkX, chunkZ));
                dumpText.append(String.format("Dimension: %s | Region File: %s (local: %d, %d)\n",
                        level.dimension().location(), mcaFile.getFileName(), localX, localZ));
                dumpText.append(String.format("World Bounds: X: [%d .. %d], Z: [%d .. %d]\n",
                        minBlockX, maxBlockX, minBlockZ, maxBlockZ));
                dumpText.append(String.format("Vanilla Status: %s | DataVersion: %d | InhabitedTime: %d\n",
                        status.isEmpty() ? "N/A" : status, dataVersion, inhabitedTime));
                dumpText.append(String.format("Sections Count: %d | BlockEntities: %d\n",
                        sectionsTag.size(), blockEntitiesTag.size()));
                dumpText.append("================================================================================\n\n");

                int nonEmptySections = 0;
                for (int i = 0; i < sectionsTag.size(); i++) {
                    CompoundTag secTag = sectionsTag.getCompound(i);
                    byte secY = secTag.getByte("Y");
                    int worldYMin = secY << 4;
                    int worldYMax = worldYMin + 15;

                    CompoundTag blockStates = secTag.getCompound("block_states");
                    net.minecraft.nbt.ListTag palette = blockStates.getList("palette", 10);
                    long[] dataArray = blockStates.getLongArray("data");

                    boolean isAllAir = (palette.size() <= 1 && palette.size() > 0 &&
                            "minecraft:air".equals(palette.getCompound(0).getString("Name")));

                    if (!isAllAir && palette.size() > 0) {
                        nonEmptySections++;
                        dumpText.append(String.format("--- Section Y = %d (World Height Y: [%d .. %d]) ---\n",
                                secY, worldYMin, worldYMax));
                        dumpText.append(String.format("  Palette Size: %d entries\n", palette.size()));
                        for (int p = 0; p < palette.size(); p++) {
                            CompoundTag entry = palette.getCompound(p);
                            String blockName = entry.getString("Name");
                            CompoundTag props = entry.getCompound("Properties");
                            if (props.isEmpty()) {
                                dumpText.append(String.format("    [%d] %s\n", p, blockName));
                            } else {
                                dumpText.append(String.format("    [%d] %s %s\n", p, blockName, props));
                            }
                        }
                        dumpText.append(String.format("  Packed Data Array Length: %d longs\n\n", dataArray.length));
                    }
                }

                if (blockEntitiesTag.size() > 0) {
                    dumpText.append("--- Block Entities ---\n");
                    for (int i = 0; i < blockEntitiesTag.size(); i++) {
                        CompoundTag beTag = blockEntitiesTag.getCompound(i);
                        dumpText.append(String.format("  [%d] id: %s at (%d, %d, %d)\n",
                                i, beTag.getString("id"), beTag.getInt("x"), beTag.getInt("y"), beTag.getInt("z")));
                    }
                    dumpText.append("\n");
                }

                Path rootPath = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
                Path dumpDir = rootPath.resolve("qve_dumps");
                if (!Files.exists(dumpDir)) {
                    Files.createDirectories(dumpDir);
                }
                Path dumpFile = dumpDir.resolve(String.format("chunk_%d_%d.txt", chunkX, chunkZ));
                Files.writeString(dumpFile, dumpText.toString());

                final int finalNonEmpty = nonEmptySections;
                level.getServer().execute(() -> source.sendSuccess(() -> Component.literal(String.format(
                        "§6=== [Quick Voxel Engine: Chunk NBT Dump Exported] ===\n" +
                        "§7Chunk: §f(%d, %d) §7| Status: §b%s §7| DataVersion: §f%d\n" +
                        "§7Non-Empty Sections: §a%d §7| BlockEntities: §e%d\n" +
                        "§aExport File: §f%s",
                        chunkX, chunkZ, status, dataVersion,
                        finalNonEmpty, blockEntitiesTag.size(),
                        dumpFile.toAbsolutePath()
                )), false));

            } catch (Exception e) {
                level.getServer().execute(() -> source.sendFailure(Component.literal(
                        "§c[QVE Dump] Error exporting chunk NBT dump: " + e.getMessage()
                )));
            }
        });

        return 1;
    }
}
