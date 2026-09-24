package com.pixel.qve.core.test;

import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.state.PropertyIndexRegistry;
import com.pixel.qve.state.ShapeRegistry;
import com.pixel.qve.state.VoxelShape;
import com.pixel.qve.world.VoxelSection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class BlockIdRegistryTest {

    @Test
    @DisplayName("Verify basic registration, air reservation, and bidirectional resolution")
    void testBasicRegistration() {
        BlockIdRegistry registry = new BlockIdRegistry();
        assertEquals(BlockIdRegistry.AIR_ID, registry.getOrRegister("minecraft:air"));
        assertEquals(BlockIdRegistry.AIR_ID, registry.getOrRegister("minecraft:cave_air"));
        assertEquals(BlockIdRegistry.AIR_ID, registry.getOrRegister("minecraft:void_air"));
        assertTrue(BlockIdRegistry.isAir(BlockIdRegistry.AIR_ID));

        int stoneId = registry.getOrRegister("minecraft:stone");
        assertTrue(stoneId > 0);
        assertEquals("minecraft:stone", registry.getName(stoneId));
        assertEquals(stoneId, registry.getOrRegister("minecraft:stone"));
    }

    @Test
    @DisplayName("Verify scaling past 65,535 (e.g. 70,000 blocks) without 16-bit short overflow")
    void testMassiveModpackBlockCapacity() {
        BlockIdRegistry registry = new BlockIdRegistry();
        ShapeRegistry shapeRegistry = new ShapeRegistry();
        PropertyIndexRegistry propRegistry = registry.getStateDictionary().getPropertyRegistry();

        final int TARGET_COUNT = 70_000;
        int id66k = 0;
        String name66k = "modded_pack:block_variant_66000";

        for (int i = 1; i <= TARGET_COUNT; i++) {
            String name = (i == 66_000) ? name66k : ("modded_pack:block_variant_" + i);
            int id = registry.getOrRegister(name);
            assertEquals(i, id);

            if (i == 66_000) {
                id66k = id;
            }
        }

        // Verify registration count
        assertEquals(66_000, id66k);
        assertEquals(70_001, registry.size()); // 70,000 blocks + 1 air at index 0
        assertEquals(name66k, registry.getName(id66k));
        assertEquals(id66k, registry.getOrRegister(name66k));

        // Test VoxelSection storage with ID > 65,535
        VoxelSection section = new VoxelSection();
        section.setVoxel(5, 7, 9, true, id66k);
        assertTrue(section.isSolid(5, 7, 9));
        assertEquals(id66k, section.getBlockId(5, 7, 9));

        // Test ShapeRegistry with ID > 65,535
        shapeRegistry.registerShape(id66k, VoxelShape.SLAB_BOTTOM);
        assertFalse(shapeRegistry.isFullCube(id66k));
        assertEquals(VoxelShape.SLAB_BOTTOM, shapeRegistry.getShape(id66k));

        // Test PropertyIndexRegistry with ID > 65,535
        short keyFacing = propRegistry.getOrRegisterKey("facing");
        short valEast = propRegistry.getOrRegisterValue(keyFacing, "east");
        propRegistry.registerBlockProperties(id66k, new short[]{keyFacing, valEast});

        assertEquals(valEast, propRegistry.getPropertyValue(id66k, keyFacing));
        assertEquals("east", propRegistry.getPropertyValueName(id66k, keyFacing));
        assertEquals("[facing=east]", propRegistry.formatProperties(id66k));
    }
}
