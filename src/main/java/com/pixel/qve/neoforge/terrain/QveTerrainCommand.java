package com.pixel.qve.neoforge.terrain;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.concurrent.CompletableFuture;

/**
 * Handles all diagnostic, chunk generation, and terrain sampling commands under {@code /qve terrain}.
 */
public final class QveTerrainCommand {

    private QveTerrainCommand() {}

    /**
     * Registers the {@code terrain} argument builder subtree onto the root QVE builder.
     *
     * @param root Root {@code /qve} literal argument builder
     */
    public static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        var terrainNode = Commands.literal("terrain")
                .requires(source -> source.hasPermission(2));

        // 1. /qve terrain generate <chunkX> <chunkZ> [stage]
        terrainNode.then(Commands.literal("generate")
                .then(Commands.argument("chunkX", IntegerArgumentType.integer())
                        .suggests(QveTerrainCommand::suggestTargetedChunkX)
                        .then(Commands.argument("chunkZ", IntegerArgumentType.integer())
                                .suggests(QveTerrainCommand::suggestTargetedChunkZ)
                                .executes(ctx -> executeGenerate(ctx, TerrainStage.SURFACE))
                                .then(Commands.argument("stage", StringArgumentType.word())
                                        .suggests(QveTerrainCommand::suggestStages)
                                        .executes(ctx -> {
                                            String stageStr = StringArgumentType.getString(ctx, "stage");
                                            return executeGenerate(ctx, TerrainStage.fromString(stageStr));
                                        })))));

        // 2. /qve terrain stamp <srcChunkX> <srcChunkZ> [targetPos] [stage]
        var stampSrcZ = Commands.argument("srcChunkZ", IntegerArgumentType.integer())
                .suggests(QveTerrainCommand::suggestTargetedChunkZ)
                // /qve terrain stamp <srcX> <srcZ> (defaults to player current chunk)
                .executes(ctx -> executeStamp(ctx, null, TerrainStage.SURFACE))
                .then(Commands.argument("targetPos", BlockPosArgument.blockPos())
                        .executes(ctx -> executeStamp(ctx, BlockPosArgument.getBlockPos(ctx, "targetPos"), TerrainStage.SURFACE))
                        .then(Commands.argument("stage", StringArgumentType.word())
                                .suggests(QveTerrainCommand::suggestStages)
                                .executes(ctx -> {
                                    BlockPos targetPos = BlockPosArgument.getBlockPos(ctx, "targetPos");
                                    TerrainStage stage = TerrainStage.fromString(StringArgumentType.getString(ctx, "stage"));
                                    return executeStamp(ctx, targetPos, stage);
                                })))
                .then(Commands.argument("stage", StringArgumentType.word())
                        .suggests(QveTerrainCommand::suggestStages)
                        .executes(ctx -> {
                            TerrainStage stage = TerrainStage.fromString(StringArgumentType.getString(ctx, "stage"));
                            return executeStamp(ctx, null, stage);
                        }));

        terrainNode.then(Commands.literal("stamp")
                .then(Commands.argument("srcChunkX", IntegerArgumentType.integer())
                        .suggests(QveTerrainCommand::suggestTargetedChunkX)
                        .then(stampSrcZ)));

