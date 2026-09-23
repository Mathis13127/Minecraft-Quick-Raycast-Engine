package com.pixel.raycast.neoforge.test;

import com.pixel.raycast.core.api.IVoxelGrid;
import com.pixel.raycast.core.cache.VoxelCache;
import com.pixel.raycast.core.cache.VoxelChunkColumn;
import com.pixel.raycast.core.voxel.VoxelFace;
import com.pixel.raycast.core.voxel.VoxelSection;
import com.pixel.raycast.neoforge.api.VoxelRaycastAPI;
import com.pixel.raycast.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.raycast.neoforge.bridge.MinecraftVoxelGrid;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class VoxelRaycastAPITest {

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
            System.err.println("Bootstrap error: " + e);
        }
    }

    @Test
    @DisplayName("VoxelFace to Direction conversion maps all 6 cardinal directions faithfully")
    void testDirectionMapping() {
        assertEquals(Direction.DOWN, VoxelRaycastAPI.toDirection(VoxelFace.DOWN));
        assertEquals(Direction.UP, VoxelRaycastAPI.toDirection(VoxelFace.UP));
        assertEquals(Direction.NORTH, VoxelRaycastAPI.toDirection(VoxelFace.NORTH));
        assertEquals(Direction.SOUTH, VoxelRaycastAPI.toDirection(VoxelFace.SOUTH));
        assertEquals(Direction.WEST, VoxelRaycastAPI.toDirection(VoxelFace.WEST));
        assertEquals(Direction.EAST, VoxelRaycastAPI.toDirection(VoxelFace.EAST));
        assertEquals(Direction.UP, VoxelRaycastAPI.toDirection(VoxelFace.NONE));
    }

    @Test
    @DisplayName("Mock grid line-of-sight and raycast intersection test")
    void testMockGridRaycast() {
        VoxelCache cache = new VoxelCache(MinecraftVoxelBridge.getBlockRegistry(), MinecraftVoxelBridge.getShapeRegistry(), null, -4, 20);
        VoxelChunkColumn column = cache.getOrCreateColumn(0, 0);
        short stoneId = MinecraftVoxelBridge.getBlockId(Blocks.STONE.defaultBlockState());
        cache.setVoxel(5, 69, 5, true, stoneId);

        // Verify ray striking the stone block
        com.pixel.raycast.core.api.RayHitResult result = new com.pixel.raycast.core.api.RayHitResult();
        boolean hit = com.pixel.raycast.core.traversal.VoxelDDA.trace(
                0.5, 69.5, 5.5,
                1.0, 0.0, 0.0,
                10.0,
                cache,
                result
        );

        assertTrue(hit, "Ray from X=0.5 along +X should strike stone at X=5");
        assertEquals(5, result.blockX);
        assertEquals(69, result.blockY);
        assertEquals(5, result.blockZ);
        assertEquals(5.0, result.hitX, 1e-4);
        assertEquals(VoxelFace.WEST, result.face);
    }
}
