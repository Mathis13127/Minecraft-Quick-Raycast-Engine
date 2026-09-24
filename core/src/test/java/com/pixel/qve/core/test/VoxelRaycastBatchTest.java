package com.pixel.qve.core.test;

import com.pixel.qve.api.raycast.Ray3f;
import com.pixel.qve.api.raycast.RayHitResult;
import com.pixel.qve.raycast.VoxelDDA;
import com.pixel.qve.raycast.VoxelRaycastBatch;
import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.world.VoxelChunkColumn;
import com.pixel.qve.world.VoxelSection;
import com.pixel.qve.world.cache.UnifiedVoxelCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class VoxelRaycastBatchTest {

    @Test
    @DisplayName("VoxelRaycastBatch executes batch raycasts with exact results matching single traces")
    void testRaycastBatchExecution() {
        BlockIdRegistry registry = new BlockIdRegistry();
        int stoneId = registry.getOrRegister("minecraft:stone");

        UnifiedVoxelCache cache = new UnifiedVoxelCache(registry);
        VoxelChunkColumn col = cache.getOrCreateColumn(0, 0);
        VoxelSection section = new VoxelSection();
        // Solid wall at X=5, Y=0..15, Z=0..15
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                section.setVoxel(5, y, z, true, stoneId);
            }
        }
        cache.putSection(0, 0, 0, section);

        int count = 256;
        Ray3f[] rays = new Ray3f[count];
        RayHitResult[] results = new RayHitResult[count];

        for (int i = 0; i < count; i++) {
            float y = (i % 16) + 0.5f;
            float z = (i / 16) + 0.5f;
            rays[i] = new Ray3f(0.5f, y, z, 1.0f, 0.0f, 0.0f, 64.0f);
            results[i] = new RayHitResult();
        }

        // Run batch
        VoxelRaycastBatch.INSTANCE.traceBatch(rays, results, cache);

        // Verify all 256 hit the wall at X=5
        for (int i = 0; i < count; i++) {
            assertTrue(results[i].hit, "Ray " + i + " must hit");
            assertEquals(5, results[i].blockX);
            assertEquals(stoneId, results[i].blockId);
        }

        // Test List overload
        List<Ray3f> rayList = new ArrayList<>(count);
        List<RayHitResult> resList = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rayList.add(rays[i]);
            resList.add(new RayHitResult());
        }

        VoxelRaycastBatch.INSTANCE.traceBatch(rayList, resList, cache);
        for (int i = 0; i < count; i++) {
            assertTrue(resList.get(i).hit);
            assertEquals(5, resList.get(i).blockX);
        }
    }
}
