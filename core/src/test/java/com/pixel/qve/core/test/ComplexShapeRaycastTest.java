package com.pixel.qve.core.test;

import com.pixel.qve.api.IVoxelGrid;


import com.pixel.qve.api.IVoxelWorld;
import com.pixel.qve.api.raycast.RayHitResult;
import com.pixel.qve.state.ShapeRegistry;
import com.pixel.qve.state.VoxelShape;
import com.pixel.qve.raycast.VoxelDDA;
import com.pixel.qve.world.VoxelFace;
import com.pixel.qve.world.VoxelSection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates sub-voxel collision precision against non-full blocks (slabs, stairs, panes).
 */
public class ComplexShapeRaycastTest {

    private static final short SLAB_ID = 10;
    private static final short STAIRS_ID = 20;
    private static final short WALL_ID = 30;

    static class ShapeTestGrid implements IVoxelGrid {
        final Map<Long, VoxelSection> sections = new HashMap<>();
        final ShapeRegistry shapeRegistry = new ShapeRegistry();

        static long key(int sx, int sy, int sz) {
            return (((long) sx & 0x3FFFFFL)) | (((long) sz & 0x3FFFFFL) << 22) | (((long) sy & 0xFFFFFL) << 44);
        }

        @Override
        public VoxelSection getSection(int sectionX, int sectionY, int sectionZ) {
            return sections.get(key(sectionX, sectionY, sectionZ));
        }

        @Override
        public ShapeRegistry getShapeRegistry() {
            return shapeRegistry;
        }

        public void setVoxel(int wx, int wy, int wz, boolean solid, short blockId) {
            int sx = wx >> 4;
            int sy = wy >> 4;
            int sz = wz >> 4;
            long k = key(sx, sy, sz);
            VoxelSection sec = sections.computeIfAbsent(k, id -> new VoxelSection());
            sec.setVoxel(wx & 15, wy & 15, wz & 15, solid, blockId);
        }
    }

    private ShapeTestGrid grid;
    private RayHitResult result;

    @BeforeEach
    void setUp() {
        grid = new ShapeTestGrid();
        result = new RayHitResult();

        // Register custom shapes
        grid.getShapeRegistry().registerShape(SLAB_ID, VoxelShape.SLAB_BOTTOM);
        grid.getShapeRegistry().registerShape(STAIRS_ID, VoxelShape.STAIRS_NORTH_BOTTOM);
        grid.getShapeRegistry().registerShape(WALL_ID, VoxelShape.FULL_CUBE);
    }

    @Test
    void testShootThroughSlabEmptyUpperHalf() {
        // Place a bottom slab at (0, 0, 0) - solid from Y 0.0 to 0.5, empty from Y 0.5 to 1.0
        grid.setVoxel(0, 0, 0, true, SLAB_ID);

        // Place a solid wall behind it at (5, 0, 0)
        grid.setVoxel(5, 0, 0, true, WALL_ID);

        // Shoot at Y = 0.75 (through the empty top half of the slab)
        boolean hit = VoxelDDA.trace(-2.0, 0.75, 0.5, 1.0, 0.0, 0.0, 20.0, grid, result);

        assertTrue(hit, "Ray should hit the wall behind the slab");
        assertEquals(5, result.blockX, "Ray should pass through slab at X=0 and hit wall at X=5");
        assertEquals(WALL_ID, result.blockId, "Hit block should be the back wall");
        assertEquals(7.0, result.distance, 1e-4, "Distance should be 7 blocks (from -2 to 5)");
    }

