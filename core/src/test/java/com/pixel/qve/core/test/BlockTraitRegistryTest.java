package com.pixel.qve.core.test;

import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.state.BlockTraitRegistry;
import com.pixel.qve.state.BlockTraits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockTraitRegistryTest {

    @Test
    @DisplayName("Verify Air ID 0 is initialized as invisible and pass-through")
    void testAirInitialization() {
        BlockTraitRegistry registry = new BlockTraitRegistry();
        assertTrue(registry.isInvisible(BlockIdRegistry.AIR_ID));
        assertTrue(registry.isPassThrough(BlockIdRegistry.AIR_ID));
        assertFalse(registry.isTerrainSolid(BlockIdRegistry.AIR_ID));
    }

    @Test
    @DisplayName("Verify custom block traits classification and fast-path methods")
    void testCustomBlockTraits() {
        BlockTraitRegistry registry = new BlockTraitRegistry();

        int stoneId = 1;
        registry.setTraits(stoneId, BlockTraits.TERRAIN_SOLID);
        assertTrue(registry.isTerrainSolid(stoneId));
        assertTrue(registry.isSurfaceMeshable(stoneId));
        assertFalse(registry.isPassThrough(stoneId));
        assertFalse(registry.isPartialShape(stoneId));

        int flowerId = 2;
        registry.setTraits(flowerId, BlockTraits.PASS_THROUGH);
        assertFalse(registry.isTerrainSolid(flowerId));
        assertFalse(registry.isSurfaceMeshable(flowerId));
        assertTrue(registry.isPassThrough(flowerId));
        assertFalse(registry.isPartialShape(flowerId));

        int slabId = 3;
        registry.setTraits(slabId, BlockTraits.PARTIAL_SHAPE);
        assertFalse(registry.isTerrainSolid(slabId));
        assertTrue(registry.isSurfaceMeshable(slabId));
        assertFalse(registry.isPassThrough(slabId));
        assertTrue(registry.isPartialShape(slabId));

        int waterId = 4;
        registry.setTraits(waterId, (byte) (BlockTraits.FLUID | BlockTraits.TRANSLUCENT | BlockTraits.PASS_THROUGH));
        assertFalse(registry.isTerrainSolid(waterId));
        assertTrue(registry.isSurfaceMeshable(waterId));
        assertTrue(registry.isFluid(waterId));
        assertTrue(registry.isTranslucent(waterId));
        assertTrue(registry.isPassThrough(waterId));

        int leavesId = 5;
        registry.setTraits(leavesId, (byte) (BlockTraits.TERRAIN_SOLID | BlockTraits.FOLIAGE));
        assertTrue(registry.isTerrainSolid(leavesId));
        assertTrue(registry.isSurfaceMeshable(leavesId));

        int grassPlantId = 6;
        registry.setTraits(grassPlantId, (byte) (BlockTraits.PASS_THROUGH | BlockTraits.CROSS_PLANT));
        assertFalse(registry.isTerrainSolid(grassPlantId));
        assertFalse(registry.isSurfaceMeshable(grassPlantId));
        assertTrue(registry.isPassThrough(grassPlantId));
        assertTrue(registry.isCrossPlant(grassPlantId));
    }

    @Test
    @DisplayName("Verify dynamic capacity expansion beyond initial 256 entries")
    void testCapacityExpansion() {
        BlockTraitRegistry registry = new BlockTraitRegistry();
        int largeId = 5000;
        registry.setTraits(largeId, BlockTraits.PARTIAL_SHAPE);

        assertTrue(registry.isPartialShape(largeId));
        assertFalse(registry.isTerrainSolid(largeId));
    }

    @Test
    @DisplayName("Verify FLUID blocks automatically strip INVISIBLE flag and isInvisible returns false")
    void testFluidCannotBeInvisible() {
        BlockTraitRegistry registry = new BlockTraitRegistry();
        int waterId = 100;
        // Even if explicitly passed with INVISIBLE bit set
        registry.setTraits(waterId, (byte) (BlockTraits.FLUID | BlockTraits.INVISIBLE | BlockTraits.TRANSLUCENT));

        assertTrue(registry.isFluid(waterId), "Block must be FLUID");
        assertFalse(registry.isInvisible(waterId), "FLUID block must NEVER be INVISIBLE");
        assertEquals(0, registry.getTraits(waterId) & BlockTraits.INVISIBLE, "INVISIBLE bit must be stripped");
    }
}
