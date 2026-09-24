package com.pixel.qve.core.test;

import com.pixel.qve.api.IVoxelGrid;
import com.pixel.qve.api.raycast.RayHitResult;
import com.pixel.qve.raycast.VoxelDDA;
import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.world.Heightmap2D;
import com.pixel.qve.world.RegionHeightmap2D;
import com.pixel.qve.world.VoxelChunkColumn;
import com.pixel.qve.world.VoxelSection;
import com.pixel.qve.world.cache.UnifiedVoxelCache;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class RegionHeightmapRaycastTest {

    @Test
    public void testRegionHeightmapScalarAndArray() {
        RegionHeightmap2D rHm = new RegionHeightmap2D();
        assertEquals(Heightmap2D.VOID_Y, rHm.getRegionMaxY());
        assertTrue(rHm.isAboveRegion(0.0));

        rHm.updateMax(0, 0, (short) 64);
        assertEquals(64, rHm.getRegionMaxY());
        assertEquals(64, rHm.getChunkMaxY(0, 0));
        assertEquals(Heightmap2D.VOID_Y, rHm.getChunkMaxY(1, 0));

        assertTrue(rHm.isAboveRegion(65.0));
        assertFalse(rHm.isAboveRegion(64.0));
        assertFalse(rHm.isAboveRegion(63.5));

        rHm.updateMax(15, 15, (short) 120);
        assertEquals(120, rHm.getRegionMaxY());
        assertEquals(120, rHm.getChunkMaxY(15, 15));
        assertTrue(rHm.isAboveRegion(121.0));
        assertFalse(rHm.isAboveRegion(120.0));
    }

    @Test
    public void testMacroscopicSkySkipOverMultipleRegions() {
        BlockIdRegistry registry = new BlockIdRegistry();
        UnifiedVoxelCache cache = new UnifiedVoxelCache(registry);

        // Populate a small terrain in region (0, 0) up to Y=70
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                cache.setVoxel(x, 70, z, true, 1);
            }
        }

        // Ray starts at (8, 150, 8) heading east for 50,000 blocks (crossing ~100 regions)
        RayHitResult result = new RayHitResult();
        long startNano = System.nanoTime();
        boolean hit = VoxelDDA.trace(8.0, 150.0, 8.0, 1.0, 0.0, 0.0, 50000.0, cache, result);
        long elapsedNano = System.nanoTime() - startNano;

        assertFalse(hit, "Ray at Y=150 should never hit terrain at Y=70");
        // Must complete in under 1 millisecond (typically < 20 microseconds)
        assertTrue(elapsedNano < 5_000_000L, "Raycast took " + (elapsedNano / 1_000_000.0) + "ms, expected < 5ms");
    }

    @Test
    public void testGroundRayHitsCorrectVoxelWhenCrossingRegions() {
        BlockIdRegistry registry = new BlockIdRegistry();
        UnifiedVoxelCache cache = new UnifiedVoxelCache(registry);

        // Put a solid target wall in region (1, 0) at X=600, Y=50, Z=0..15
        for (int y = 40; y <= 60; y++) {
            for (int z = 0; z < 16; z++) {
                cache.setVoxel(600, y, z, true, 2);
            }
        }

        // Fire ray from region (0, 0) at (10, 50, 8) aiming straight at (600, 50, 8)
        RayHitResult result = new RayHitResult();
        boolean hit = VoxelDDA.trace(10.0, 50.5, 8.5, 1.0, 0.0, 0.0, 1000.0, cache, result);

        assertTrue(hit, "Ray must hit target wall at X=600");
        assertEquals(600, result.blockX);
        assertEquals(50, result.blockY);
        assertEquals(8, result.blockZ);
        assertEquals(2, result.blockId);
    }
}
