package com.pixel.qve.neoforge.test;

import com.pixel.qve.neoforge.api.terrain.VirtualChunk;
import com.pixel.qve.neoforge.api.terrain.VoxelTerrainAPI;
import com.pixel.qve.neoforge.terrain.DefaultVirtualChunk;
import com.pixel.qve.neoforge.terrain.TerrainStage;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the contracts and behavior of the VirtualChunk API and DefaultVirtualChunk wrapper.
 */
public class VirtualChunkTest {

    @Test
    @DisplayName("Verify DefaultVirtualChunk coordinates and block queries")
    void testVirtualChunkContracts() {
        LevelHeightAccessor mockHeight = Mockito.mock(LevelHeightAccessor.class);
        Mockito.when(mockHeight.getMinBuildHeight()).thenReturn(-64);
        Mockito.when(mockHeight.getHeight()).thenReturn(384);
        Mockito.when(mockHeight.getMinSection()).thenReturn(-4);
        Mockito.when(mockHeight.getSectionsCount()).thenReturn(24);
        Mockito.when(mockHeight.getMaxSection()).thenReturn(19);

        Registry mockBiomeRegistry = Mockito.mock(Registry.class);

        ProtoChunk proto = new ProtoChunk(new ChunkPos(12, -8), UpgradeData.EMPTY, mockHeight, mockBiomeRegistry, null);
        VirtualChunk virtualChunk = new DefaultVirtualChunk(12, -8, TerrainStage.SURFACE, proto);

        assertEquals(12, virtualChunk.getSourceChunkX());
        assertEquals(-8, virtualChunk.getSourceChunkZ());
        assertEquals(TerrainStage.SURFACE, virtualChunk.getStage());
        assertSame(proto, virtualChunk.getProtoChunk());
        assertNotNull(virtualChunk.getSections());

        // Default empty sections must return air
        assertEquals(Blocks.AIR.defaultBlockState(), virtualChunk.getBlockState(0, 64, 0));
        assertEquals(Blocks.AIR.defaultBlockState(), virtualChunk.getBlockState(15, -100, 15)); // Out of bounds
    }

    @Test
    @DisplayName("Verify VoxelTerrainAPI parameter validations")
    void testVoxelTerrainApiValidation() {
        assertThrows(NullPointerException.class, () -> VoxelTerrainAPI.generateVirtualChunkAsync(null, 0, 0, TerrainStage.SURFACE));
        assertThrows(NullPointerException.class, () -> VoxelTerrainAPI.generateVirtualChunkAsync(Mockito.mock(net.minecraft.server.level.ServerLevel.class), 0, 0, null));
    }
}
