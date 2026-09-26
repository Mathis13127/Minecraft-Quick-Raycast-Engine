package com.pixel.qve.neoforge.terrain;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.pixel.qve.neoforge.api.VoxelWriteAPI;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

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
                        .then(Commands.argument("chunkZ", IntegerArgumentType.integer())
                                .executes(ctx -> executeGenerate(ctx, TerrainStage.SURFACE))
                                .then(Commands.argument("stage", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            builder.suggest("biomes");
                                            builder.suggest("noise");
                                            builder.suggest("surface");
                                            builder.suggest("carvers");
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            String stageStr = StringArgumentType.getString(ctx, "stage");
                                            return executeGenerate(ctx, TerrainStage.fromString(stageStr));
                                        })))));

        // 2. /qve terrain sample <x> <z> (NoFogGiven / Radar LOD math sampler)
        terrainNode.then(Commands.literal("sample")
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(QveTerrainCommand::executeSample))));

        root.then(terrainNode);
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
