package com.pixel.qve.core.test;

import com.pixel.qve.mca.McaRegionReader;
import com.pixel.qve.mca.writer.McaWriteCoordinator;
import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.state.ShapeRegistry;
import com.pixel.qve.world.VoxelSection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class McaRoundTripTest {

    @Test
    @DisplayName("Write chunk with McaWriteCoordinator and read back with McaRegionReader")
    void testChunkWriteAndReadBack(@TempDir Path tempDir) throws IOException {
        BlockIdRegistry registry = new BlockIdRegistry();
        ShapeRegistry shapeRegistry = new ShapeRegistry();

        int stoneId = registry.getOrRegister("minecraft:stone");
        int dirtId = registry.getOrRegister("minecraft:dirt");
        int glassId = registry.getOrRegister("minecraft:glass");

        // Prepare section 0 with specific test patterns
        VoxelSection section0 = new VoxelSection();
        // Layer y=0 is stone
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                section0.setVoxel(x, 0, z, true, stoneId);
            }
        }
        // Layer y=1 is dirt
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                section0.setVoxel(x, 1, z, true, dirtId);
            }
        }
        // Specific pillar of glass at (5, y, 5)
        for (int y = 2; y < 10; y++) {
            section0.setVoxel(5, y, 5, true, glassId);
        }

        Map<Integer, VoxelSection> sections = new HashMap<>();
        sections.put(0, section0);

        try (McaWriteCoordinator coordinator = new McaWriteCoordinator(tempDir, registry, -4, 19)) {
            // Write chunk (0, 0) -> r.0.0.mca
            var metrics = coordinator.writeChunkSync(0, 0, sections, null);
            assertNotNull(metrics);
            assertTrue(metrics.compressedBytes() > 0);
            assertTrue(metrics.sectorOffset() >= 2);
        }

        Path mcaFile = tempDir.resolve("r.0.0.mca");
        assertTrue(java.nio.file.Files.exists(mcaFile), "Region file r.0.0.mca must exist on disk");

        // Read back chunk (0, 0) with McaRegionReader
        AtomicInteger parsedSectionsCount = new AtomicInteger(0);
        try (McaRegionReader reader = new McaRegionReader(mcaFile, registry, shapeRegistry)) {
            assertTrue(reader.hasChunk(0, 0), "Reader must see chunk (0, 0) in header");

            int count = reader.readChunk(0, 0, (secY, readSec) -> {
                parsedSectionsCount.incrementAndGet();
                if (secY == 0) {
                    assertEquals(section0.getSolidCount(), readSec.getSolidCount(), "Solid count must match exactly");

                    // Verify voxel identity across all 4096 positions
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                assertEquals(section0.getBlockId(x, y, z), readSec.getBlockId(x, y, z),
                                        "Block ID mismatch at (" + x + "," + y + "," + z + ")");
                                assertEquals(section0.isSolid(x, y, z), readSec.isSolid(x, y, z),
                                        "Solidity mismatch at (" + x + "," + y + "," + z + ")");
                            }
                        }
                    }
                }
            });

            assertTrue(count > 0, "Reader must have parsed sections");
            assertTrue(parsedSectionsCount.get() > 0);
        }
    }

    @Test
    @DisplayName("Write multiple chunks concurrently across same and different regions")
    void testMultiChunkAndMultiRegionConcurrentWrites(@TempDir Path tempDir) throws Exception {
        BlockIdRegistry registry = new BlockIdRegistry();
        int stoneId = registry.getOrRegister("minecraft:stone");

        VoxelSection testSection = new VoxelSection();
        testSection.setVoxel(0, 0, 0, true, stoneId);
        Map<Integer, VoxelSection> map = Map.of(0, testSection);

        try (McaWriteCoordinator coordinator = new McaWriteCoordinator(tempDir, registry, -4, 19)) {
            // Chunks in region r.0.0.mca
            var f1 = coordinator.writeChunkAsync(0, 0, map, null);
            var f2 = coordinator.writeChunkAsync(1, 1, map, null);
            var f3 = coordinator.writeChunkAsync(2, 2, map, null);

            // Chunk in region r.1.1.mca (chunk (35, 35))
            var f4 = coordinator.writeChunkAsync(35, 35, map, null);

            // Chunk in region r.-1.-1.mca (chunk (-5, -5))
            var f5 = coordinator.writeChunkAsync(-5, -5, map, null);

            java.util.concurrent.CompletableFuture.allOf(f1, f2, f3, f4, f5).get();
        }

        assertTrue(java.nio.file.Files.exists(tempDir.resolve("r.0.0.mca")));
        assertTrue(java.nio.file.Files.exists(tempDir.resolve("r.1.1.mca")));
        assertTrue(java.nio.file.Files.exists(tempDir.resolve("r.-1.-1.mca")));

        // Verify r.0.0.mca has chunks (0,0), (1,1), (2,2)
        try (McaRegionReader reader = new McaRegionReader(tempDir.resolve("r.0.0.mca"), registry)) {
            assertTrue(reader.hasChunk(0, 0));
            assertTrue(reader.hasChunk(1, 1));
            assertTrue(reader.hasChunk(2, 2));
            assertFalse(reader.hasChunk(3, 3));
        }

        // Verify r.1.1.mca has chunk (35 & 31 = 3, 35 & 31 = 3)
        try (McaRegionReader reader = new McaRegionReader(tempDir.resolve("r.1.1.mca"), registry)) {
            assertTrue(reader.hasChunk(35 & 31, 35 & 31));
        }
    }

    @Test
    @DisplayName("Write chunk containing wrapped Block{minecraft:emerald_block} and verify sanitization on disk")
    void testBlockWrapperSanitizationAndRoundTrip(@TempDir Path tempDir) throws Exception {
        BlockIdRegistry writeRegistry = new BlockIdRegistry();
        // Simulate a dirty/un-sanitized block name containing "Block{...}"
        int dirtyEmeraldId = writeRegistry.getOrRegister("Block{minecraft:emerald_block}");

        VoxelSection section = new VoxelSection();
        section.setVoxel(7, 5, 7, true, dirtyEmeraldId);
        Map<Integer, VoxelSection> sections = Map.of(5, section);

        try (McaWriteCoordinator coordinator = new McaWriteCoordinator(tempDir, writeRegistry, -4, 19)) {
            var metrics = coordinator.writeChunkSync(0, 0, sections, null);
            assertNotNull(metrics);
        }

        // Read back with a fresh registry that has canonical "minecraft:emerald_block"
        BlockIdRegistry readRegistry = new BlockIdRegistry();
        try (McaRegionReader reader = new McaRegionReader(tempDir.resolve("r.0.0.mca"), readRegistry)) {
            assertTrue(reader.hasChunk(0, 0));
            AtomicInteger parsedBlocks = new AtomicInteger(0);
            reader.readChunk(0, 0, (secY, readSec) -> {
                if (secY == 5) {
                    int blockId = readSec.getBlockId(7, 5, 7);
                    String blockName = readRegistry.getName(blockId);
                    assertEquals("minecraft:emerald_block", blockName, "Palette entry must be cleanly sanitized without Block{...}");
                    parsedBlocks.incrementAndGet();
                }
            });
            assertEquals(1, parsedBlocks.get(), "Must have parsed the emerald block section");
        }
    }

    @Test
    @DisplayName("Verify McaRegionWriter rollback restores previous header and payload state")
    void testRegionRollback(@TempDir Path tempDir) throws Exception {
        BlockIdRegistry registry = new BlockIdRegistry();
        int stoneId = registry.getOrRegister("minecraft:stone");
        int diamondId = registry.getOrRegister("minecraft:diamond_block");

        Path mcaFile = tempDir.resolve("r.0.0.mca");

        // 1. Initial state: chunk (0, 0) has stone
        VoxelSection stoneSection = new VoxelSection();
        stoneSection.setVoxel(0, 0, 0, true, stoneId);
        try (McaWriteCoordinator coordinator = new McaWriteCoordinator(tempDir, registry, -4, 19)) {
            coordinator.writeChunkSync(0, 0, Map.of(0, stoneSection), null);
            assertTrue(coordinator.verifyVoxel(0, 0, 0, 0, 0, stoneId));
            assertFalse(coordinator.hasChunk(1, 0));
        }

        // 2. Open writer directly, capture snapshot and old raw payload
        try (com.pixel.qve.mca.writer.McaRegionWriter writer = new com.pixel.qve.mca.writer.McaRegionWriter(mcaFile)) {
            var snapshot = writer.snapshotAllocator();
            byte[] oldRaw = writer.readChunkRaw(0);
            assertNotNull(oldRaw);

            // Mutate chunk 0 to diamond and create chunk 1
            com.pixel.qve.mca.writer.FastNbtWriter nbtWriter = new com.pixel.qve.mca.writer.FastNbtWriter();
            VoxelSection diamondSection = new VoxelSection();
            diamondSection.setVoxel(0, 0, 0, true, diamondId);
            com.pixel.qve.mca.writer.FastChunkNbtWriter.writeChunk(nbtWriter, 0, 0, -4, 19, registry, Map.of(0, diamondSection), null);
            writer.writeChunk(0, 0, nbtWriter.toByteArray(), false);

            nbtWriter.reset();
            com.pixel.qve.mca.writer.FastChunkNbtWriter.writeChunk(nbtWriter, 1, 0, -4, 19, registry, Map.of(0, diamondSection), null);
            writer.writeChunk(1, 0, nbtWriter.toByteArray(), false);

            assertTrue(writer.hasChunk(1, 0), "Chunk 1 should exist before rollback");

            // 3. Trigger rollback
            writer.rollback(snapshot, Map.of(0, oldRaw));

            assertFalse(writer.hasChunk(1, 0), "Chunk 1 must not exist after rollback");
            assertTrue(writer.hasChunk(0, 0), "Chunk 0 must exist after rollback");
        }

        // 4. Verify on disk with fresh reader
        try (McaRegionReader reader = new McaRegionReader(mcaFile, registry)) {
            assertFalse(reader.hasChunk(1, 0), "Chunk 1 must not exist on disk");
            assertTrue(reader.hasChunk(0, 0), "Chunk 0 must exist on disk");

            reader.readChunk(0, 0, (secY, sec) -> {
                if (secY == 0) {
                    assertEquals(stoneId, sec.getBlockId(0, 0, 0), "Chunk 0 must be restored to stone");
                }
            });
        }
    }
}
