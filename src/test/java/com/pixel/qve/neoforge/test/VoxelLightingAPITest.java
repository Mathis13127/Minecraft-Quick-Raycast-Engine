package com.pixel.qve.neoforge.test;

import com.pixel.qve.neoforge.api.lighting.VoxelChunkLighting;
import com.pixel.qve.neoforge.api.lighting.VoxelLightingAPI;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.neoforge.lighting.QveLightingEngine;
import com.pixel.qve.neoforge.lighting.UnifiedLightChunkGetter;
import com.pixel.qve.world.VoxelChunkColumn;
import com.pixel.qve.world.VoxelSection;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public class VoxelLightingAPITest {

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
        } catch (Exception ignored) {
        }
    }

    @Test
    @DisplayName("Verify ClientboundLightUpdatePacket creation from VoxelChunkLighting and network roundtrip")
    void testClientboundLightUpdatePacketCreation() {
        VoxelChunkColumn column = new VoxelChunkColumn(10, 20, -4, 20);
        VoxelChunkLighting lighting = VoxelLightingAPI.computeLighting(null, column);
        assertNotNull(lighting);

        net.minecraft.world.level.LevelHeightAccessor heightAccessor = new net.minecraft.world.level.LevelHeightAccessor() {
            @Override public int getHeight() { return 384; }
            @Override public int getMinBuildHeight() { return -64; }
        };

        net.minecraft.world.level.ChunkPos cpos = new net.minecraft.world.level.ChunkPos(10, 20);
        net.minecraft.network.protocol.game.ClientboundLightUpdatePacket packet =
                com.pixel.qve.neoforge.lighting.QveLightPacketHelper.createUpdatePacket(cpos, heightAccessor, lighting);

        assertNotNull(packet);
        assertEquals(10, packet.getX());
        assertEquals(20, packet.getZ());
        assertNotNull(packet.getLightData());

        // Test stream codec serialization & deserialization roundtrip
        net.minecraft.network.FriendlyByteBuf buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        net.minecraft.network.protocol.game.ClientboundLightUpdatePacket.STREAM_CODEC.encode(buffer, packet);
        net.minecraft.network.protocol.game.ClientboundLightUpdatePacket decoded =
                net.minecraft.network.protocol.game.ClientboundLightUpdatePacket.STREAM_CODEC.decode(buffer);

        assertEquals(packet.getX(), decoded.getX());
        assertEquals(packet.getZ(), decoded.getZ());
        assertEquals(packet.getLightData().getSkyYMask(), decoded.getLightData().getSkyYMask());
        assertEquals(packet.getLightData().getEmptyBlockYMask(), decoded.getLightData().getEmptyBlockYMask());
    }

    @Test
    @DisplayName("Verify corrupted light packet generation for DARK, BRIGHT, and RANDOM modes")
    void testCorruptedLightPacketGeneration() {
        net.minecraft.world.level.LevelHeightAccessor heightAccessor = new net.minecraft.world.level.LevelHeightAccessor() {
            @Override public int getHeight() { return 384; }
            @Override public int getMinBuildHeight() { return -64; }
        };
        net.minecraft.world.level.ChunkPos cpos = new net.minecraft.world.level.ChunkPos(3, 7);

        // 1. DARK mode
        net.minecraft.network.protocol.game.ClientboundLightUpdatePacket darkPacket =
                com.pixel.qve.neoforge.lighting.QveLightPacketHelper.createCorruptedPacket(
                        cpos, heightAccessor, com.pixel.qve.neoforge.lighting.QveLightPacketHelper.CorruptMode.DARK);
        assertNotNull(darkPacket);
        assertEquals(3, darkPacket.getX());
        assertEquals(7, darkPacket.getZ());
        assertTrue(darkPacket.getLightData().getSkyUpdates().isEmpty(), "DARK mode must not send raw byte buffers");
        assertFalse(darkPacket.getLightData().getEmptySkyYMask().isEmpty(), "DARK mode must set empty sky mask");
        assertFalse(darkPacket.getLightData().getEmptyBlockYMask().isEmpty(), "DARK mode must set empty block mask");

        // 2. BRIGHT mode
        net.minecraft.network.protocol.game.ClientboundLightUpdatePacket brightPacket =
                com.pixel.qve.neoforge.lighting.QveLightPacketHelper.createCorruptedPacket(
                        cpos, heightAccessor, com.pixel.qve.neoforge.lighting.QveLightPacketHelper.CorruptMode.BRIGHT);
        assertNotNull(brightPacket);
        assertFalse(brightPacket.getLightData().getSkyUpdates().isEmpty(), "BRIGHT mode must send full light buffers");
        assertFalse(brightPacket.getLightData().getBlockUpdates().isEmpty(), "BRIGHT mode must send full light buffers");

        // 3. RANDOM mode
        net.minecraft.network.protocol.game.ClientboundLightUpdatePacket randomPacket =
                com.pixel.qve.neoforge.lighting.QveLightPacketHelper.createCorruptedPacket(
                        cpos, heightAccessor, com.pixel.qve.neoforge.lighting.QveLightPacketHelper.CorruptMode.RANDOM);
        assertNotNull(randomPacket);
        assertFalse(randomPacket.getLightData().getSkyUpdates().isEmpty(), "RANDOM mode must send noise buffers");

        // Verify mode resolution
        assertEquals(com.pixel.qve.neoforge.lighting.QveLightPacketHelper.CorruptMode.DARK,
                com.pixel.qve.neoforge.lighting.QveLightPacketHelper.CorruptMode.fromString("dark"));
        assertEquals(com.pixel.qve.neoforge.lighting.QveLightPacketHelper.CorruptMode.DARK,
                com.pixel.qve.neoforge.lighting.QveLightPacketHelper.CorruptMode.fromString("black"));
        assertEquals(com.pixel.qve.neoforge.lighting.QveLightPacketHelper.CorruptMode.DARK,
                com.pixel.qve.neoforge.lighting.QveLightPacketHelper.CorruptMode.fromString("0"));
        assertEquals(com.pixel.qve.neoforge.lighting.QveLightPacketHelper.CorruptMode.BRIGHT,
                com.pixel.qve.neoforge.lighting.QveLightPacketHelper.CorruptMode.fromString("bright"));
        assertEquals(com.pixel.qve.neoforge.lighting.QveLightPacketHelper.CorruptMode.BRIGHT,
                com.pixel.qve.neoforge.lighting.QveLightPacketHelper.CorruptMode.fromString("full"));
        assertEquals(com.pixel.qve.neoforge.lighting.QveLightPacketHelper.CorruptMode.RANDOM,
                com.pixel.qve.neoforge.lighting.QveLightPacketHelper.CorruptMode.fromString("random"));
        assertEquals(com.pixel.qve.neoforge.lighting.QveLightPacketHelper.CorruptMode.RANDOM,
                com.pixel.qve.neoforge.lighting.QveLightPacketHelper.CorruptMode.fromString("noise"));
        assertNull(com.pixel.qve.neoforge.lighting.QveLightPacketHelper.CorruptMode.fromString("unknown_mode"));
    }

    @Test
    @DisplayName("Verify sky light is 15 in open air and drops to 0 beneath a solid stone roof")
    void testSkyLightPropagationAndOcclusion() {
        VoxelChunkColumn column = new VoxelChunkColumn(0, 0, -4, 20);

        // Section 4 corresponds to Y [64..79]
        VoxelSection section = new VoxelSection();
        int stoneId = MinecraftVoxelBridge.getBlockId(Blocks.STONE.defaultBlockState());

        // Fill an opaque roof at local Y = 0 (world Y = 64) across the entire chunk
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                section.setVoxel(x, 0, z, true, stoneId);
                column.getHeightmap().setHeight(x, z, (short) 64);
            }
        }
        column.setSection(4, section);

        VoxelChunkLighting lighting = VoxelLightingAPI.computeLighting(null, column);
        assertNotNull(lighting, "Lighting result must not be null");

        // 1. Above the roof: world Y = 70 must have full sky light (15)
        int skyAbove = lighting.getSkyLight(8, 70, 8);
        assertEquals(15, skyAbove, "Sky light above open stone roof must be 15");

        // 2. Directly above the roof: world Y = 65 must have full sky light (15)
        assertEquals(15, lighting.getSkyLight(8, 65, 8), "Sky light directly above stone roof must be 15");

        // 3. Under an open overhang (8 blocks away from open air border): horizontal diffusion gives exactly 15 - 8 = 7!
        int skyUnderOverhang = lighting.getSkyLight(8, 50, 8);
        assertEquals(7, skyUnderOverhang, "Horizontal daylight diffusion from open border at distance 8 must be exactly 15 - 8 = 7");

        // 4. Fully enclosed room with stone walls: must be pitch black (sky light = 0)
        VoxelChunkColumn enclosedColumn = new VoxelChunkColumn(0, 0, -4, 20);
        enclosedColumn.setSection(4, section); // Roof at Y=64
        VoxelSection wallSec = new VoxelSection();
        for (int y = 0; y < 16; y++) {
            for (int i = 0; i < 16; i++) {
                wallSec.setVoxel(0, y, i, true, stoneId);
                wallSec.setVoxel(15, y, i, true, stoneId);
                wallSec.setVoxel(i, y, 0, true, stoneId);
                wallSec.setVoxel(i, y, 15, true, stoneId);
            }
        }
        enclosedColumn.setSection(3, wallSec); // Walls at Y [48..63]

        VoxelChunkLighting enclosedLighting = VoxelLightingAPI.computeLighting(null, enclosedColumn);
        assertEquals(0, enclosedLighting.getSkyLight(8, 55, 8), "Completely enclosed subterranean room must have sky light 0");

        // 5. Block light must be 0 everywhere since no light sources were placed
        assertEquals(0, lighting.getBlockLight(8, 70, 8), "Block light must be 0");
        assertEquals(0, lighting.getBlockLight(8, 50, 8), "Block light must be 0");
    }

    @Test
    @DisplayName("Verify block light emission and symmetric Manhattan decay from an emitting block")
    void testBlockLightEmissionAndDecay() {
        VoxelChunkColumn column = new VoxelChunkColumn(0, 0, -4, 20);

        // Section 4 -> Y [64..79]. Place a glowstone block (emission 15) at (8, 64, 8)
        VoxelSection section = new VoxelSection();
        BlockState glowstoneState = Blocks.GLOWSTONE.defaultBlockState();
        int glowstoneId = MinecraftVoxelBridge.getBlockId(glowstoneState);
        assertEquals(15, glowstoneState.getLightEmission(), "Glowstone must emit light level 15");

        section.setVoxel(8, 0, 8, true, glowstoneId);
        column.setSection(4, section);

        VoxelChunkLighting lighting = VoxelLightingAPI.computeLighting(null, column);
        assertNotNull(lighting, "Lighting result must not be null");

        // Source block itself (emission 15)
        int sourceLight = lighting.getBlockLight(8, 64, 8);
        assertEquals(15, sourceLight, "Glowstone source block must have block light level 15");

        // Immediate 1-step neighbors (distance 1) -> must have light level 14
        assertEquals(14, lighting.getBlockLight(8, 65, 8), "+Y neighbor must have light 14");
        assertEquals(14, lighting.getBlockLight(8, 63, 8), "-Y neighbor must have light 14");
        assertEquals(14, lighting.getBlockLight(9, 64, 8), "+X neighbor must have light 14");
        assertEquals(14, lighting.getBlockLight(7, 64, 8), "-X neighbor must have light 14");
        assertEquals(14, lighting.getBlockLight(8, 64, 9), "+Z neighbor must have light 14");
        assertEquals(14, lighting.getBlockLight(8, 64, 7), "-Z neighbor must have light 14");

        // 2-step neighbor (distance 2) -> must have light level 13
        assertEquals(13, lighting.getBlockLight(8, 66, 8), "Distance 2 neighbor must have light 13");
        assertEquals(13, lighting.getBlockLight(10, 64, 8), "Distance 2 neighbor must have light 13");

        // Verify packed light format: (block << 4) | (sky << 20)
        // Air block directly above glowstone: block light = 14, sky light = 15
        int packedAbove = lighting.getPackedLight(8, 65, 8);
        int expectedPackedAbove = (14 << 4) | (15 << 20);
        assertEquals(expectedPackedAbove, packedAbove, "Packed light above glowstone must match (14 << 4) | (15 << 20)");

        // Inside the solid glowstone block: block light = 15, sky light = 0
        int packedAtSource = lighting.getPackedLight(8, 64, 8);
        int expectedPackedSource = (15 << 4) | (0 << 20);
        assertEquals(expectedPackedSource, packedAtSource, "Packed light inside glowstone must match (15 << 4) | (0 << 20)");
    }

    @Test
    @DisplayName("Verify block light propagates across chunk borders from neighbor chunk into target chunk")
    void testCrossChunkBorderPropagation() {
        // Target chunk is (0, 0)
        VoxelChunkColumn targetColumn = new VoxelChunkColumn(0, 0, -4, 20);

        // East neighbor chunk is (1, 0)
        VoxelChunkColumn eastNeighbor = new VoxelChunkColumn(1, 0, -4, 20);

        // Place a glowstone block (level 15) at local (0, 0, 8) in east neighbor (which is world X = 16, Y = 64, Z = 8)
        VoxelSection neighborSection = new VoxelSection();
        int glowstoneId = MinecraftVoxelBridge.getBlockId(Blocks.GLOWSTONE.defaultBlockState());
        neighborSection.setVoxel(0, 0, 8, true, glowstoneId);
        eastNeighbor.setSection(4, neighborSection);

        // Setup getter and manually set east neighbor
        UnifiedLightChunkGetter getter = new UnifiedLightChunkGetter(null, null, targetColumn);
        getter.setNeighborColumn(eastNeighbor);

        VoxelChunkLighting lighting = QveLightingEngine.computeLighting(getter);
        assertNotNull(lighting, "Lighting result must not be null");

        // In target chunk (0, 0):
        // Local X = 15 is 1 block away from world X = 16 (where glowstone is) -> must have light level 14!
        int borderLight = lighting.getBlockLight(15, 64, 8);
        assertEquals(14, borderLight, "Block light crossing chunk border into local X=15 must be 14");

        // Local X = 14 is 2 blocks away -> must have light level 13!
        int subBorderLight = lighting.getBlockLight(14, 64, 8);
        assertEquals(13, subBorderLight, "Block light at local X=14 must be 13");

        // Local X = 13 is 3 blocks away -> must have light level 12!
        assertEquals(12, lighting.getBlockLight(13, 64, 8), "Block light at local X=13 must be 12");
    }

    @Test
    @DisplayName("Verify asynchronous lighting computation completes on RaycastThreadPool")
    void testAsyncLightingComputation() {
        VoxelChunkColumn column = new VoxelChunkColumn(5, 5, -4, 20);
        CompletableFuture<VoxelChunkLighting> future = VoxelLightingAPI.computeLightingAsync(null, column);
        assertNotNull(future, "CompletableFuture must not be null");

        VoxelChunkLighting lighting = future.join();
        assertNotNull(lighting, "Async lighting result must not be null");
        assertEquals(5, lighting.getChunkX());
        assertEquals(5, lighting.getChunkZ());

        // Empty chunk in broad daylight: sky light must be 15 everywhere
        assertEquals(15, lighting.getSkyLight(8, 100, 8), "Open sky must be 15");
        assertEquals(15, lighting.getSkyLight(8, 0, 8), "Open sky down to 0 must be 15");
    }

    @Test
    @DisplayName("Verify zero-allocation primitive bitmasks and raw byte buffer pruning")
    void testZeroAllocationBitmasksAndPruning() {
        VoxelChunkColumn column = new VoxelChunkColumn(2, 3, -4, 20);
        VoxelChunkLighting lighting = VoxelLightingAPI.computeLighting(null, column);
        assertInstanceOf(com.pixel.qve.neoforge.lighting.DefaultVoxelChunkLighting.class, lighting);
        com.pixel.qve.neoforge.lighting.DefaultVoxelChunkLighting def =
                (com.pixel.qve.neoforge.lighting.DefaultVoxelChunkLighting) lighting;

        int secCount = 20 - (-4); // 24 sections
        long expectedAllBits = (1L << secCount) - 1;

        // In completely empty column:
        // All sections have sky light 15 -> skyFullMask must have all 24 bits set!
        assertEquals(expectedAllBits, def.getSkyFullMask(), "All sections in empty column must be marked full sky light in mask");
        // All sections have block light 0 -> blockZeroMask must have all 24 bits set!
        assertEquals(expectedAllBits, def.getBlockZeroMask(), "All sections in empty column must be marked zero block light in mask");

        // Raw byte buffers should be completely unallocated (null) because all sections are homogeneous!
        byte[][] rawSky = def.getRawSkyData();
        if (rawSky != null) {
            for (byte[] buf : rawSky) {
                assertNull(buf, "Homogeneous sky sections must not allocate raw byte arrays");
            }
        }
        byte[][] rawBlock = def.getRawBlockData();
        if (rawBlock != null) {
            for (byte[] buf : rawBlock) {
                assertNull(buf, "Homogeneous block sections must not allocate raw byte arrays");
            }
        }
    }
}