        // 3. /qve terrain sample <x> <z> (NoFogGiven / Radar LOD math sampler)
        terrainNode.then(Commands.literal("sample")
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(QveTerrainCommand::executeSample))));

        root.then(terrainNode);
    }

    private static CompletableFuture<Suggestions> suggestStages(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        builder.suggest("biomes");
        builder.suggest("noise");
        builder.suggest("surface");
        builder.suggest("carvers");
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestTargetedChunkX(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        CommandSourceStack source = ctx.getSource();
        if (source.getEntity() instanceof ServerPlayer player) {
            HitResult hit = player.pick(128.0, 0.0f, false);
            if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bhr) {
                builder.suggest(String.valueOf(bhr.getBlockPos().getX() >> 4));
            }
            builder.suggest(String.valueOf(player.blockPosition().getX() >> 4));
        }
        builder.suggest("0");
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestTargetedChunkZ(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        CommandSourceStack source = ctx.getSource();
        if (source.getEntity() instanceof ServerPlayer player) {
            HitResult hit = player.pick(128.0, 0.0f, false);
            if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bhr) {
                builder.suggest(String.valueOf(bhr.getBlockPos().getZ() >> 4));
            }
            builder.suggest(String.valueOf(player.blockPosition().getZ() >> 4));
        }
        builder.suggest("0");
        return builder.buildFuture();
    }

    private static int executeGenerate(CommandContext<CommandSourceStack> ctx, TerrainStage stage) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        int chunkX = IntegerArgumentType.getInteger(ctx, "chunkX");
        int chunkZ = IntegerArgumentType.getInteger(ctx, "chunkZ");

        source.sendSuccess(() -> Component.literal(String.format(
                "§7[QVE Terrain] Generating chunk §f(%d, %d) §7at stage §e[%s] §7from seed §f%d §7via worker thread...",
                chunkX, chunkZ, stage.getName(), level.getSeed()
        )), false);

        QveTerrainEngine.generateChunkAsync(level, chunkX, chunkZ, stage).thenAccept(res -> {
            level.getServer().execute(() -> {
                if (res.success()) {
                    int centerX = (chunkX << 4) + 8;
                    int centerZ = (chunkZ << 4) + 8;
                    int surfaceY = DistantTerrainSampler.sampleSurfaceHeight(level, centerX, centerZ);

                    source.sendSuccess(() -> Component.literal(String.format(
                            "§a=== [Quick Voxel Engine: Terrain Generation SUCCESS] ===\n" +
                            "§7Chunk: §f(%d, %d) §7| Stage: §e%s §7(Status: §b%s§7)\n" +
                            "§7Duration: §f%.2f ms §7| MCA Sector: §b%d §7| Bytes: §b%,d\n" +
                            "§7Center Pos: §e[%d, ~%d, %d]\n" +
                            "§aTeleport: §f/tp @s %d %d %d",
                            res.chunkX(), res.chunkZ(), res.stage().getName(), res.stage().getChunkStatus().getName(),
                            res.durationMs(), res.sectorOffset(), res.compressedBytes(),
                            centerX, surfaceY, centerZ,
                            centerX, Math.max(surfaceY + 2, 70), centerZ
                    )), true);
                } else {
                    source.sendFailure(Component.literal(String.format(
                            "§c[QVE Terrain] Chunk generation FAILED for (%d, %d): %s",
                            res.chunkX(), res.chunkZ(), res.errorMessage()
                    )));
                }
            });
        });

        return 1;
    }

    private static int executeStamp(CommandContext<CommandSourceStack> ctx, BlockPos targetPos, TerrainStage stage) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        int srcChunkX = IntegerArgumentType.getInteger(ctx, "srcChunkX");
        int srcChunkZ = IntegerArgumentType.getInteger(ctx, "srcChunkZ");

        // If targetPos is null, default to player's current position (or line of sight)
        int dstChunkX;
        int dstChunkZ;
        if (targetPos != null) {
            dstChunkX = targetPos.getX() >> 4;
            dstChunkZ = targetPos.getZ() >> 4;
        } else if (source.getEntity() instanceof ServerPlayer player) {
            HitResult hit = player.pick(128.0, 0.0f, false);
            if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bhr) {
                dstChunkX = bhr.getBlockPos().getX() >> 4;
                dstChunkZ = bhr.getBlockPos().getZ() >> 4;
            } else {
                dstChunkX = player.blockPosition().getX() >> 4;
                dstChunkZ = player.blockPosition().getZ() >> 4;
            }
        } else {
            dstChunkX = srcChunkX;
            dstChunkZ = srcChunkZ;
        }

        boolean liveRam = level.isLoaded(new BlockPos(dstChunkX << 4, 64, dstChunkZ << 4));
        long commandStartNs = System.nanoTime();

        source.sendSuccess(() -> Component.literal(String.format(
                "§7[QVE Terrain] Stamping virtual chunk §e(%d, %d) §7-> target §f(%d, %d) §7[%s] at stage §e[%s]§7...",
                srcChunkX, srcChunkZ, dstChunkX, dstChunkZ,
                liveRam ? "§aLive RAM" : "§bOffline Disk",
                stage.getName()
        )), false);

        QveTerrainEngine.generateVirtualChunkAsync(level, srcChunkX, srcChunkZ, stage)
                .thenCompose(vc -> {
                    double genMs = vc.getGenerationDurationNanos() / 1_000_000.0;
                    boolean fromCache = vc.isFromCache();
                    return QveTerrainEngine.stampVirtualChunkAsync(level, vc, dstChunkX, dstChunkZ)
                            .thenApply(res -> new StampingSummary(vc, res, genMs, fromCache));
                })
                .thenAccept(summary -> {
                    level.getServer().execute(() -> {
                        long totalNs = System.nanoTime() - commandStartNs;
                        double totalMs = totalNs / 1_000_000.0;
                        var res = summary.writeResult();
                        if (res.isSuccess()) {
                            double stampMs = res.durationNanos() / 1_000_000.0;
                            double genMs = summary.genMs();
                            String cacheTag = summary.fromCache() ? "§a[CACHE HIT]" : "§e[WORLDGEN]";
                            String modeStr = (res.status() == com.pixel.qve.neoforge.api.WriteStatus.SUCCESS_RAM)
                                    ? "§aLive RAM (Packet Synced)"
                                    : "§bAnvil MCA Disk";

                            source.sendSuccess(() -> Component.literal(String.format(
                                    "§a=== [Quick Voxel Engine: Virtual Chunk STAMP SUCCESS] ===\n" +
                                    "§7Source Seed Chunk: §e(%d, %d) §8| §7Stage: §e%s %s\n" +
                                    "§7Destination Chunk: §f(%d, %d) §8| §7Target: %s\n" +
                                    "§7Total Duration: §f%.2f ms §7(Gen: §b%.2f ms §7| Stamp: §e%.2f ms§7)\n" +
                                    "§7Status: §a%s",
                                    srcChunkX, srcChunkZ, stage.getName(), cacheTag,
                                    dstChunkX, dstChunkZ, modeStr,
                                    totalMs, genMs, stampMs,
                                    res.status()
                            )), true);
                        } else {
                            source.sendFailure(Component.literal(String.format(
                                    "§c[QVE Terrain] Stamping FAILED for target (%d, %d): %s",
                                    dstChunkX, dstChunkZ, res.errorMessage()
                            )));
                        }
                    });
                });

        return 1;
    }

    private record StampingSummary(
            com.pixel.qve.neoforge.api.terrain.VirtualChunk virtualChunk,
            com.pixel.qve.neoforge.api.WriteResult writeResult,
            double genMs,
            boolean fromCache
    ) {}

    private static int executeSample(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");

        long t0 = System.nanoTime();
        int surfaceY = DistantTerrainSampler.sampleSurfaceHeight(level, x, z);
        int floorY = DistantTerrainSampler.sampleOceanFloorHeight(level, x, z);
        long elapsedNs = System.nanoTime() - t0;

        source.sendSuccess(() -> Component.literal(String.format(
                "§6=== [Quick Voxel Engine: Distant Terrain Sampler] ===\n" +
                "§7Position: §f[%d, %d] §7in §e%s\n" +
                "§7Surface Height: §aY = %d §7| Ocean Floor: §bY = %d\n" +
                "§7Query Latency: §d%,d ns §7(Zero disk I/O, pure noise router)",
                x, z, level.dimension().location(),
                surfaceY, floorY,
                elapsedNs
        )), false);

        return 1;
    }
}
