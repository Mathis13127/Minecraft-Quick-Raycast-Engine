package com.pixel.qve.neoforge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.pixel.qve.mca.writer.WriteOptions;
import com.pixel.qve.neoforge.api.VoxelWriteMode;
import com.pixel.qve.neoforge.hud.RaycastHudTracker;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Root command dispatcher for the Quick Voxel Engine (QVE).
 * Wires modular subcommands for writing, benchmarks, inspections, and HUD diagnostics.
 */
public final class QveCommand {

    private QveCommand() {}

    /**
     * Registers the root {@code /qve} command and alias {@code /raycast}.
     *
     * @param dispatcher Command dispatcher instance
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        register(dispatcher, null);
    }

    /**
     * Registers the root {@code /qve} command with context-aware block state parsers.
     *
     * @param dispatcher   Command dispatcher instance
     * @param buildContext Minecraft command build context
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        var qveRoot = Commands.literal("qve")
                .requires(source -> source.hasPermission(0));

        // 1. Attach write subsystem
        qveRoot.then(QveWriteCommand.register(buildContext));

        // 2. Attach inspection & diagnostic subsystem
        QveInspectCommand.register(qveRoot);

        // 3. Attach benchmark & testing subsystem
        QveBenchmarkCommand.register(qveRoot);

        // 4. Attach HUD subsystem
        qveRoot.then(Commands.literal("hud")
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

        // Register /raycast alias redirecting to /qve (/qre is removed)
        dispatcher.register(Commands.literal("raycast").redirect(qveNode));
    }

    /**
     * Retrieves the currently active write mode.
     */
    public static VoxelWriteMode getWriteMode() {
        return QveWriteCommand.getWriteMode();
    }

    /**
     * Sets the active write mode.
     */
    public static void setWriteMode(VoxelWriteMode mode) {
        QveWriteCommand.setWriteMode(mode);
    }

    /**
     * Retrieves the currently active write options.
     */
    public static WriteOptions getWriteOptions() {
        return QveWriteCommand.getWriteOptions();
    }

    /**
     * Sets the active write options.
     */
    public static void setWriteOptions(WriteOptions options) {
        QveWriteCommand.setWriteOptions(options);
    }
}