    @Test
    void testShootAtSlabSolidLowerHalf() {
        // Place a bottom slab at (0, 0, 0) - solid from Y 0.0 to 0.5
        grid.setVoxel(0, 0, 0, true, SLAB_ID);
        grid.setVoxel(5, 0, 0, true, WALL_ID);

        // Shoot at Y = 0.25 (hitting the solid lower half of the slab)
        boolean hit = VoxelDDA.trace(-2.0, 0.25, 0.5, 1.0, 0.0, 0.0, 20.0, grid, result);

        assertTrue(hit, "Ray should hit the solid lower half of the slab");
        assertEquals(0, result.blockX, "Ray must hit slab at X=0");
        assertEquals(SLAB_ID, result.blockId, "Hit block must be the slab");
        assertEquals(VoxelFace.WEST, result.face, "Should hit west face");
        assertEquals(2.0, result.distance, 1e-4, "Distance should be 2 blocks (from -2 to 0)");
    }

    @Test
    void testShootDownAtSlabTopSurface() {
        // Place a bottom slab at (0, 0, 0)
        grid.setVoxel(0, 0, 0, true, SLAB_ID);

        // Shoot straight down from (0.5, 3.0, 0.5)
        boolean hit = VoxelDDA.trace(0.5, 3.0, 0.5, 0.0, -1.0, 0.0, 10.0, grid, result);

        assertTrue(hit, "Downward ray must strike the top surface of the slab");
        assertEquals(0, result.blockX);
        assertEquals(0, result.blockY);
        assertEquals(0, result.blockZ);
        assertEquals(VoxelFace.UP, result.face, "Impact face must be UP");
        assertEquals(SLAB_ID, result.blockId);
        // Slab top is at Y = 0.5, ray started at Y = 3.0 -> distance = 2.5m
        assertEquals(2.5, result.distance, 1e-4, "Distance to top of slab should be exactly 2.5m");
        assertEquals(0.5, result.hitY, 1e-4, "Impact Y must be exactly 0.5 (top of lower slab)");
    }

    @Test
    void testShootThroughStairsOpening() {
        // STAIRS_NORTH_BOTTOM: base is Y[0..0.5], North step is Y[0.5..1.0] and Z[0..0.5].
        // South top is empty: Y[0.5..1.0] and Z[0.5..1.0] is open!
        grid.setVoxel(0, 0, 0, true, STAIRS_ID);
        grid.setVoxel(5, 0, 0, true, WALL_ID);

        // Ray 1: Shoot through the open South-Top notch at Z = 0.75, Y = 0.75
        boolean hitOpen = VoxelDDA.trace(-2.0, 0.75, 0.75, 1.0, 0.0, 0.0, 20.0, grid, result);
        assertTrue(hitOpen);
        assertEquals(5, result.blockX, "Ray through stair notch should pass through to the wall");
        assertEquals(WALL_ID, result.blockId);

        // Ray 2: Shoot through the solid North step at Z = 0.25, Y = 0.75
        boolean hitStep = VoxelDDA.trace(-2.0, 0.75, 0.25, 1.0, 0.0, 0.0, 20.0, grid, result);
        assertTrue(hitStep);
        assertEquals(0, result.blockX, "Ray into stair step must hit the stair at X=0");
        assertEquals(STAIRS_ID, result.blockId);
        assertEquals(2.0, result.distance, 1e-4);
    }

    @Test
    void testRayStartingInEmptyUpperHalfOfSlabDoesNotTriggerPointBlankHit() {
        // Place a bottom slab at (0, 0, 0) - solid from Y 0.0 to 0.5, empty from 0.5 to 1.0
        grid.setVoxel(0, 0, 0, true, SLAB_ID);
        grid.setVoxel(5, 0, 0, true, WALL_ID);

        // Ray starts at (0.5, 0.75, 0.5), inside the voxel coordinates (0, 0, 0), but in the AIR upper half
        boolean hit = VoxelDDA.trace(0.5, 0.75, 0.5, 1.0, 0.0, 0.0, 20.0, grid, result);

        assertTrue(hit, "Ray starting in empty upper half should not be blocked point-blank");
        assertEquals(5, result.blockX, "Ray should hit the wall at X=5");
        assertEquals(WALL_ID, result.blockId);
        assertEquals(4.5, result.distance, 1e-4);
    }
}
