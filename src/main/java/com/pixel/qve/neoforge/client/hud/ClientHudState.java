package com.pixel.qve.neoforge.client.hud;

import com.pixel.qve.neoforge.network.ClientboundRaycastHudPayload;

/**
 * Singleton state storing client-side telemetry received from the server.
 */
public final class ClientHudState {

    /** Singleton instance of the client HUD state. */
    public static final ClientHudState INSTANCE = new ClientHudState();

    private volatile ClientboundRaycastHudPayload latestPayload;
    private volatile boolean active = false;
    private volatile boolean visible = true;
    private volatile long lastReceivedMs = 0L;

    private ClientHudState() {}

    /**
     * Updates the state with new telemetry payload.
     *
     * @param payload Telemetry payload
     */
    public void update(ClientboundRaycastHudPayload payload) {
        this.latestPayload = payload;
        this.active = payload.active();
        this.lastReceivedMs = System.currentTimeMillis();
    }

    /**
     * Returns whether tracking is currently active on the server.
     *
     * @return True if active
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Returns whether the client HUD overlay is currently visible.
     *
     * @return True if visible
     */
    public boolean isVisible() {
        return visible;
    }

    /**
     * Sets HUD visibility on the client.
     *
     * @param visible Desired visibility
     */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    /**
     * Toggles client-side HUD visibility.
     *
     * @return New visibility state
     */
    public boolean toggleVisible() {
        this.visible = !this.visible;
        return this.visible;
    }

    /**
     * Gets the latest received payload.
     *
     * @return Latest payload, or null if none
     */
    public ClientboundRaycastHudPayload getLatestPayload() {
        return latestPayload;
    }

    /**
     * Gets timestamp of last received payload in milliseconds.
     *
     * @return Epoch millisecond timestamp
     */
    public long getLastReceivedMs() {
        return lastReceivedMs;
    }

    /**
     * Resets state.
     */
    public void reset() {
        this.latestPayload = null;
        this.active = false;
        this.lastReceivedMs = 0L;
    }
}
