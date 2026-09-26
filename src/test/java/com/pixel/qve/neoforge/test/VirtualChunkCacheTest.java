package com.pixel.qve.neoforge.test;

import com.pixel.qve.neoforge.api.terrain.VirtualChunk;
import com.pixel.qve.neoforge.terrain.TerrainStage;
import com.pixel.qve.neoforge.terrain.VirtualChunkCache;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

public class VirtualChunkCacheTest {

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
            net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Exception e) {
            System.err.println("Bootstrap warning: " + e);
        }
    }

    @BeforeEach
    void setUp() {
        VirtualChunkCache.clear();
    }

    @Test
    @DisplayName("Verify VirtualChunkCache stores and retrieves virtual chunks with stage distinction")
    void testBasicPutAndGet() {
        assertEquals(0, VirtualChunkCache.size());

        VirtualChunk mockChunkSurface = Mockito.mock(VirtualChunk.class);
        VirtualChunk mockChunkNoise = Mockito.mock(VirtualChunk.class);

        VirtualChunkCache.put(0, 100, TerrainStage.SURFACE, mockChunkSurface);
        VirtualChunkCache.put(0, 100, TerrainStage.NOISE, mockChunkNoise);

        assertEquals(2, VirtualChunkCache.size());
        assertTrue(VirtualChunkCache.contains(0, 100, TerrainStage.SURFACE));
        assertTrue(VirtualChunkCache.contains(0, 100, TerrainStage.NOISE));
        assertFalse(VirtualChunkCache.contains(0, 100, TerrainStage.BIOMES));

        assertSame(mockChunkSurface, VirtualChunkCache.get(0, 100, TerrainStage.SURFACE));
        assertSame(mockChunkNoise, VirtualChunkCache.get(0, 100, TerrainStage.NOISE));
        assertNull(VirtualChunkCache.get(0, 100, TerrainStage.BIOMES));
    }

    @Test
    @DisplayName("Verify LRU eviction triggers when cache exceeds MAX_ENTRIES")
    void testLruEviction() {
        int max = VirtualChunkCache.MAX_ENTRIES;

        // Insert max entries (0..63)
        for (int i = 0; i < max; i++) {
            VirtualChunk chunk = Mockito.mock(VirtualChunk.class);
            VirtualChunkCache.put(i, 0, TerrainStage.SURFACE, chunk);
        }
        assertEquals(max, VirtualChunkCache.size());
        assertTrue(VirtualChunkCache.contains(0, 0, TerrainStage.SURFACE));

        // Insert entry 64 -> eldest entry (0, 0) should be evicted
        VirtualChunk overflow = Mockito.mock(VirtualChunk.class);
        VirtualChunkCache.put(max, 0, TerrainStage.SURFACE, overflow);

        assertEquals(max, VirtualChunkCache.size());
        assertFalse(VirtualChunkCache.contains(0, 0, TerrainStage.SURFACE), "Oldest entry (0, 0) must be evicted");
        assertTrue(VirtualChunkCache.contains(max, 0, TerrainStage.SURFACE), "New entry must be present");
    }

    @Test
    @DisplayName("Verify 64-bit key packing uniquely separates coordinates and stages")
    void testKeyPacking() {
        long k1 = VirtualChunkCache.packKey(0, 10, TerrainStage.SURFACE);
        long k2 = VirtualChunkCache.packKey(0, 10, TerrainStage.NOISE);
        long k3 = VirtualChunkCache.packKey(10, 0, TerrainStage.SURFACE);
        long k4 = VirtualChunkCache.packKey(-5, -10, TerrainStage.SURFACE);

        assertNotEquals(k1, k2);
        assertNotEquals(k1, k3);
        assertNotEquals(k1, k4);
        assertNotEquals(k2, k3);
    }
}
