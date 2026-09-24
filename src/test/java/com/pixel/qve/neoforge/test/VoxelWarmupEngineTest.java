package com.pixel.qve.neoforge.test;

import com.pixel.qve.neoforge.warmup.VoxelWarmupEngine;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public class VoxelWarmupEngineTest {

    @BeforeAll
    static void initMinecraft() {
        try {
            net.neoforged.fml.loading.LoadingModList.of(
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.Map.of()
            );
            SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
            Bootstrap.bootStrap();
        } catch (Exception e) {
            System.err.println("Bootstrap warning: " + e);
        }
    }

    @Test
    @DisplayName("Verify synchronous and asynchronous warmup pre-indexes all registered vanilla blocks and states")
    void testWarmupExecution() throws Exception {
        VoxelWarmupEngine.reset();
        assertFalse(VoxelWarmupEngine.isWarm());

        VoxelWarmupEngine.WarmupStats stats = VoxelWarmupEngine.runWarmup();
        assertNotNull(stats);
        assertTrue(stats.blockCount() > 500, "Must index at least 500 blocks, got: " + stats.blockCount());
        assertTrue(stats.stateCount() > 5000, "Must index at least 5000 states, got: " + stats.stateCount());
        assertTrue(stats.propertyKeyCount() > 20, "Must discover at least 20 property keys, got: " + stats.propertyKeyCount());
        assertTrue(VoxelWarmupEngine.isWarm());

        // Test async idempotency
        CompletableFuture<VoxelWarmupEngine.WarmupStats> asyncFuture = VoxelWarmupEngine.startAsyncWarmup();
        assertNotNull(asyncFuture);
        VoxelWarmupEngine.WarmupStats asyncStats = asyncFuture.get();
        assertEquals(stats.blockCount(), asyncStats.blockCount());
    }
}
