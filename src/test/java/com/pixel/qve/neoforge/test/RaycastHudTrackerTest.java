package com.pixel.qve.neoforge.test;

import com.pixel.qve.neoforge.client.hud.ClientHudState;
import com.pixel.qve.neoforge.hud.RaycastHudTracker;
import com.pixel.qve.neoforge.network.ClientboundRaycastHudPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RaycastHudTrackerTest {

    @AfterEach
    public void tearDown() {
        RaycastHudTracker.clearAll();
        ClientHudState.INSTANCE.reset();
    }

    @Test
    public void testHudSessionState() {
        RaycastHudTracker.HudSession session = new RaycastHudTracker.HudSession(99_999.0);
        assertEquals(99_999.0, session.getMaxDistance(), 1e-6);
        assertFalse(session.getInFlight().get(), "In-flight should initially be false");

        session.setMaxDistance(500.0);
        assertEquals(500.0, session.getMaxDistance(), 1e-6);

        assertTrue(session.getInFlight().compareAndSet(false, true));
        assertTrue(session.getInFlight().get());
        assertTrue(session.getInFlight().compareAndSet(true, false));
    }

    @Test
    public void testTrackingStateAndClear() {
        UUID testUuid = UUID.randomUUID();
        assertFalse(RaycastHudTracker.isTracking(testUuid));

        RaycastHudTracker.clearAll();
        assertFalse(RaycastHudTracker.isTracking(testUuid));
    }

    @Test
    public void testClientboundPayloadAndState() {
        ClientboundRaycastHudPayload payload = new ClientboundRaycastHudPayload(
                true,
                true,
                420_000L,
                14.5,
                100, 64, -200,
                "UP",
                42,
                "minecraft:oak_stairs",
                "facing=north",
                99_999.0
        );

        assertTrue(payload.active());
        assertTrue(payload.hit());
        assertEquals(420_000L, payload.elapsedNs());
        assertEquals(14.5, payload.distance(), 1e-6);
        assertEquals(100, payload.blockX());
        assertEquals(64, payload.blockY());
        assertEquals(-200, payload.blockZ());
        assertEquals("UP", payload.face());
        assertEquals(42, payload.blockId());
        assertEquals("minecraft:oak_stairs", payload.blockName());
        assertEquals("facing=north", payload.properties());
        assertEquals(99_999.0, payload.maxDistance(), 1e-6);

        // Update Client state
        ClientHudState.INSTANCE.update(payload);
        assertTrue(ClientHudState.INSTANCE.isActive());
        assertTrue(ClientHudState.INSTANCE.isVisible());
        assertNotNull(ClientHudState.INSTANCE.getLatestPayload());
        assertEquals(42, ClientHudState.INSTANCE.getLatestPayload().blockId());

        // Toggle visibility
        assertFalse(ClientHudState.INSTANCE.toggleVisible());
        assertTrue(ClientHudState.INSTANCE.toggleVisible());

        // Test inactive payload
        ClientboundRaycastHudPayload inactive = ClientboundRaycastHudPayload.inactive();
        assertFalse(inactive.active());
        assertFalse(inactive.hit());
        ClientHudState.INSTANCE.update(inactive);
        assertFalse(ClientHudState.INSTANCE.isActive());
    }
}
