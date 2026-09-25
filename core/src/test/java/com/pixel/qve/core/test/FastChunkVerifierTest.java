package com.pixel.qve.core.test;

import com.pixel.qve.mca.FastNbtReader;
import com.pixel.qve.mca.writer.FastChunkNbtWriter;
import com.pixel.qve.mca.writer.FastChunkVerifier;
import com.pixel.qve.mca.writer.FastNbtWriter;
import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.world.VoxelSection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class FastChunkVerifierTest {

    @Test
    @DisplayName("Verify homogeneous single-entry palette with air and solid blocks")
    void testVerifyHomogeneousPalette() {
        BlockIdRegistry registry = new BlockIdRegistry();
        int waterId = registry.getOrRegister("minecraft:water");
        int stoneId = registry.getOrRegister("minecraft:stone");

        // Synthesize chunk with section 0 as 100% water
        FastNbtWriter writer = new FastNbtWriter();
        writer.beginRootCompound("")
                .putInt("DataVersion", 3955)
                .beginList("sections", FastNbtReader.TAG_COMPOUND, 1)
                    .beginListCompound()
                        .putByte("Y", (byte) 0)
                        .beginCompound("block_states")
                            .beginList("palette", FastNbtReader.TAG_COMPOUND, 1)
                                .beginListCompound()
                                    .putString("Name", "minecraft:water")
                                .endCompound()
                            .endCompound()
                        .endCompound()
                    .endCompound()
                .endCompound()
                .endCompound();

        ByteBuffer buf = writer.toReadBuffer();

        // Any block in section 0 (Y in 0..15) must match waterId
        assertTrue(FastChunkVerifier.verifyVoxel(buf, 0, 0, 0, waterId, registry));
        assertTrue(FastChunkVerifier.verifyVoxel(buf, 7, 5, 9, waterId, registry));
        assertTrue(FastChunkVerifier.verifyVoxel(buf, 15, 15, 15, waterId, registry));

        // Must not match stone or air
        assertFalse(FastChunkVerifier.verifyVoxel(buf, 7, 5, 9, stoneId, registry));
        assertFalse(FastChunkVerifier.verifyVoxel(buf, 7, 5, 9, BlockIdRegistry.AIR_ID, registry));
    }

    @Test
    @DisplayName("Verify multi-entry bit-packed palette with single voxel extraction")
    void testVerifyMultiEntryBitPackedPalette() {
        BlockIdRegistry registry = new BlockIdRegistry();
        int stoneId = registry.getOrRegister("minecraft:stone");
        int diamondId = registry.getOrRegister("minecraft:diamond_block");

        // Create section 0 with specific blocks
        VoxelSection section = new VoxelSection();
        section.setVoxel(3, 5, 7, true, diamondId);
        section.setVoxel(15, 12, 1, true, stoneId);

        Map<Integer, VoxelSection> sections = new HashMap<>();
        sections.put(0, section);

        FastNbtWriter writer = new FastNbtWriter();
        FastChunkNbtWriter.writeChunk(writer, 0, 0, 0, 0, registry, sections, null);
        ByteBuffer buf = writer.toReadBuffer();

        // Diamond block at (3, 5, 7)
        assertTrue(FastChunkVerifier.verifyVoxel(buf, 3, 5, 7, diamondId, registry));
        assertFalse(FastChunkVerifier.verifyVoxel(buf, 3, 5, 7, stoneId, registry));
        assertFalse(FastChunkVerifier.verifyVoxel(buf, 3, 5, 7, BlockIdRegistry.AIR_ID, registry));

        // Stone block at (15, 12, 1)
        assertTrue(FastChunkVerifier.verifyVoxel(buf, 15, 12, 1, stoneId, registry));
        assertFalse(FastChunkVerifier.verifyVoxel(buf, 15, 12, 1, diamondId, registry));

        // Air at untouched coordinate (0, 0, 0)
        assertTrue(FastChunkVerifier.verifyVoxel(buf, 0, 0, 0, BlockIdRegistry.AIR_ID, registry));
        assertFalse(FastChunkVerifier.verifyVoxel(buf, 0, 0, 0, diamondId, registry));
    }

    @Test
    @DisplayName("Verify missing sections and sections without block_states evaluate to air")
    void testVerifyMissingSectionEvaluatesToAir() {
        BlockIdRegistry registry = new BlockIdRegistry();
        int stoneId = registry.getOrRegister("minecraft:stone");

        FastNbtWriter writer = new FastNbtWriter();
        writer.beginRootCompound("")
                .putInt("DataVersion", 3955)
                .beginList("sections", FastNbtReader.TAG_COMPOUND, 1)
                    .beginListCompound()
                        .putByte("Y", (byte) 0)
                        // Section 0 has biomes but NO block_states compound
                        .beginCompound("biomes")
                            .beginList("palette", FastNbtReader.TAG_STRING, 1)
                                .putListString("minecraft:plains")
                        .endCompound()
                    .endCompound()
                .endCompound()
                .endCompound();

        ByteBuffer buf = writer.toReadBuffer();

        // Section 0 has no block_states -> air
        assertTrue(FastChunkVerifier.verifyVoxel(buf, 4, 4, 4, BlockIdRegistry.AIR_ID, registry));
        assertFalse(FastChunkVerifier.verifyVoxel(buf, 4, 4, 4, stoneId, registry));

        // Section 3 (Y = 48) does not exist in chunk -> air
        assertTrue(FastChunkVerifier.verifyVoxel(buf, 4, 48, 4, BlockIdRegistry.AIR_ID, registry));
        assertFalse(FastChunkVerifier.verifyVoxel(buf, 4, 48, 4, stoneId, registry));
    }

    @Test
    @DisplayName("Verify inverted NBT tag ordering where block_states appears before Y")
    void testVerifyInvertedTagOrdering() {
        BlockIdRegistry registry = new BlockIdRegistry();
        int goldId = registry.getOrRegister("minecraft:gold_block");

        FastNbtWriter writer = new FastNbtWriter();
        writer.beginRootCompound("")
                .putInt("DataVersion", 3955)
                .beginList("sections", FastNbtReader.TAG_COMPOUND, 2)
                    // Section A: block_states BEFORE Y, Y = 0
                    .beginListCompound()
                        .beginCompound("block_states")
                            .beginList("palette", FastNbtReader.TAG_COMPOUND, 1)
                                .beginListCompound()
                                    .putString("Name", "minecraft:gold_block")
                                .endCompound()
                            .endCompound()
                        .putByte("Y", (byte) 0)
                    .endCompound()
                    // Section B: Y BEFORE block_states, Y = 1
                    .beginListCompound()
                        .putByte("Y", (byte) 1)
                        .beginCompound("block_states")
                            .beginList("palette", FastNbtReader.TAG_COMPOUND, 1)
                                .beginListCompound()
                                    .putString("Name", "minecraft:air")
                                .endCompound()
                            .endCompound()
                        .endCompound()
                    .endCompound()
                .endCompound()
                .endCompound();

        ByteBuffer buf = writer.toReadBuffer();

        // Y = 5 (Section 0) -> gold_block
        assertTrue(FastChunkVerifier.verifyVoxel(buf, 2, 5, 2, goldId, registry));
        assertFalse(FastChunkVerifier.verifyVoxel(buf, 2, 5, 2, BlockIdRegistry.AIR_ID, registry));

        // Y = 20 (Section 1) -> air
        assertTrue(FastChunkVerifier.verifyVoxel(buf, 2, 20, 2, BlockIdRegistry.AIR_ID, registry));
        assertFalse(FastChunkVerifier.verifyVoxel(buf, 2, 20, 2, goldId, registry));
    }
}
