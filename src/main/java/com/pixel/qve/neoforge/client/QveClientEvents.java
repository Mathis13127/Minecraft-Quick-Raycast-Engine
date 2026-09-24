package com.pixel.qve.neoforge.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.pixel.qve.neoforge.QuickVoxelEngineMod;
import com.pixel.qve.neoforge.client.hud.ClientHudRenderer;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Mod bus event handlers for client-side registrations.
 */
public final class QveClientEvents {

    /**
     * Key binding to toggle client HUD visibility (unbound by default to prevent conflicts).
     */
    public static final KeyMapping TOGGLE_HUD_KEY = new KeyMapping(
            "key.quickvoxelengine.toggle_hud",
            InputConstants.UNKNOWN.getValue(),
            "key.categories.quickvoxelengine"
    );

    private QveClientEvents() {}

    /**
     * Registers the dedicated telemetry HUD GUI layer.
     *
     * @param event GUI layer registration event
     */
    public static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(
                VanillaGuiLayers.CHAT,
                ResourceLocation.fromNamespaceAndPath(QuickVoxelEngineMod.MOD_ID, "telemetry_hud"),
                ClientHudRenderer::render
        );
    }

    /**
     * Registers client key bindings.
     *
     * @param event Key mapping registration event
     */
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_HUD_KEY);
    }
}
