package com.pixel.qve.neoforge.test;

import com.pixel.qve.world.VoxelChunkColumn;
import com.pixel.qve.state.VoxelShape;
import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.world.Heightmap2D;
import com.pixel.qve.world.VoxelSection;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.neoforge.bridge.IRaycastChunkSection;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MinecraftVoxelBridgeTest {

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
    @DisplayName("BlockState resolution correctly extracts IDs and registers sub-voxel shapes")
    void testBlockStateToIdAndShapeResolution() {
        // Air should map strictly to ID 0
        BlockState airState = Blocks.AIR.defaultBlockState();
        assertEquals(BlockIdRegistry.AIR_ID, MinecraftVoxelBridge.getBlockId(airState));

        // Stone should be a solid block and full cube
        BlockState stoneState = Blocks.STONE.defaultBlockState();
        int stoneId = MinecraftVoxelBridge.getBlockId(stoneState);
        assertNotEquals(BlockIdRegistry.AIR_ID, stoneId);
        assertEquals(VoxelShape.FULL_CUBE, MinecraftVoxelBridge.getShapeRegistry().getShape(stoneId));

        // Bottom Slab
        BlockState bottomSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        int bottomSlabId = MinecraftVoxelBridge.getBlockId(bottomSlab);
        assertNotEquals(BlockIdRegistry.AIR_ID, bottomSlabId);
        assertEquals(VoxelShape.SLAB_BOTTOM, MinecraftVoxelBridge.getShapeRegistry().getShape(bottomSlabId));

        // Top Slab
        BlockState topSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
        int topSlabId = MinecraftVoxelBridge.getBlockId(topSlab);
        assertNotEquals(bottomSlabId, topSlabId);
        assertEquals(VoxelShape.SLAB_TOP, MinecraftVoxelBridge.getShapeRegistry().getShape(topSlabId));

        // Double Slab should be full cube
        BlockState doubleSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE);
        int doubleSlabId = MinecraftVoxelBridge.getBlockId(doubleSlab);
        assertEquals(VoxelShape.FULL_CUBE, MinecraftVoxelBridge.getShapeRegistry().getShape(doubleSlabId));

        // Stairs North Bottom
        BlockState stairsNorthBottom = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.NORTH)
                .setValue(StairBlock.HALF, Half.BOTTOM);
        int stairsId = MinecraftVoxelBridge.getBlockId(stairsNorthBottom);
        assertEquals(VoxelShape.STAIRS_NORTH_BOTTOM, MinecraftVoxelBridge.getShapeRegistry().getShape(stairsId));

        // Iron Bars
        BlockState ironBars = Blocks.IRON_BARS.defaultBlockState();
        int barsId = MinecraftVoxelBridge.getBlockId(ironBars);
        VoxelShape barsShape = MinecraftVoxelBridge.getShapeRegistry().getShape(barsId);
        assertNotNull(barsShape);
        assertFalse(barsShape.isFullCube());

        // Trapdoor Bottom
        BlockState trapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.HALF, Half.BOTTOM);
        int trapdoorId = MinecraftVoxelBridge.getBlockId(trapdoor);
        assertEquals(VoxelShape.TRAPDOOR_BOTTOM, MinecraftVoxelBridge.getShapeRegistry().getShape(trapdoorId));
    }

    @Test
    @DisplayName("Dirty Tracking: onBlockStateChanged directly mutates VoxelSection and VoxelChunkColumn")
    void testDirtyTrackingBridge() {
        VoxelChunkColumn column = new VoxelChunkColumn(0, 0, -4, 20);
        VoxelSection section = new VoxelSection();
        int sectionY = 4;
        column.setSection(sectionY, section);

        // Simulated dummy LevelChunkSection implementing IRaycastChunkSection
        class DummySection implements IRaycastChunkSection {
            private VoxelSection vs = section;
            private VoxelChunkColumn vc = column;
            private int sy = sectionY;

            @Override public VoxelSection raycast$getVoxelSection() { return vs; }
            @Override public void raycast$setVoxelSection(VoxelSection s) { this.vs = s; }
            @Override public VoxelChunkColumn raycast$getVoxelColumn() { return vc; }
            @Override public void raycast$setVoxelColumn(VoxelChunkColumn c, int y) { this.vc = c; this.sy = y; }
            @Override public int raycast$getSectionY() { return sy; }
        }

        DummySection dummy = new DummySection();

        // Initially empty
        assertFalse(section.isSolid(3, 7, 5));
        assertEquals(0, section.getBlockId(3, 7, 5));

        // Place Stone block at (3, 7, 5) -> World Y = (4 << 4) | 7 = 71
        BlockState stone = Blocks.STONE.defaultBlockState();
        MinecraftVoxelBridge.onBlockStateChanged(dummy, 3, 7, 5, stone);

        assertTrue(section.isSolid(3, 7, 5));
        int stoneId = MinecraftVoxelBridge.getBlockId(stone);
        assertEquals(stoneId, section.getBlockId(3, 7, 5));
        assertEquals((short) 71, column.getHeightmap().getHeight(3, 5));
        assertEquals((short) 71, column.getHeightmap().getHighestY());

        // Destroy the block (replace with air)
        BlockState air = Blocks.AIR.defaultBlockState();
        MinecraftVoxelBridge.onBlockStateChanged(dummy, 3, 7, 5, air);

        assertFalse(section.isSolid(3, 7, 5));
        assertEquals(BlockIdRegistry.AIR_ID, section.getBlockId(3, 7, 5));
        assertEquals(Heightmap2D.VOID_Y, column.getHeightmap().getHeight(3, 5));
    }

    @Test
    @DisplayName("Verify plant blocks (short grass, poppy, dandelion) resolve CROSS_PLANT trait")
    void testPlantTraitResolution() {
        int grassPlantId = MinecraftVoxelBridge.getBlockId(Blocks.SHORT_GRASS.defaultBlockState());
        assertTrue(MinecraftVoxelBridge.getTraitRegistry().isPassThrough(grassPlantId));
        assertTrue(MinecraftVoxelBridge.getTraitRegistry().isCrossPlant(grassPlantId));
        assertFalse(MinecraftVoxelBridge.getTraitRegistry().isTerrainSolid(grassPlantId));
        assertFalse(MinecraftVoxelBridge.getTraitRegistry().isSurfaceMeshable(grassPlantId));

        int poppyId = MinecraftVoxelBridge.getBlockId(Blocks.POPPY.defaultBlockState());
        assertTrue(MinecraftVoxelBridge.getTraitRegistry().isPassThrough(poppyId));
        assertTrue(MinecraftVoxelBridge.getTraitRegistry().isCrossPlant(poppyId));
        assertFalse(MinecraftVoxelBridge.getTraitRegistry().isTerrainSolid(poppyId));

        int dandelionId = MinecraftVoxelBridge.getBlockId(Blocks.DANDELION.defaultBlockState());
        assertTrue(MinecraftVoxelBridge.getTraitRegistry().isPassThrough(dandelionId));
        assertTrue(MinecraftVoxelBridge.getTraitRegistry().isCrossPlant(dandelionId));
    }
}
