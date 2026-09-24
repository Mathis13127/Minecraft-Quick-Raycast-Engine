package com.pixel.qve.neoforge.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Initializes client-specific event handlers and listeners.
 */
public final class QveClientSetup {

    private QveClientSetup() {}

    /**
     * Registers client event listeners on mod bus and game bus.
     *
     * @param modEventBus Mod lifecycle event bus
     */
    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(QveClientEvents::registerGuiLayers);
        modEventBus.addListener(QveClientEvents::registerKeyMappings);
        NeoForge.EVENT_BUS.register(QveClientGameEvents.class);
    }
}
