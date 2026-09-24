package com.pixel.raycast.core.test;

import com.pixel.raycast.core.voxel.BlockIdRegistry;
import com.pixel.raycast.core.voxel.BlockStatePaletteUnpacker;
import com.pixel.raycast.core.voxel.VoxelSection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class BlockStatePaletteUnpackerTest {

    @Test
    @DisplayName("Single palette entry: non-air populates full solidCount and is not empty")
    void testSinglePaletteSolidEntry() {
        short stoneId = 12;
        short[] palette = new short[]{ stoneId };

        // Test unpackDirect
        VoxelSection direct = BlockStatePaletteUnpacker.unpackDirect(palette, null);
        assertFalse(direct.isEmpty(), "Direct unpacked section must not be empty");
        assertTrue(direct.isFull(), "Homogeneous stone section must be full");
        assertEquals(4096, direct.getSolidCount());
        for (int i = 0; i < 4096; i++) {
            assertEquals(stoneId, direct.getBlockIds()[i]);
        }

        // Test unpackInto
        VoxelSection target = new VoxelSection();
        BlockStatePaletteUnpacker.unpackInto(palette, null, target);
        assertFalse(target.isEmpty(), "Target unpacked section must not be empty");
        assertTrue(target.isFull(), "Target homogeneous stone section must be full");
        assertEquals(4096, target.getSolidCount());
    }

    @Test
    @DisplayName("Single palette entry: air produces empty section")
    void testSinglePaletteAirEntry() {
        short[] palette = new short[]{ BlockIdRegistry.AIR_ID };

        VoxelSection direct = BlockStatePaletteUnpacker.unpackDirect(palette, null);
        assertTrue(direct.isEmpty());
        assertEquals(0, direct.getSolidCount());

        VoxelSection target = new VoxelSection();
        BlockStatePaletteUnpacker.unpackInto(palette, null, target);
        assertTrue(target.isEmpty());
        assertEquals(0, target.getSolidCount());
    }

    @Test
    @DisplayName("Multi-entry palette: accurately computes solidCount and occupancy")
    void testMultiEntryPalette() {
        short stoneId = 1;
        short airId = BlockIdRegistry.AIR_ID;
        short[] palette = new short[]{ airId, stoneId };

        // 4096 entries, bitsPerBlock = 4 (min in Minecraft). 16 entries per long.
        // 4096 / 16 = 256 longs.
        long[] data = new long[256];

        // Make every second entry stone (index 1 in palette): 0x1111111111111111L
        // Alternating entries: nibble 0 is stone, nibble 1 is air -> 0x0101010101010101L (8 stones per long)
        long pattern = 0x0101010101010101L;
        for (int i = 0; i < data.length; i++) {
            data[i] = pattern;
        }

        VoxelSection section = BlockStatePaletteUnpacker.unpackDirect(palette, data);
        assertEquals(256 * 8, section.getSolidCount(), "Expected exactly 2048 solid blocks");
        assertFalse(section.isEmpty());
        assertFalse(section.isFull());

        VoxelSection target = new VoxelSection();
        BlockStatePaletteUnpacker.unpackInto(palette, data, target);
        assertEquals(256 * 8, target.getSolidCount(), "Expected exactly 2048 solid blocks in target");
        assertFalse(target.isEmpty());
        assertFalse(target.isFull());
    }
}
