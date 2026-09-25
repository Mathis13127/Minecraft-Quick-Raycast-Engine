package com.pixel.qve.neoforge.client;

import com.pixel.qve.neoforge.client.hud.ClientHudState;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * Handles client game bus events such as hotkey presses.
 */
public final class QveClientGameEvents {

    private QveClientGameEvents() {}

    /**
     * Handles keyboard input for QVE hotkeys.
     *
     * @param event Key input event
     */
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (QveClientEvents.TOGGLE_HUD_KEY.consumeClick()) {
            boolean visible = ClientHudState.INSTANCE.toggleVisible();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("§6[QVE] §7Telemetry Card visibility: " + (visible ? "§aVISIBLE" : "§cOFF")),
                        true
                );
            }
        }
    }

    /**
     * Resets client-side voxel grids and resources on logout/disconnect.
     *
     * @param event Client player logging out event
     */
    @SubscribeEvent
    public static void onClientLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge.reset();
    }
}
