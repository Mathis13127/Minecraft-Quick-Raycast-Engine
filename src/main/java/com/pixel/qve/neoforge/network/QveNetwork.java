package com.pixel.qve.neoforge.network;

import com.pixel.qve.neoforge.QuickVoxelEngineMod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers all network payloads for Quick Voxel Engine.
 */
public final class QveNetwork {

    private QveNetwork() {}

    /**
     * Registers the HUD telemetry payload handler.
     *
     * @param event Payload registration event
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(QuickVoxelEngineMod.MOD_ID).versioned("1.0.0");
        registrar.playToClient(
                ClientboundRaycastHudPayload.TYPE,
                ClientboundRaycastHudPayload.STREAM_CODEC,
                ClientboundRaycastHudPayload::handle
        );
        QuickVoxelEngineMod.LOGGER.info("[QuickVoxelEngine] Registered network telemetry payloads.");
    }
}
