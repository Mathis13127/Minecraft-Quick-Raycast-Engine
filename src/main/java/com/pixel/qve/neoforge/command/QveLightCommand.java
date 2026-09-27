package com.pixel.qve.neoforge.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.pixel.qve.neoforge.api.lighting.VoxelChunkLighting;
import com.pixel.qve.neoforge.api.lighting.VoxelLightingAPI;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelGrid;
import com.pixel.qve.neoforge.lighting.QveLightPacketHelper;
import com.pixel.qve.neoforge.lighting.QveLightPacketHelper.CorruptMode;
import com.pixel.qve.raycast.RaycastThreadPool;
import com.pixel.qve.world.VoxelChunkColumn;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Handles lighting update, recalculation, and diagnostic corruption commands under {@code /qve light}.
 * <p>
 * Operates strictly on chunks currently loaded in server RAM (throwing {@link BlockPosArgument#ERROR_NOT_LOADED}
 * if any chunk in the target area is unloaded, identical to vanilla {@code /fill}), while calculating
 * authentic Minecraft illumination entirely off-thread on {@link RaycastThreadPool} with 0 TPS lag
 * and dispatching {@link ClientboundLightUpdatePacket} to all tracking players.
 * </p>
 */
public final class QveLightCommand {

    private static final String[] CORRUPT_MODES = new String[]{"dark", "bright", "random"};

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

        // 3. /qve light corrupt [<mode>] [<pos>] / [<from>] [<to>]
        var corruptNode = Commands.literal("corrupt")
                .executes(ctx -> executeCorruptCurrentChunk(ctx, CorruptMode.DARK))
                .then(Commands.argument("mode", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CORRUPT_MODES, builder))
                        .executes(QveLightCommand::executeCorruptCurrentChunkWithArg)
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(QveLightCommand::executeCorruptSinglePos))
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(QveLightCommand::executeCorruptArea))));

        lightNode.then(updateNode);
        lightNode.then(relightNode);
        lightNode.then(corruptNode);

        root.then(lightNode);
    }

    // =========================================================================
    // RELIGHT / UPDATE COMMAND HANDLERS
    // =========================================================================

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

        // Re-ingest live LevelChunks in RAM (including 1-chunk boundary for 3x3 neighbor lighting context)
        for (int cx = chunkMinX - 1; cx <= chunkMaxX + 1; cx++) {
            for (int cz = chunkMinZ - 1; cz <= chunkMaxZ + 1; cz++) {
                net.minecraft.world.level.chunk.LevelChunk liveChunk = world.getChunkSource().getChunkNow(cx, cz);
                if (liveChunk != null) {
                    grid.ingestChunk(liveChunk);
                }
            }
        }
        for (int cx = chunkMinX; cx <= chunkMaxX; cx++) {
            for (int cz = chunkMinZ; cz <= chunkMaxZ; cz++) {
                VoxelChunkColumn col = grid.getColumn(cx, cz);
                if (col != null) {
                    col.setCachedLighting(null);
                }
            }
        }

        source.sendSuccess(() -> Component.literal(
                String.format("§e[QVE] Relighting %d chunk(s) across X:[%d..%d], Z:[%d..%d] (RAM only, off-thread)...",
                        chunkCount, chunkMinX, chunkMaxX, chunkMinZ, chunkMaxZ)
        ), false);

        CompletableFuture.runAsync(() -> {
            long startTime = System.nanoTime();
            List<CompletableFuture<VoxelChunkLighting>> futures = new ArrayList<>(chunkCount);

            for (int cx = chunkMinX; cx <= chunkMaxX; cx++) {
                for (int cz = chunkMinZ; cz <= chunkMaxZ; cz++) {
                    futures.add(VoxelLightingAPI.computeChunkLightingAsync(world, cx, cz));
                }
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

            List<ClientboundLightUpdatePacket> packets = new ArrayList<>(futures.size());
            for (CompletableFuture<VoxelChunkLighting> future : futures) {
                VoxelChunkLighting lighting = future.join();
                if (lighting != null) {
                    ChunkPos cpos = new ChunkPos(lighting.getChunkX(), lighting.getChunkZ());
                    packets.add(QveLightPacketHelper.createUpdatePacket(cpos, world, lighting));
                }
            }

            // Dispatch network packets on main server thread
            world.getServer().execute(() -> QveLightPacketHelper.sendPacketsToTrackingPlayers(world, packets));

            source.sendSuccess(() -> Component.literal(
                    String.format("§a[QVE] Successfully relighted %d chunk(s) in %dms (0ms server tick impact, synced to clients)!",
                            futures.size(), elapsedMs)
            ), true);
        }, RaycastThreadPool.getExecutor());

        return chunkCount;
    }

    // =========================================================================
    // CORRUPT COMMAND HANDLERS
    // =========================================================================

    private static int executeCorruptCurrentChunk(CommandContext<CommandSourceStack> ctx, CorruptMode mode) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerLevel world = source.getLevel();
        Vec3 pos = source.getPosition();
        BlockPos blockPos = BlockPos.containing(pos.x, pos.y, pos.z);

        if (!world.hasChunksAt(blockPos, blockPos)) {
            throw BlockPosArgument.ERROR_NOT_LOADED.create();
        }

        return executeCorruptAreaInternal(source, world, blockPos, blockPos, mode);
    }

    private static int executeCorruptCurrentChunkWithArg(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CorruptMode mode = parseMode(ctx);
        return executeCorruptCurrentChunk(ctx, mode);
    }

    private static int executeCorruptSinglePos(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerLevel world = source.getLevel();
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
        CorruptMode mode = parseMode(ctx);

        if (!world.hasChunksAt(pos, pos)) {
            throw BlockPosArgument.ERROR_NOT_LOADED.create();
        }

        return executeCorruptAreaInternal(source, world, pos, pos, mode);
    }

    private static int executeCorruptArea(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerLevel world = source.getLevel();
        BlockPos from = BlockPosArgument.getBlockPos(ctx, "from");
        BlockPos to = BlockPosArgument.getBlockPos(ctx, "to");
        CorruptMode mode = parseMode(ctx);

        if (!world.hasChunksAt(from, to)) {
            throw BlockPosArgument.ERROR_NOT_LOADED.create();
        }

        return executeCorruptAreaInternal(source, world, from, to, mode);
    }

    private static CorruptMode parseMode(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String modeStr = StringArgumentType.getString(ctx, "mode");
        CorruptMode mode = CorruptMode.fromString(modeStr);
        if (mode == null) {
            throw new SimpleCommandExceptionType(
                    Component.literal("Unknown corruption mode '" + modeStr + "'. Available modes: dark, bright, random.")
            ).create();
        }
        return mode;
    }

    private static int executeCorruptAreaInternal(
            CommandSourceStack source,
            ServerLevel world,
            BlockPos from,
            BlockPos to,
            CorruptMode mode
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
                String.format("§e[QVE] Corrupting lighting (%s) for %d chunk(s) across X:[%d..%d], Z:[%d..%d] (RAM only)...",
                        mode.name().toLowerCase(Locale.ROOT), chunkCount, chunkMinX, chunkMaxX, chunkMinZ, chunkMaxZ)
        ), false);

        CompletableFuture.runAsync(() -> {
            List<ClientboundLightUpdatePacket> packets = new ArrayList<>(chunkCount);

            for (int cx = chunkMinX; cx <= chunkMaxX; cx++) {
                for (int cz = chunkMinZ; cz <= chunkMaxZ; cz++) {
                    VoxelChunkColumn col = grid.getColumn(cx, cz);
                    if (col != null) {
                        col.setCachedLighting(null);
                    }
                    ChunkPos cpos = new ChunkPos(cx, cz);
                    packets.add(QveLightPacketHelper.createCorruptedPacket(cpos, world, mode));
                }
            }

            // Dispatch corrupted packets on main server thread
            world.getServer().execute(() -> QveLightPacketHelper.sendPacketsToTrackingPlayers(world, packets));

            source.sendSuccess(() -> Component.literal(
                    String.format("§c[QVE] Corrupted lighting (%s) for %d chunk(s)! Run '/qve light update' to restore.",
                            mode.name().toLowerCase(Locale.ROOT), chunkCount)
            ), true);
        }, RaycastThreadPool.getExecutor());

        return chunkCount;
    }
}
