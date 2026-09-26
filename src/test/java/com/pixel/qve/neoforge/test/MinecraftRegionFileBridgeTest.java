package com.pixel.qve.neoforge.test;

import com.pixel.qve.neoforge.bridge.writer.MinecraftRegionFileBridge;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MinecraftRegionFileBridge: Concurrency & Eviction Hardening")
public class MinecraftRegionFileBridgeTest {

    @BeforeAll
    static void initMinecraft() {
        try {
            net.neoforged.fml.loading.LoadingModList.of(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    java.util.Map.of()
            );
            SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
            Bootstrap.bootStrap();
        } catch (Exception ignored) {
        }
    }

    @Test
    @DisplayName("evictAndFlushRegion handles null ServerLevel safely")
    void testNullLevelGraceful() {
        assertFalse(MinecraftRegionFileBridge.evictAndFlushRegion(null, 0, 0));
        assertFalse(MinecraftRegionFileBridge.evictAndFlushRegions(null, List.of()));
        assertFalse(MinecraftRegionFileBridge.evictAndFlushRegions(null, null));
    }

    @Test
    @DisplayName("evictAndFlushRegions handles empty region list safely")
    void testEmptyRegionList() {
        ServerLevel mockLevel = Mockito.mock(ServerLevel.class);
        assertFalse(MinecraftRegionFileBridge.evictAndFlushRegions(mockLevel, List.of()));
        assertFalse(MinecraftRegionFileBridge.evictAndFlushRegions(mockLevel, null));
    }

    @Test
    @DisplayName("Concurrent eviction calls do not collide or throw race conditions")
    void testConcurrentEvictionsThreadSafety() throws Exception {
        ServerLevel mockLevel = Mockito.mock(ServerLevel.class);
        ServerChunkCache mockScc = Mockito.mock(ServerChunkCache.class);
        Mockito.when(mockLevel.getChunkSource()).thenReturn(mockScc);
        // chunkMap is a public field which defaults to null on mockScc

        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            final int regionIndex = i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                long rKey = ChunkPos.asLong(regionIndex, regionIndex);
                return MinecraftRegionFileBridge.evictAndFlushRegions(mockLevel, List.of(rKey));
            }, executor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));

        for (CompletableFuture<Boolean> f : futures) {
            assertFalse(f.join(), "Expected false on mock with null chunkMap");
        }
    }
}
