package com.pixel.qve.neoforge.test;

import com.pixel.qve.neoforge.hud.RaycastHudTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RaycastHudTrackerTest {

    @AfterEach
    public void tearDown() {
        RaycastHudTracker.clearAll();
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
}
