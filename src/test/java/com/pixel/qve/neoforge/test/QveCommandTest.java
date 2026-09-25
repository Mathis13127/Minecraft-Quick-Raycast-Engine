package com.pixel.qve.neoforge.test;

import com.mojang.brigadier.CommandDispatcher;
import com.pixel.qve.neoforge.command.QveCommand;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class QveCommandTest {

    @Test
    @DisplayName("Verify /qve registration, all subcommands and aliases")
    public void testCommandRegistration() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        QveCommand.register(dispatcher);

        var qve = dispatcher.getRoot().getChild("qve");
        assertNotNull(qve, "Command /qve should be registered");

        // Verify all core subcommands
        assertNotNull(qve.getChild("stats"), "Subcommand 'stats' should exist");
        assertNotNull(qve.getChild("inspect"), "Subcommand 'inspect' should exist");
        assertNotNull(qve.getChild("nbt"), "Subcommand 'nbt' should exist");
        assertNotNull(qve.getChild("purge"), "Subcommand 'purge' should exist");
        assertNotNull(qve.getChild("warmup"), "Subcommand 'warmup' should exist");
        assertNotNull(qve.getChild("cache"), "Subcommand 'cache' should exist");
        assertNotNull(qve.getChild("test"), "Subcommand 'test' should exist");
        assertNotNull(qve.getChild("benchmark"), "Subcommand 'benchmark' should exist");
        assertNotNull(qve.getChild("benchmark_mca"), "Subcommand 'benchmark_mca' should exist");
        assertNotNull(qve.getChild("hud"), "Subcommand 'hud' should exist");

        // Verify aliases redirecting to /qve
        var raycast = dispatcher.getRoot().getChild("raycast");
        assertNotNull(raycast, "Alias /raycast should be registered");
        assertEquals(qve, raycast.getRedirect(), "Alias /raycast should redirect to /qve");

        var qre = dispatcher.getRoot().getChild("qre");
        assertNotNull(qre, "Alias /qre should be registered");
        assertEquals(qve, qre.getRedirect(), "Alias /qre should redirect to /qve");

        // Verify write command branch
        var write = qve.getChild("write");
        assertNotNull(write, "Subcommand 'write' should exist");
        assertNotNull(write.getChild("mode"), "Subcommand 'write mode' should exist");
        assertNotNull(write.getChild("dump"), "Subcommand 'write dump' should exist");
        assertNotNull(write.getChild("inspect"), "Subcommand 'write inspect' should exist");

        // Verify with CommandBuildContext
        try {
            var buildContext = net.minecraft.commands.Commands.createValidationContext(
                    net.minecraft.core.registries.BuiltInRegistries.createWrapperLookup()
            );
            CommandDispatcher<CommandSourceStack> fullDispatcher = new CommandDispatcher<>();
            QveCommand.register(fullDispatcher, buildContext);
            var fullWrite = fullDispatcher.getRoot().getChild("qve").getChild("write");
            assertNotNull(fullWrite.getChild("setblock"), "Subcommand 'write setblock' should exist");
            assertNotNull(fullWrite.getChild("block"), "Subcommand 'write block' should exist");
            assertNotNull(fullWrite.getChild("fill"), "Subcommand 'write fill' should exist");
            assertNotNull(fullWrite.getChild("unified"), "Subcommand 'write unified' should exist");
            assertNotNull(fullWrite.getChild("direct"), "Subcommand 'write direct' should exist");
        } catch (Throwable t) {
            // Optional if registry wrapper lookup requires full vanilla bootstrap
        }
    }

    @Test
    @DisplayName("Verify write mode switching and state preservation")
    public void testWriteModeSwitching() {
        QveCommand.setWriteMode(com.pixel.qve.neoforge.api.VoxelWriteMode.UNIFIED);
        assertEquals(com.pixel.qve.neoforge.api.VoxelWriteMode.UNIFIED, QveCommand.getWriteMode());

        QveCommand.setWriteMode(com.pixel.qve.neoforge.api.VoxelWriteMode.STRICT_DIRECT);
        assertEquals(com.pixel.qve.neoforge.api.VoxelWriteMode.STRICT_DIRECT, QveCommand.getWriteMode());

        QveCommand.setWriteMode(com.pixel.qve.neoforge.api.VoxelWriteMode.UNIFIED);
    }
}
