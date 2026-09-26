package com.pixel.qve.neoforge.test;

import com.pixel.qve.mca.writer.VoxelDiskWriterThreadPool;
import com.pixel.qve.mca.writer.WriteOptions;
import com.pixel.qve.mca.writer.WriteOptions.CreationPolicy;
import com.pixel.qve.mca.writer.WriteOptions.ExecutionPolicy;
import com.pixel.qve.neoforge.api.BatchWriteResult;
import com.pixel.qve.neoforge.api.ChunkWriteBatch;
import com.pixel.qve.neoforge.api.ChunkWriteBatch.SortStrategy;
import java.util.Comparator;
import com.pixel.qve.neoforge.api.WriteResult;
import com.pixel.qve.neoforge.api.WriteStatus;
import com.pixel.qve.neoforge.api.event.ChunkPostDirectWriteEvent;
import com.pixel.qve.neoforge.api.event.ChunkPreDirectWriteEvent;
import com.pixel.qve.neoforge.bridge.writer.ChunkExclusivityGuard;
import com.pixel.qve.neoforge.bridge.writer.ChunkWriteContext;
import com.pixel.qve.neoforge.recovery.BatchRecoveryJournal;
import com.pixel.qve.world.VoxelSection;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class VoxelWriteAPITest {

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
    @DisplayName("WriteResult factories and status evaluations")
    void testWriteResultMechanics() {
        WriteResult ram = WriteResult.successRam(5, 10, 150_000L);
        assertTrue(ram.isSuccess());
        assertEquals(WriteStatus.SUCCESS_RAM, ram.status());
        assertEquals(5, ram.chunkX());
        assertEquals(10, ram.chunkZ());
        assertEquals(150_000L, ram.durationNanos());

        WriteResult diskInPlace = WriteResult.successDisk(0, 0, 500_000L, 4096, 2, false);
        assertTrue(diskInPlace.isSuccess());
        assertEquals(WriteStatus.SUCCESS_DISK_IN_PLACE, diskInPlace.status());

        WriteResult diskRelocated = WriteResult.successDisk(0, 0, 800_000L, 8192, 10, true);
        assertTrue(diskRelocated.isSuccess());
        assertEquals(WriteStatus.SUCCESS_DISK_REALLOCATED, diskRelocated.status());

        WriteResult failure = WriteResult.failure(WriteStatus.FAIL_CHUNK_LOADED_IN_RAM, 2, 3, "Chunk in RAM");
        assertFalse(failure.isSuccess());
        assertEquals(WriteStatus.FAIL_CHUNK_LOADED_IN_RAM, failure.status());
        assertEquals("Chunk in RAM", failure.errorMessage());
    }

    @Test
    @DisplayName("ChunkWriteContext sparse block and section manipulation")
    void testChunkWriteContextOperations() {
        ChunkWriteContext ctx = new ChunkWriteContext(10, 20, -4, 19, true);
        assertEquals(10, ctx.getChunkX());
        assertEquals(20, ctx.getChunkZ());
        assertEquals(0, ctx.getRegionX());
        assertEquals(0, ctx.getRegionZ());
        assertTrue(ctx.isNewChunk());

        // Set block at local (5, 64, 5) -> section 4 (64 >> 4 = 4)
        ctx.setBlock(5, 64, 5, 42);
        assertEquals(42, ctx.getBlock(5, 64, 5));

        VoxelSection sec4 = ctx.getSection(4);
        assertNotNull(sec4);
        assertEquals(42, sec4.getBlockId(5, 0, 5));

        int mask = ctx.getModifiedSectionMask();
        assertTrue((mask & (1 << (4 - (-4)))) != 0, "Modified mask must reflect section 4");
    }

    @Test
    @DisplayName("ChunkPreDirectWriteEvent cancellation logic")
    void testPreWriteEventCancellation() {
        net.minecraft.world.level.Level mockLevel = org.mockito.Mockito.mock(net.minecraft.world.level.Level.class);
        ChunkWriteContext ctx = new ChunkWriteContext(0, 0, -4, 19, false);
        ChunkPreDirectWriteEvent event = new ChunkPreDirectWriteEvent(
                mockLevel,
                0, 0, ctx
        );

        assertFalse(event.isCanceled());
        event.setCanceled(true);
        assertTrue(event.isCanceled());
        assertEquals(0, event.getChunkX());
        assertEquals(0, event.getChunkZ());
        assertEquals(0, event.getRegionX());
        assertEquals(0, event.getRegionZ());
    }

    @Test
    @DisplayName("WriteResult read-back verification metadata")
    void testWriteResultVerification() {
        WriteResult res = WriteResult.successDisk(62, 62, 2_000_000L, 2048, 5, false)
                .withVerification(true, "minecraft:emerald_block");

        assertTrue(res.isSuccess());
        assertTrue(res.isVerified());
        assertEquals("minecraft:emerald_block", res.verifiedBlock());
    }

    @Test
    @DisplayName("VoxelWriteMode enumeration contracts")
    void testVoxelWriteMode() {
        assertEquals("Unified", com.pixel.qve.neoforge.api.VoxelWriteMode.UNIFIED.getDisplayName());
        assertEquals("Strict Direct Disk", com.pixel.qve.neoforge.api.VoxelWriteMode.STRICT_DIRECT.getDisplayName());
        assertNotNull(com.pixel.qve.neoforge.api.VoxelWriteMode.UNIFIED.getDescription());
        assertNotNull(com.pixel.qve.neoforge.api.VoxelWriteMode.STRICT_DIRECT.getDescription());
    }

    @Test
    @DisplayName("ChunkWriteBatch heterogeneous buffering and volumetric fill")
    void testChunkWriteBatchHeterogeneousAndFill() {
        net.minecraft.world.level.Level mockLevel = org.mockito.Mockito.mock(net.minecraft.world.level.Level.class);
        org.mockito.Mockito.when(mockLevel.getMinBuildHeight()).thenReturn(-64);
        org.mockito.Mockito.when(mockLevel.getMaxBuildHeight()).thenReturn(320);

        ChunkWriteBatch batch = new ChunkWriteBatch(mockLevel);
        assertEquals(0, batch.getTotalBlockCount());
        assertEquals(0, batch.getAffectedChunkCount());

        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();

        // 1. Heterogeneous single blocks
        batch.setBlock(new BlockPos(0, 64, 0), stone);
        batch.setBlock(new BlockPos(1000, 150, 1000), dirt);
        assertEquals(2, batch.getTotalBlockCount());
        assertEquals(2, batch.getAffectedChunkCount());

        // 2. Volumetric fill 6x2x6 = 72 voxels within chunk (0, 0)
        batch.fill(new BlockPos(10, 64, 10), new BlockPos(15, 65, 15), stone);
        assertEquals(74, batch.getTotalBlockCount());
        assertEquals(2, batch.getAffectedChunkCount());

        // 3. Volumetric fill crossing chunk boundaries from chunk (0, 0) to chunk (1, 1)
        batch.fill(new BlockPos(14, 70, 14), new BlockPos(18, 70, 18), dirt);
        // (18 - 14 + 1) * 1 * (18 - 14 + 1) = 25 voxels
        assertEquals(99, batch.getTotalBlockCount());
        assertTrue(batch.getAffectedChunkCount() >= 4, "Must span chunks (0,0), (1,0), (0,1), (1,1)");
    }

    @Test
    @DisplayName("BatchWriteResult metrics and throughput calculation")
    void testBatchWriteResultMetrics() {
        BatchWriteResult res = new BatchWriteResult(
                4, 4, 0,
                100_000, 1, 3,
                100_000_000L, // 100 ms = 0.1s
                java.util.List.of()
        );

        assertTrue(res.isAllSuccessful());
        assertEquals(100_000, res.totalBlocks());
        assertEquals(1, res.ramChunkCount());
        assertEquals(3, res.diskChunkCount());
        assertEquals(100.0, res.durationMs(), 0.001);
        assertEquals(1_000_000.0, res.throughputBlocksPerSecond(), 1.0);
    }

    @Test
    @DisplayName("WriteOptions presets, policy combinations, and immutability")
    void testWriteOptionsPolicies() {
        WriteOptions def = WriteOptions.DEFAULT;
        assertFalse(def.isStrict());
        assertTrue(def.canCreateIfMissing());
        assertEquals(ExecutionPolicy.UNIFIED, def.executionPolicy());
        assertEquals(CreationPolicy.CREATE_IF_MISSING, def.creationPolicy());

        WriteOptions strict = WriteOptions.STRICT;
        assertTrue(strict.isStrict());
        assertTrue(strict.canCreateIfMissing());
        assertEquals(ExecutionPolicy.STRICT_DIRECT, strict.executionPolicy());

        WriteOptions existingOnly = WriteOptions.EXISTING_ONLY;
        assertFalse(existingOnly.isStrict());
        assertFalse(existingOnly.canCreateIfMissing());
        assertTrue(existingOnly.shouldFailIfMissing());
        assertEquals(CreationPolicy.FAIL_IF_MISSING, existingOnly.creationPolicy());

        WriteOptions strictExisting = WriteOptions.STRICT_EXISTING_ONLY;
        assertTrue(strictExisting.isStrict());
        assertFalse(strictExisting.canCreateIfMissing());
        assertTrue(strictExisting.shouldFailIfMissing());

        WriteOptions custom = new WriteOptions(ExecutionPolicy.UNIFIED, CreationPolicy.CREATE_IF_MISSING);
        assertEquals(def, custom);
    }

    @Test
    @DisplayName("Pre-flight fail-fast validates coordinate height boundaries")
    void testPreFlightOutOfBoundsValidation() {
        ServerLevel mockLevel = org.mockito.Mockito.mock(ServerLevel.class);
        org.mockito.Mockito.when(mockLevel.getMinBuildHeight()).thenReturn(-64);
        org.mockito.Mockito.when(mockLevel.getMaxBuildHeight()).thenReturn(320);

        ChunkWriteBatch batch = new ChunkWriteBatch(mockLevel);
        BlockState stone = Blocks.STONE.defaultBlockState();

        // Enqueue block below min build height (-65 < -64)
        batch.setBlock(new BlockPos(0, -65, 0), stone);

        Optional<WriteResult> failureOpt = batch.validate(WriteOptions.DEFAULT);
        assertTrue(failureOpt.isPresent(), "Pre-flight validation must fail for out of bounds Y");
        WriteResult failure = failureOpt.get();
        assertEquals(WriteStatus.FAIL_INVALID_COORDINATES, failure.status());
        assertFalse(failure.isSuccess());

        // Verify executeAsync fails fast immediately without touching blocks
        BatchWriteResult batchRes = batch.executeAsync(WriteOptions.DEFAULT).join();
        assertFalse(batchRes.isAllSuccessful());
        assertEquals(1, batchRes.totalFailed());
        assertEquals(0, batchRes.ramChunkCount());
        assertEquals(0, batchRes.diskChunkCount());
    }

    @Test
    @DisplayName("Pre-flight fail-fast rejects RAM chunks under strict policy")
    void testPreFlightStrictRamRejection() {
        ServerLevel mockLevel = org.mockito.Mockito.mock(ServerLevel.class);
        ServerChunkCache mockCache = org.mockito.Mockito.mock(ServerChunkCache.class);
        org.mockito.Mockito.when(mockLevel.getChunkSource()).thenReturn(mockCache);
        org.mockito.Mockito.when(mockLevel.getMinBuildHeight()).thenReturn(-64);
        org.mockito.Mockito.when(mockLevel.getMaxBuildHeight()).thenReturn(320);

        // Chunk (5, 5) is loaded in RAM
        net.minecraft.world.level.chunk.LevelChunk mockLevelChunk = org.mockito.Mockito.mock(net.minecraft.world.level.chunk.LevelChunk.class);
        org.mockito.Mockito.when(mockCache.getChunkNow(5, 5)).thenReturn(mockLevelChunk);
        // Chunk (6, 6) is not loaded
        org.mockito.Mockito.when(mockCache.getChunkNow(6, 6)).thenReturn(null);

        ChunkWriteBatch batch = new ChunkWriteBatch(mockLevel);
        BlockState stone = Blocks.STONE.defaultBlockState();
        batch.setBlock(new BlockPos(5 * 16, 64, 5 * 16), stone);

        // In UNIFIED mode, validation passes
        Optional<WriteResult> unifiedOpt = batch.validate(WriteOptions.DEFAULT);
        assertTrue(unifiedOpt.isEmpty(), "Unified mode must allow RAM chunks during pre-flight");

        // In STRICT mode, validation must fail fast
        Optional<WriteResult> strictOpt = batch.validate(WriteOptions.STRICT);
        assertTrue(strictOpt.isPresent(), "Strict mode must reject RAM chunks during pre-flight");
        assertEquals(WriteStatus.FAIL_CHUNK_LOADED_IN_RAM, strictOpt.get().status());
    }

    @Test
    @DisplayName("Pre-flight fail-fast rejects ungenerated chunks under FAIL_IF_MISSING policy")
    void testPreFlightFailIfMissing() {
        ServerLevel mockLevel = org.mockito.Mockito.mock(ServerLevel.class);
        ServerChunkCache mockCache = org.mockito.Mockito.mock(ServerChunkCache.class);
        org.mockito.Mockito.when(mockLevel.getChunkSource()).thenReturn(mockCache);
        org.mockito.Mockito.when(mockLevel.getMinBuildHeight()).thenReturn(-64);
        org.mockito.Mockito.when(mockLevel.getMaxBuildHeight()).thenReturn(320);

        // Chunk (999, 999) is NOT in RAM
        org.mockito.Mockito.when(mockCache.getChunkNow(999, 999)).thenReturn(null);

        ChunkWriteBatch batch = new ChunkWriteBatch(mockLevel);
        BlockState stone = Blocks.STONE.defaultBlockState();
        batch.setBlock(new BlockPos(999 * 16, 64, 999 * 16), stone);

        // Under EXISTING_ONLY without coordinator having chunk -> fails fast with FAIL_CHUNK_NOT_FOUND
        Optional<WriteResult> failOpt = batch.validate(WriteOptions.EXISTING_ONLY);
        assertTrue(failOpt.isPresent(), "FAIL_IF_MISSING must reject missing chunk during pre-flight");
        assertEquals(WriteStatus.FAIL_CHUNK_NOT_FOUND, failOpt.get().status());
    }

    @Test
    @DisplayName("VoxelDiskWriterThreadPool graceful drain and shutdown")
    void testDrainAndShutdownGracefulTermination() {
        java.util.concurrent.atomic.AtomicBoolean taskFinished = new java.util.concurrent.atomic.AtomicBoolean(false);
        VoxelDiskWriterThreadPool.submit(() -> {
            Thread.sleep(50);
            taskFinished.set(true);
            return null;
        });
        assertTrue(VoxelDiskWriterThreadPool.isRunning());

        boolean drained = VoxelDiskWriterThreadPool.drainAndShutdown(3, TimeUnit.SECONDS);
        assertTrue(drained, "Pool must drain and shut down cleanly");
        assertTrue(taskFinished.get(), "Submitted task must complete before drain finishes");
        assertFalse(VoxelDiskWriterThreadPool.isRunning(), "Pool must report not running after shutdown");
    }

    @Test
    @DisplayName("ChunkWriteBatch spatial sorting strategies (Player proximity and centroid)")
    void testSpatialSortingStrategies() {
        ServerLevel mockLevel = org.mockito.Mockito.mock(ServerLevel.class);
        org.mockito.Mockito.when(mockLevel.getMinBuildHeight()).thenReturn(-64);
        org.mockito.Mockito.when(mockLevel.getMaxBuildHeight()).thenReturn(320);

        ServerPlayer mockPlayer = org.mockito.Mockito.mock(ServerPlayer.class);
        org.mockito.Mockito.when(mockPlayer.chunkPosition()).thenReturn(new ChunkPos(10, 10));
        org.mockito.Mockito.when(mockLevel.players()).thenReturn(List.of(mockPlayer));

        ChunkWriteBatch batch = new ChunkWriteBatch(mockLevel);
        BlockState stone = Blocks.STONE.defaultBlockState();

        // Enqueue chunks at (0, 0), (10, 10), and (50, 50)
        batch.setBlock(new BlockPos(0, 64, 0), stone);
        batch.setBlock(new BlockPos(50 * 16, 64, 50 * 16), stone);
        batch.setBlock(new BlockPos(10 * 16, 64, 10 * 16), stone);

        // Player proximity sorting: chunk (10, 10) should be first, then (0, 0), then (50, 50)
        List<ChunkWriteBatch.ChunkEdits> playerSorted = batch.getSortedChunkEdits(SortStrategy.PLAYER_PROXIMITY);
        assertEquals(3, playerSorted.size());
        assertEquals(10, playerSorted.get(0).getChunkX());
        assertEquals(10, playerSorted.get(0).getChunkZ());
        assertEquals(0, playerSorted.get(1).getChunkX());
        assertEquals(0, playerSorted.get(1).getChunkZ());
        assertEquals(50, playerSorted.get(2).getChunkX());
        assertEquals(50, playerSorted.get(2).getChunkZ());

        // Centroid sorting: average coordinates (0 + 10 + 50)/3 = 20
        List<ChunkWriteBatch.ChunkEdits> centroidSorted = batch.getSortedChunkEdits(SortStrategy.CENTROID);
        assertEquals(3, centroidSorted.size());
        assertEquals(10, centroidSorted.get(0).getChunkX());
    }

    @Test
    @DisplayName("BatchRecoveryJournal serialization, persistence, and cleanup")
    void testBatchRecoveryJournalSerializationAndCleanup() throws Exception {
        Path tempDir = Files.createTempDirectory("qve_journal_test");
        try {
            MinecraftServer mockServer = org.mockito.Mockito.mock(MinecraftServer.class);
            org.mockito.Mockito.when(mockServer.getWorldPath(LevelResource.ROOT)).thenReturn(tempDir);

            ServerLevel mockLevel = org.mockito.Mockito.mock(ServerLevel.class);
            org.mockito.Mockito.when(mockLevel.getServer()).thenReturn(mockServer);
            org.mockito.Mockito.when(mockLevel.getMinBuildHeight()).thenReturn(-64);
            org.mockito.Mockito.when(mockLevel.getMaxBuildHeight()).thenReturn(320);
            org.mockito.Mockito.when(mockLevel.dimension()).thenReturn(Level.OVERWORLD);

            ChunkWriteBatch batch = new ChunkWriteBatch(mockLevel);
            BlockState stone = Blocks.STONE.defaultBlockState();
            batch.setBlock(new BlockPos(100, 64, 200), stone);

            // Execute validation and initialize batch
            batch.validate(WriteOptions.DEFAULT);

            // Save pending batch to journal
            int savedChunks = BatchRecoveryJournal.savePendingBatches(mockServer, List.of(batch));
            assertEquals(1, savedChunks);

            Path journalFile = tempDir.resolve("qve_recovery_queue.json");
            assertTrue(Files.isRegularFile(journalFile), "Journal file must exist on disk");
            String content = Files.readString(journalFile);
            assertTrue(content.contains("minecraft:overworld"));
            assertTrue(content.contains("minecraft:stone"));

            // Delete journal
            BatchRecoveryJournal.deleteJournal(mockServer);
            assertFalse(Files.exists(journalFile), "Journal file must be deleted");
        } finally {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("ChunkWriteContext strictly rejects out-of-bounds Y coordinates with IllegalArgumentException")
    void testChunkWriteContextOutOfBoundsThrows() {
        ChunkWriteContext ctx = new ChunkWriteContext(0, 0, -4, 19, true);

        // Valid section bounds: minSectionY = -4 (Y = -64), maxSectionY = 19 (Y = 319)
        assertDoesNotThrow(() -> ctx.setBlock(0, -64, 0, 1));
        assertDoesNotThrow(() -> ctx.setBlock(0, 319, 0, 1));

        // Out of bounds: Y = -65, Y = 320
        assertThrows(IllegalArgumentException.class, () -> ctx.setBlock(0, -65, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> ctx.setBlock(0, 320, 0, 1));

        // Section out of bounds: -5, 20
        VoxelSection dummy = new VoxelSection();
        assertThrows(IllegalArgumentException.class, () -> ctx.setSection(-5, dummy));
        assertThrows(IllegalArgumentException.class, () -> ctx.setSection(20, dummy));
    }

    @Test
    @DisplayName("VoxelWriteAPI rejects out-of-bounds Y coordinates with FAIL_INVALID_COORDINATES")
    void testVoxelWriteApiOutOfBoundsRejection() throws Exception {
        ServerLevel mockLevel = org.mockito.Mockito.mock(ServerLevel.class);
        org.mockito.Mockito.when(mockLevel.getMinBuildHeight()).thenReturn(-64);
        org.mockito.Mockito.when(mockLevel.getMaxBuildHeight()).thenReturn(320);
        org.mockito.Mockito.when(mockLevel.dimension()).thenReturn(Level.OVERWORLD);

        BlockState stone = Blocks.STONE.defaultBlockState();

        // Below min build height
        WriteResult resLow = com.pixel.qve.neoforge.api.VoxelWriteAPI.setBlockUnifiedAsync(
                mockLevel, new BlockPos(0, -65, 0), stone
        ).get(5, TimeUnit.SECONDS);
        assertEquals(WriteStatus.FAIL_INVALID_COORDINATES, resLow.status());
        assertFalse(resLow.isSuccess());

        // Above max build height
        WriteResult resHigh = com.pixel.qve.neoforge.api.VoxelWriteAPI.setBlockDirectAsync(
                mockLevel, new BlockPos(0, 320, 0), stone, null
        ).get(5, TimeUnit.SECONDS);
        assertEquals(WriteStatus.FAIL_INVALID_COORDINATES, resHigh.status());
        assertFalse(resHigh.isSuccess());
    }

    @Test
    @DisplayName("WriteResult with FAIL_VERIFICATION_MISMATCH correctly reports failure and metadata")
    void testVerificationMismatchHandling() {
        WriteResult res = WriteResult.failure(WriteStatus.FAIL_VERIFICATION_MISMATCH, 5, 5, "Verification mismatch")
                .withVerification(false, "minecraft:stone");

        assertFalse(res.isSuccess());
        assertEquals(WriteStatus.FAIL_VERIFICATION_MISMATCH, res.status());
        assertFalse(res.isVerified());
        assertEquals("minecraft:stone", res.verifiedBlock());
    }

    @Test
    @DisplayName("BatchWriteResult tracks and aggregates FAIL_VERIFICATION_MISMATCH across multiple chunks")
    void testBatchWriteVerificationMismatchAggregation() {
        WriteResult okRes = WriteResult.successDisk(0, 0, 1000L, 512, 2, false).withVerification(true, "minecraft:stone");
        WriteResult failRes = WriteResult.failure(WriteStatus.FAIL_VERIFICATION_MISMATCH, 1, 1, "Verification mismatch")
                .withVerification(false, "mismatch");

        BatchWriteResult batchRes = new BatchWriteResult(
                2, 1, 1, 200, 0, 2, 2000L, List.of(okRes, failRes)
        );

        assertFalse(batchRes.isAllSuccessful());
        assertEquals(1, batchRes.totalSucceeded());
        assertEquals(1, batchRes.totalFailed());
        assertEquals(WriteStatus.FAIL_VERIFICATION_MISMATCH, batchRes.results().get(1).status());
        assertFalse(batchRes.results().get(1).isVerified());
    }

    @Test
    @DisplayName("Verify MinecraftRegionFileBridge handles null level or uninitialized storage safely")
    void testRegionFileBridgeNullSafety() {
        assertFalse(com.pixel.qve.neoforge.bridge.writer.MinecraftRegionFileBridge.evictAndFlushRegion(null, 0, 0));
        assertFalse(com.pixel.qve.neoforge.bridge.writer.MinecraftRegionFileBridge.evictAndFlushRegions(null, List.of(0L)));

        ServerLevel mockLevel = org.mockito.Mockito.mock(ServerLevel.class);
        assertFalse(com.pixel.qve.neoforge.bridge.writer.MinecraftRegionFileBridge.evictAndFlushRegion(mockLevel, 0, 0));
    }

    @Test
    @DisplayName("ChunkExclusivityGuard detects in-memory residency via ChunkMap visible chunks")
    void testChunkExclusivityGuardChunkMapVisibleResidency() {
        ServerLevel mockLevel = org.mockito.Mockito.mock(ServerLevel.class);
        ServerChunkCache mockScc = org.mockito.Mockito.mock(ServerChunkCache.class);
        net.minecraft.server.level.ChunkMap mockChunkMap = org.mockito.Mockito.mock(net.minecraft.server.level.ChunkMap.class);
        net.minecraft.server.level.ChunkHolder mockHolder = org.mockito.Mockito.mock(net.minecraft.server.level.ChunkHolder.class);

        org.mockito.Mockito.when(mockLevel.getChunkSource()).thenReturn(mockScc);
        org.mockito.Mockito.when(mockLevel.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        org.mockito.Mockito.when(mockScc.hasChunk(10, 20)).thenReturn(false);
        org.mockito.Mockito.when(mockScc.getChunkNow(10, 20)).thenReturn(null);

        // Reflectively set or verify scc.chunkMap
        try {
            java.lang.reflect.Field cmField = ServerChunkCache.class.getDeclaredField("chunkMap");
            cmField.setAccessible(true);
            cmField.set(mockScc, mockChunkMap);
        } catch (Exception e) {
            // If field cannot be set directly on mock, test fallback
        }

        long posLong = ChunkPos.asLong(10, 20);
        net.minecraft.world.level.chunk.LevelChunk mockLevelChunk = org.mockito.Mockito.mock(net.minecraft.world.level.chunk.LevelChunk.class);
        org.mockito.Mockito.when(mockHolder.getTickingChunk()).thenReturn(mockLevelChunk);
        org.mockito.Mockito.when(mockChunkMap.getVisibleChunkIfPresent(posLong)).thenReturn(mockHolder);

        // When visible in ChunkMap with an active LevelChunk, isChunkLoadedInRam MUST return true
        assertTrue(ChunkExclusivityGuard.isChunkLoadedInRam(mockLevel, 10, 20));
        assertFalse(ChunkExclusivityGuard.isSafeForDirectDiskWrite(mockLevel, 10, 20));
        assertThrows(ChunkExclusivityGuard.ChunkLoadedInRamException.class,
                () -> ChunkExclusivityGuard.assertSafeForDirectDiskWrite(mockLevel, 10, 20));

        // When holder has no active LevelChunk (e.g. border chunk), it must NOT be considered resident in RAM,
        // even if mockScc.hasChunk(10, 20) is true (e.g. border ticket in DistanceManager)
        org.mockito.Mockito.when(mockHolder.getTickingChunk()).thenReturn(null);
        org.mockito.Mockito.when(mockHolder.getChunkToSend()).thenReturn(null);
        org.mockito.Mockito.when(mockHolder.getFullChunkFuture())
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(net.minecraft.server.level.ChunkHolder.UNLOADED_LEVEL_CHUNK));
        org.mockito.Mockito.when(mockScc.hasChunk(10, 20)).thenReturn(true);
        org.mockito.Mockito.when(mockScc.getChunkNow(10, 20)).thenReturn(null);
        assertFalse(ChunkExclusivityGuard.isChunkLoadedInRam(mockLevel, 10, 20));
        assertTrue(ChunkExclusivityGuard.isSafeForDirectDiskWrite(mockLevel, 10, 20));
    }

    @Test
    @DisplayName("ChunkWriteBatch.fill() promotes fully enclosed sections to homogeneous VoxelSection in O(1)")
    void testChunkAlignedFillAndWholeSectionPromotion() {
        ServerLevel mockLevel = org.mockito.Mockito.mock(ServerLevel.class);
        org.mockito.Mockito.when(mockLevel.getMinBuildHeight()).thenReturn(-64);
        org.mockito.Mockito.when(mockLevel.getMaxBuildHeight()).thenReturn(320);

        BlockState stoneState = Blocks.STONE.defaultBlockState();

        ChunkWriteBatch batch = new ChunkWriteBatch(mockLevel);

        // Fill an exact 16x16x16 chunk section: chunk (2, 3), section Y=0 (world Y: 0..15)
        batch.fill(32, 0, 48, 47, 15, 63, stoneState);

        assertEquals(4096, batch.getTotalBlockCount());
        assertEquals(1, batch.getAffectedChunkCount());

        ChunkWriteBatch.ChunkEdits edits = batch.getChunkEdits(2, 3);
        assertNotNull(edits);
        // Fully enclosed section must be promoted to wholeSections, with ZERO individual mutations
        assertEquals(1, edits.getWholeSections().size());
        assertTrue(edits.getWholeSections().containsKey(0));
        assertTrue(edits.getWholeSections().get(0).isHomogeneous());
        assertEquals(0, edits.getMutations().size());
    }
}
