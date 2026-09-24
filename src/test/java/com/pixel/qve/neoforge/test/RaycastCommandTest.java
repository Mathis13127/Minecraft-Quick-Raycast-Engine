package com.pixel.qve.neoforge.test;

import com.mojang.brigadier.CommandDispatcher;
import com.pixel.qve.neoforge.command.QveCommand;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class RaycastCommandTest {

    @Test
    public void testCommandRegistration() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        QveCommand.register(dispatcher);

        assertNotNull(dispatcher.getRoot().getChild("raycast"), "Command /raycast should be registered");
        assertNotNull(dispatcher.getRoot().getChild("qre"), "Alias /qre should be registered");

        var raycast = dispatcher.getRoot().getChild("raycast");
        assertNotNull(raycast.getChild("test"), "Subcommand 'test' should exist");
        assertNotNull(raycast.getChild("benchmark"), "Subcommand 'benchmark' should exist");
        assertNotNull(raycast.getChild("benchmark_mca"), "Subcommand 'benchmark_mca' should exist");
        assertNotNull(raycast.getChild("cache"), "Subcommand 'cache' should exist");
        assertNotNull(raycast.getChild("hud"), "Subcommand 'hud' should exist");
    }
}
