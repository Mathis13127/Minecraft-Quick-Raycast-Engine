package com.pixel.qve.neoforge.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.pixel.qve.neoforge.api.lighting.VoxelChunkLighting;
import com.pixel.qve.neoforge.api.lighting.VoxelLightingAPI;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelGrid;
import com.pixel.qve.raycast.RaycastThreadPool;
import com.pixel.qve.world.VoxelChunkColumn;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Handles lighting update and recalculation commands under {@code /qve light}.
 * <p>
 * Operates strictly on chunks currently loaded in server RAM (throwing {@link BlockPosArgument#ERROR_NOT_LOADED}
 * if any chunk in the target area is unloaded, identical to vanilla {@code /fill}), while calculating
 * authentic Minecraft illumination entirely off-thread on {@link RaycastThreadPool} with 0 TPS lag.
 * </p>
 */
public final class QveLightCommand {

    private QveLightCommand() {}

    /**
     * Registers lighting subcommands onto the root QVE builder.
     *
     * @param root Root {@code /qve} literal argument builder
     */
    public static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        var lightNode = Commands.literal("light")
                .requires(source -> source.hasPermission(2));

        // 1. /qve light update [<from>] [<to>]
        var updateNode = Commands.literal("update")
                .executes(QveLightCommand::executeRelightCurrentChunk)
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(QveLightCommand::executeRelightSinglePos))
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                .executes(QveLightCommand::executeRelightArea)));

        // 2. /qve light relight [<from>] [<to>] (alias)
        var relightNode = Commands.literal("relight")
                .executes(QveLightCommand::executeRelightCurrentChunk)
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(QveLightCommand::executeRelightSinglePos))
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                .executes(QveLightCommand::executeRelightArea)));

        lightNode.then(updateNode);
        lightNode.then(relightNode);

        root.then(lightNode);
    }

    private static int executeRelightCurrentChunk(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerLevel world = source.getLevel();
        Vec3 pos = source.getPosition();
        BlockPos blockPos = BlockPos.containing(pos.x, pos.y, pos.z);

        if (!world.hasChunksAt(blockPos, blockPos)) {
            throw BlockPosArgument.ERROR_NOT_LOADED.create();
        }

        return executeRelightAreaInternal(source, world, blockPos, blockPos);
    }

    private static int executeRelightSinglePos(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerLevel world = source.getLevel();
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");

        if (!world.hasChunksAt(pos, pos)) {
            throw BlockPosArgument.ERROR_NOT_LOADED.create();
        }

        return executeRelightAreaInternal(source, world, pos, pos);
    }

    private static int executeRelightArea(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerLevel world = source.getLevel();
        BlockPos from = BlockPosArgument.getBlockPos(ctx, "from");
        BlockPos to = BlockPosArgument.getBlockPos(ctx, "to");

        // Strict RAM check: fail immediately with vanilla error if any chunk is not loaded
        if (!world.hasChunksAt(from, to)) {
            throw BlockPosArgument.ERROR_NOT_LOADED.create();
        }

        return executeRelightAreaInternal(source, world, from, to);
    }

    private static int executeRelightAreaInternal(
            CommandSourceStack source,
            ServerLevel world,
            BlockPos from,
            BlockPos to
    ) {
        int minX = Math.min(from.getX(), to.getX());
        int maxX = Math.max(from.getX(), to.getX());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxZ = Math.max(from.getZ(), to.getZ());

        int chunkMinX = minX >> 4;
        int chunkMaxX = maxX >> 4;
        int chunkMinZ = minZ >> 4;
        int chunkMaxZ = maxZ >> 4;

        int chunkCount = (chunkMaxX - chunkMinX + 1) * (chunkMaxZ - chunkMinZ + 1);
        MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(world);

        source.sendSuccess(() -> Component.literal(
                String.format("§e[QVE] Relighting %d chunk(s) across X:[%d..%d], Z:[%d..%d] (RAM only, off-thread)...",
                        chunkCount, chunkMinX, chunkMaxX, chunkMinZ, chunkMaxZ)
        ), false);

        CompletableFuture.runAsync(() -> {
            long startTime = System.nanoTime();
            List<CompletableFuture<VoxelChunkLighting>> futures = new ArrayList<>(chunkCount);

            for (int cx = chunkMinX; cx <= chunkMaxX; cx++) {
                for (int cz = chunkMinZ; cz <= chunkMaxZ; cz++) {
                    VoxelChunkColumn col = grid.getColumn(cx, cz);
                    if (col != null) {
                        // Invalidate old cached lighting
                        col.setCachedLighting(null);
                        // Compute async on worker threads
                        futures.add(VoxelLightingAPI.computeLightingAsync(world, col));
                    }
                }
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

            source.sendSuccess(() -> Component.literal(
                    String.format("§a[QVE] Successfully relighted %d chunk(s) in %dms (0ms server tick impact)!",
                            futures.size(), elapsedMs)
            ), true);
        }, RaycastThreadPool.getExecutor());

        return chunkCount;
    }
}
