package com.pixel.qve.neoforge.test;

import com.pixel.qve.neoforge.api.ChunkWriteBatch;
import com.pixel.qve.neoforge.api.WriteResult;
import com.pixel.qve.neoforge.api.WriteStatus;
import com.pixel.qve.neoforge.bridge.writer.DeferredChunkQueue;
import com.pixel.qve.world.VoxelSection;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

public class DeferredChunkQueueTest {

    private Level mockLevel;
    private final ResourceKey<Level> overworldKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));

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

    @BeforeEach
    void setUp() {
        DeferredChunkQueue.clear();
        mockLevel = Mockito.mock(Level.class);
        Mockito.when(mockLevel.dimension()).thenReturn(overworldKey);
    }

    @Test
    @DisplayName("WriteStatus and WriteResult support SUCCESS_DEFERRED")
    void testDeferredWriteResult() {
        WriteResult res = WriteResult.successDeferred(10, 20, 50_000L);
        assertEquals(WriteStatus.SUCCESS_DEFERRED, res.status());
        assertEquals(10, res.chunkX());
        assertEquals(20, res.chunkZ());
        assertEquals(50_000L, res.durationNanos());
        assertTrue(res.isSuccess());
        assertTrue(res.isVerified());
        assertEquals("deferred", res.verifiedBlock());
    }

    @Test
    @DisplayName("Enqueue, peek, poll, and hasEdits lifecycle in DeferredChunkQueue")
    void testEnqueueAndPollLifecycle() {
        assertFalse(DeferredChunkQueue.hasEdits(mockLevel, 5, -3));
        assertEquals(0, DeferredChunkQueue.getTotalQueuedChunks());

        ChunkWriteBatch.ChunkEdits edits = new ChunkWriteBatch.ChunkEdits(5, -3);
        BlockState stone = Blocks.STONE.defaultBlockState();
        edits.addMutation(new ChunkWriteBatch.BlockMutation(
                (5 << 4) + 2, 64, (-3 << 4) + 7,
                101, stone,
                null, null,
                -1, null
        ));

        DeferredChunkQueue.enqueue(mockLevel, edits);

        assertTrue(DeferredChunkQueue.hasEdits(mockLevel, 5, -3));
        assertFalse(DeferredChunkQueue.hasEdits(mockLevel, 5, -2));
        assertEquals(1, DeferredChunkQueue.getTotalQueuedChunks());

        // Peek does not remove
        ChunkWriteBatch.ChunkEdits peeked = DeferredChunkQueue.peekEdits(mockLevel, 5, -3);
        assertNotNull(peeked);
        assertEquals(5, peeked.getChunkX());
        assertEquals(-3, peeked.getChunkZ());
        assertEquals(1, DeferredChunkQueue.getTotalQueuedChunks());

        // Poll retrieves and removes
        ChunkWriteBatch.ChunkEdits polled = DeferredChunkQueue.pollEdits(mockLevel, 5, -3);
        assertNotNull(polled);
        assertEquals(5, polled.getChunkX());
        assertEquals(-3, polled.getChunkZ());
        assertEquals(0, DeferredChunkQueue.getTotalQueuedChunks());
        assertFalse(DeferredChunkQueue.hasEdits(mockLevel, 5, -3));
    }

    @Test
    @DisplayName("Merging edits for the same chunk in DeferredChunkQueue")
    void testEditsMerging() {
        ChunkWriteBatch.ChunkEdits edits1 = new ChunkWriteBatch.ChunkEdits(12, 15);
        BlockState stone = Blocks.STONE.defaultBlockState();
        edits1.addMutation(new ChunkWriteBatch.BlockMutation(
                (12 << 4) + 1, 70, (15 << 4) + 1,
                101, stone, null, null, -1, null
        ));

        ChunkWriteBatch.ChunkEdits edits2 = new ChunkWriteBatch.ChunkEdits(12, 15);
        BlockState gold = Blocks.GOLD_BLOCK.defaultBlockState();
        edits2.addMutation(new ChunkWriteBatch.BlockMutation(
                (12 << 4) + 2, 70, (15 << 4) + 2,
                102, gold, null, null, -1, null
        ));
        VoxelSection section = VoxelSection.createHomogeneous(200, true);
        edits2.setSection(5, section);

        DeferredChunkQueue.enqueue(mockLevel, edits1);
        DeferredChunkQueue.enqueue(mockLevel, edits2);

        assertEquals(1, DeferredChunkQueue.getTotalQueuedChunks());
        ChunkWriteBatch.ChunkEdits combined = DeferredChunkQueue.pollEdits(mockLevel, 12, 15);
        assertNotNull(combined);
        assertEquals(2, combined.getMutations().size());
        assertEquals(1, combined.getWholeSections().size());
        assertSame(section, combined.getWholeSections().get(5));
    }

    @Test
    @DisplayName("getBlockId overlays queued mutations and sections")
    void testGetBlockIdOverlay() {
        ChunkWriteBatch.ChunkEdits edits = new ChunkWriteBatch.ChunkEdits(0, 0);
        BlockState diamond = Blocks.DIAMOND_BLOCK.defaultBlockState();
        edits.addMutation(new ChunkWriteBatch.BlockMutation(
                7, 80, 7,
                999, diamond, null, null, -1, null
        ));
        VoxelSection sec4 = VoxelSection.createHomogeneous(888, true);
        edits.setSection(4, sec4); // Y: [64..79]

        DeferredChunkQueue.enqueue(mockLevel, edits);

        // Point mutation at (7, 80, 7)
        assertEquals(999, DeferredChunkQueue.getBlockId(mockLevel, 7, 80, 7));

        // Homogeneous section 4 at (3, 68, 3)
        assertEquals(888, DeferredChunkQueue.getBlockId(mockLevel, 3, 68, 3));

        // Unmodified coordinate in chunk (0, 0)
        assertEquals(0, DeferredChunkQueue.getBlockId(mockLevel, 1, 80, 1));

        // Another chunk
        assertEquals(0, DeferredChunkQueue.getBlockId(mockLevel, 30, 80, 30));
    }
}
