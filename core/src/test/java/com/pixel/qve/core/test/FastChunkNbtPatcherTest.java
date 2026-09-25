package com.pixel.qve.core.test;

import com.pixel.qve.mca.FastNbtReader;
import com.pixel.qve.mca.writer.FastChunkNbtPatcher;
import com.pixel.qve.mca.writer.FastChunkNbtWriter;
import com.pixel.qve.mca.writer.FastNbtWriter;
import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.world.VoxelSection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class FastChunkNbtPatcherTest {

    @Test
    @DisplayName("Verify FastChunkNbtPatcher preserves Status, biomes, structures, and custom tags")
    void testPatchPreservesMetadataAndBiomes() {
        BlockIdRegistry registry = new BlockIdRegistry();
        int stoneId = registry.getOrRegister("minecraft:stone");

        // 1. Synthesize an existing chunk NBT
        FastNbtWriter originalWriter = new FastNbtWriter();
        originalWriter.beginRootCompound("")
                .putInt("DataVersion", 3955)
                .putInt("xPos", 10)
                .putInt("yPos", -4)
                .putInt("zPos", 20)
                .putString("Status", "minecraft:features")
                .putLong("InhabitedTime", 987654321L)
                .beginCompound("structures")
                    .putString("type", "minecraft:mineshaft")
                .endCompound()
                .beginList("sections", FastNbtReader.TAG_COMPOUND, 2)
                    // Section 0: Desert biome
                    .beginListCompound()
                        .putByte("Y", (byte) 0)
                        .beginCompound("biomes")
                            .beginList("palette", FastNbtReader.TAG_STRING, 1)
                                .putListString("minecraft:desert")
                        .endCompound()
                        .beginCompound("block_states")
                            .beginList("palette", FastNbtReader.TAG_COMPOUND, 1)
                                .beginListCompound()
                                    .putString("Name", "minecraft:air")
                                .endCompound()
                        .endCompound()
                    .endCompound()
                    // Section 1: Badlands biome
                    .beginListCompound()
                        .putByte("Y", (byte) 1)
                        .beginCompound("biomes")
                            .beginList("palette", FastNbtReader.TAG_STRING, 1)
                                .putListString("minecraft:badlands")
                        .endCompound()
                        .beginCompound("block_states")
                            .beginList("palette", FastNbtReader.TAG_COMPOUND, 1)
                                .beginListCompound()
                                    .putString("Name", "minecraft:air")
                                .endCompound()
                        .endCompound()
                    .endCompound()
                .endCompound()
                .endCompound();

        ByteBuffer existingNbt = originalWriter.toReadBuffer();

        // 2. Prepare modifications: update Section 0 with Stone
        VoxelSection modifiedSection0 = new VoxelSection();
        modifiedSection0.setVoxel(0, 0, 0, true, stoneId);
        Map<Integer, VoxelSection> modifiedSections = new HashMap<>();
        modifiedSections.put(0, modifiedSection0);

        // 3. Patch chunk
        FastNbtWriter patchedWriter = new FastNbtWriter();
        FastChunkNbtPatcher.patchChunk(
                existingNbt,
                10, 20,
                -4, 19,
                registry,
                modifiedSections,
                null,
                patchedWriter
        );

        // 4. Verify patched NBT
        ByteBuffer patchedBuf = patchedWriter.toReadBuffer();
        Map<String, Object> root = FastNbtReader.parseRootCompound(patchedBuf);

        assertNotNull(root);
        assertEquals(3955, root.get("DataVersion"));
        assertEquals(10, root.get("xPos"));
        assertEquals(20, root.get("zPos"));
        // Status MUST be promoted to "minecraft:full" to prevent Minecraft worldgen relocation
        assertEquals("minecraft:full", root.get("Status"));
        assertEquals(987654321L, root.get("InhabitedTime"));

        // Structures MUST be preserved
        @SuppressWarnings("unchecked")
        Map<String, Object> structures = (Map<String, Object>) root.get("structures");
        assertNotNull(structures);
        assertEquals("minecraft:mineshaft", structures.get("type"));

        // Sections MUST preserve desert and badlands biomes
        @SuppressWarnings("unchecked")
        List<Object> sections = (List<Object>) root.get("sections");
        assertNotNull(sections);
        assertEquals(2, sections.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> sec0 = (Map<String, Object>) sections.get(0);
        assertEquals((byte) 0, sec0.get("Y"));
        @SuppressWarnings("unchecked")
        Map<String, Object> biomes0 = (Map<String, Object>) sec0.get("biomes");
        assertNotNull(biomes0);
        @SuppressWarnings("unchecked")
        List<Object> bioPalette0 = (List<Object>) biomes0.get("palette");
        assertEquals("minecraft:desert", bioPalette0.get(0));

        // Section 0 block_states must now include stone
        @SuppressWarnings("unchecked")
        Map<String, Object> bs0 = (Map<String, Object>) sec0.get("block_states");
        assertNotNull(bs0);
        @SuppressWarnings("unchecked")
        List<Object> pal0 = (List<Object>) bs0.get("palette");
        boolean foundStone = false;
        for (Object o : pal0) {
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) o;
            if ("minecraft:stone".equals(entry.get("Name"))) {
                foundStone = true;
                break;
            }
        }
        assertTrue(foundStone, "Patched section 0 must contain minecraft:stone");

        // Section 1 biome must remain badlands
        @SuppressWarnings("unchecked")
        Map<String, Object> sec1 = (Map<String, Object>) sections.get(1);
        assertEquals((byte) 1, sec1.get("Y"));
        @SuppressWarnings("unchecked")
        Map<String, Object> biomes1 = (Map<String, Object>) sec1.get("biomes");
        @SuppressWarnings("unchecked")
        List<Object> bioPalette1 = (List<Object>) biomes1.get("palette");
        assertEquals("minecraft:badlands", bioPalette1.get(0));

        // Heightmaps must be present
        assertTrue(root.containsKey("Heightmaps"));
    }

    @Test
    @DisplayName("Verify FastChunkNbtPatcher patches section correctly even when block_states precedes Y")
    void testInvertedTagOrderPatching() {
        BlockIdRegistry registry = new BlockIdRegistry();
        int diamondId = registry.getOrRegister("minecraft:diamond_block");

        FastNbtWriter writer = new FastNbtWriter(4096);
        writer.beginRootCompound("");
        writer.putInt("DataVersion", 3955);
        writer.putInt("xPos", 10);
        writer.putInt("zPos", 20);
        writer.putString("Status", "minecraft:full");

        // Single section list with block_states BEFORE Y
        writer.beginList("sections", FastNbtReader.TAG_COMPOUND, 1);
        writer.beginListCompound();

        // 1. Write block_states FIRST (all air)
        writer.beginCompound("block_states")
                .beginList("palette", FastNbtReader.TAG_COMPOUND, 1)
                    .beginListCompound()
                        .putString("Name", "minecraft:air")
                    .endCompound()
                .endCompound();

        // 2. Write Y SECOND
        writer.putByte("Y", (byte) 5);

        // 3. Write biomes THIRD
        writer.beginCompound("biomes")
                .beginList("palette", FastNbtReader.TAG_STRING, 1)
                    .putListString("minecraft:ocean")
                .endCompound();

        writer.endCompound();
        writer.endCompound();

        ByteBuffer inputBuf = ByteBuffer.wrap(writer.toByteArray());

        // Prepare modification: diamond block at section Y=5
        VoxelSection modSec = new VoxelSection();
        modSec.setVoxel(7, 8, 9, true, diamondId);

        FastNbtWriter outWriter = new FastNbtWriter(4096);
        FastChunkNbtPatcher.patchChunk(
                inputBuf,
                10, 20,
                -4, 19,
                registry,
                Map.of(5, modSec),
                null,
                outWriter
        );

        ByteBuffer patchedBuf = ByteBuffer.wrap(outWriter.toByteArray());
        Map<String, Object> root = FastNbtReader.parseRootCompound(patchedBuf);

        @SuppressWarnings("unchecked")
        List<Object> sections = (List<Object>) root.get("sections");
        assertEquals(1, sections.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> sec = (Map<String, Object>) sections.get(0);
        assertEquals((byte) 5, sec.get("Y"));

        @SuppressWarnings("unchecked")
        Map<String, Object> bs = (Map<String, Object>) sec.get("block_states");
        assertNotNull(bs);

        @SuppressWarnings("unchecked")
        List<Object> palette = (List<Object>) bs.get("palette");
        boolean foundDiamond = false;
        for (Object o : palette) {
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) o;
            if ("minecraft:diamond_block".equals(entry.get("Name"))) {
                foundDiamond = true;
                break;
            }
        }
        assertTrue(foundDiamond, "Patched section must contain diamond block despite inverted NBT tag ordering");
    }

    @Test
    @DisplayName("Verify VoxelSection automatically demotes homogeneous sections to mutable on write")
    void testHomogeneousSectionDemotion() {
        VoxelSection sec = VoxelSection.createHomogeneous(5, true);
        assertTrue(sec.isHomogeneous());
        assertEquals(5, sec.getBlockId(0, 0, 0));
        assertEquals(5, sec.getBlockId(15, 15, 15));

        // Modifying a voxel must NOT throw UnsupportedOperationException
        sec.setVoxel(3, 4, 5, true, 42);

        assertFalse(sec.isHomogeneous());
        assertEquals(42, sec.getBlockId(3, 4, 5));
        assertEquals(5, sec.getBlockId(0, 0, 0));
        assertEquals(5, sec.getBlockId(15, 15, 15));
    }

    @Test
    @DisplayName("Verify FastChunkNbtPatcher strictly enforces xPos, zPos, yPos, and Status: minecraft:full")
    void testCoordinateAndStatusEnforcement() {
        BlockIdRegistry registry = new BlockIdRegistry();

        // Synthesize chunk with wrong/proto coordinates and status
        FastNbtWriter writer = new FastNbtWriter(4096);
        writer.beginRootCompound("")
                .putInt("DataVersion", 3955)
                .putInt("xPos", 999)
                .putInt("zPos", -888)
                .putInt("yPos", 0)
                .putString("Status", "minecraft:carvers")
                .beginList("sections", FastNbtReader.TAG_COMPOUND, 0)
                .endCompound();

        ByteBuffer inputBuf = ByteBuffer.wrap(writer.toByteArray());

        FastNbtWriter outWriter = new FastNbtWriter(4096);
        FastChunkNbtPatcher.patchChunk(
                inputBuf,
                -108, 4020,
                -4, 19,
                registry,
                Map.of(),
                null,
                outWriter
        );

        ByteBuffer patchedBuf = ByteBuffer.wrap(outWriter.toByteArray());
        Map<String, Object> root = FastNbtReader.parseRootCompound(patchedBuf);

        assertNotNull(root);
        assertEquals(-108, root.get("xPos"), "xPos must be strictly overwritten to target chunkX");
        assertEquals(4020, root.get("zPos"), "zPos must be strictly overwritten to target chunkZ");
        assertEquals(-4, root.get("yPos"), "yPos must match minSectionY");
        assertEquals("minecraft:full", root.get("Status"), "Status must be promoted to minecraft:full");
    }
}
