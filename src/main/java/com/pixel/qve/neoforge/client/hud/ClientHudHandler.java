package com.pixel.qve.neoforge.client.hud;

import com.pixel.qve.neoforge.network.ClientboundRaycastHudPayload;

/**
 * Entry point for client-side processing of raycast telemetry packets.
 */
public final class ClientHudHandler {

    private ClientHudHandler() {}

    /**
     * Updates client state with telemetry packet.
     *
     * @param payload Received packet
     */
    public static void handle(ClientboundRaycastHudPayload payload) {
        ClientHudState.INSTANCE.update(payload);
    }
}
