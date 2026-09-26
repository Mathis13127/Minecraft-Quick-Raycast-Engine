package com.pixel.qve.neoforge.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.pixel.qve.mca.McaRegionReader;
import com.pixel.qve.mca.writer.WriteOptions;
import com.pixel.qve.neoforge.api.ChunkWriteBatch;
import com.pixel.qve.neoforge.api.VoxelWriteAPI;
import com.pixel.qve.neoforge.api.VoxelWriteMode;
import com.pixel.qve.neoforge.api.WriteResult;
import com.pixel.qve.neoforge.api.WriteStatus;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.neoforge.bridge.writer.MinecraftVoxelWriter;
import com.pixel.qve.raycast.RaycastThreadPool;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

/**
 * Handles all block mutation, fill, write mode, and chunk dump diagnostic commands under {@code /qve write}.
 */
public final class QveWriteCommand {

    private static volatile VoxelWriteMode currentMode = VoxelWriteMode.UNIFIED;
    private static volatile WriteOptions currentWriteOptions = WriteOptions.DEFAULT;

    private QveWriteCommand() {}

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

    /**
     * Builds and returns the {@code write} argument builder subtree.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> register(CommandBuildContext buildContext) {
        var writeNode = Commands.literal("write")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("mode")
                        .executes(QveWriteCommand::executeWriteModeStatus)
                        .then(Commands.literal("unified")
                                .executes(ctx -> executeSetWriteMode(ctx, VoxelWriteMode.UNIFIED)))
                        .then(Commands.literal("strict")
                                .executes(ctx -> executeSetWriteMode(ctx, VoxelWriteMode.STRICT_DIRECT)))
                        .then(Commands.literal("direct")
                                .executes(ctx -> executeSetWriteMode(ctx, VoxelWriteMode.STRICT_DIRECT))))
                .then(Commands.literal("dump")
                        .then(Commands.argument("chunkX", IntegerArgumentType.integer())
                                .then(Commands.argument("chunkZ", IntegerArgumentType.integer())
                                        .executes(QveWriteCommand::executeWriteDump))))
                .then(Commands.literal("inspect")
                        .then(Commands.argument("chunkX", IntegerArgumentType.integer())
                                .then(Commands.argument("chunkZ", IntegerArgumentType.integer())
                                        .executes(QveWriteCommand::executeWriteInspect))));

        if (buildContext != null) {
            var setblockArg = Commands.argument("block", BlockStateArgument.block(buildContext))
                    .executes(ctx -> executeWriteBlockWithMode(ctx, currentMode))
                    .then(Commands.literal("unified").executes(ctx -> executeWriteBlockWithMode(ctx, VoxelWriteMode.UNIFIED)))
                    .then(Commands.literal("strict").executes(ctx -> executeWriteBlockWithMode(ctx, VoxelWriteMode.STRICT_DIRECT)))
                    .then(Commands.literal("direct").executes(ctx -> executeWriteBlockWithMode(ctx, VoxelWriteMode.STRICT_DIRECT)));

            var fillBlockArg = Commands.argument("block", BlockStateArgument.block(buildContext))
                    .executes(ctx -> executeWriteFill(ctx, currentMode, null))
                    .then(Commands.literal("unified").executes(ctx -> executeWriteFill(ctx, VoxelWriteMode.UNIFIED, null)))
                    .then(Commands.literal("strict").executes(ctx -> executeWriteFill(ctx, VoxelWriteMode.STRICT_DIRECT, null)))
                    .then(Commands.literal("direct").executes(ctx -> executeWriteFill(ctx, VoxelWriteMode.STRICT_DIRECT, null)))
                    .then(Commands.literal("replace")
                            .then(Commands.argument("filter", BlockStateArgument.block(buildContext))
                                    .executes(ctx -> executeWriteFill(ctx, currentMode, BlockStateArgument.getBlock(ctx, "filter").getState()))
                                    .then(Commands.literal("unified").executes(ctx -> executeWriteFill(ctx, VoxelWriteMode.UNIFIED, BlockStateArgument.getBlock(ctx, "filter").getState())))
                                    .then(Commands.literal("strict").executes(ctx -> executeWriteFill(ctx, VoxelWriteMode.STRICT_DIRECT, BlockStateArgument.getBlock(ctx, "filter").getState())))
                                    .then(Commands.literal("direct").executes(ctx -> executeWriteFill(ctx, VoxelWriteMode.STRICT_DIRECT, BlockStateArgument.getBlock(ctx, "filter").getState())))));

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
                            .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                                    .executes(ctx -> executeWriteBlockWithMode(ctx, VoxelWriteMode.UNIFIED)))))
            .then(Commands.literal("direct")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                            .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                                    .executes(ctx -> executeWriteBlockWithMode(ctx, VoxelWriteMode.STRICT_DIRECT)))));
        }

        return writeNode;
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
        BlockState state = BlockStateArgument.getBlock(ctx, "block").getState();

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
                ? VoxelWriteAPI.setBlockDirectAsync(level, pos, state, null)
                : VoxelWriteAPI.setBlockUnifiedAsync(level, pos, state);

        future.thenAccept(res -> {
            level.getServer().execute(() -> {
                if (res.isSuccess()) {
                    double ms = res.durationNanos() / 1_000_000.0;
                    String backendUsed = (res.status() == WriteStatus.SUCCESS_RAM)
                            ? "§aRAM Live (LevelChunk)"
                            : (res.status() == WriteStatus.SUCCESS_DEFERRED)
                            ? "§dDeferred Queue (Hybrid Region)"
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
        BlockState state = BlockStateArgument.getBlock(ctx, "block").getState();

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
                    long defCount = res.results().stream().filter(r -> r.status() == WriteStatus.SUCCESS_DEFERRED).count();
                    String distStr = (defCount > 0)
                            ? String.format("RAM: §a%d§7, Disk: §b%d§7, Deferred: §d%d§7", res.ramChunkCount(), res.diskChunkCount() - defCount, defCount)
                            : String.format("RAM: §a%d§7, Disk: §b%d§7", res.ramChunkCount(), res.diskChunkCount());
                    source.sendSuccess(() -> Component.literal(String.format(
                            "§a=== [Quick Voxel Engine: Batch Fill SUCCESS] ===\n" +
                            "§7Total Voxels: §f%,d §7| Chunks: §e%,d §7(%s)\n" +
                            "§7Policy: §f%s §7(§b%s§7) | Duration: §f%.2f ms §7(§a§l%,.0f voxels/s§7)",
                            res.totalBlocks(),
                            res.totalSubmitted(),
                            distStr,
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

        Path regionDir = MinecraftVoxelWriter.resolveRegionDirectory(level);
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
                CompoundTag tag = net.minecraft.nbt.NbtIo.read(new DataInputStream(new ByteArrayInputStream(nbtBytes)));

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

    private static int executeWriteInspect(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        int cx = IntegerArgumentType.getInteger(ctx, "chunkX");
        int cz = IntegerArgumentType.getInteger(ctx, "chunkZ");

        boolean loaded = VoxelWriteAPI.isLoadedInRam(level, cx, cz);
        boolean safe = VoxelWriteAPI.isSafeForDirectDiskWrite(level, cx, cz);

        int rx = cx >> 5;
        int rz = cz >> 5;
        Path regionDir = MinecraftVoxelWriter.resolveRegionDirectory(level);
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
}
