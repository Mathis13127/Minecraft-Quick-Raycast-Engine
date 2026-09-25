package com.pixel.qve.neoforge.test;

import com.pixel.qve.mca.writer.WriteOptions;
import com.pixel.qve.mca.writer.WriteOptions.CreationPolicy;
import com.pixel.qve.mca.writer.WriteOptions.ExecutionPolicy;
import com.pixel.qve.neoforge.api.BatchWriteResult;
import com.pixel.qve.neoforge.api.ChunkWriteBatch;
import com.pixel.qve.neoforge.api.WriteResult;
import com.pixel.qve.neoforge.api.WriteStatus;
import com.pixel.qve.neoforge.api.event.ChunkPostDirectWriteEvent;
import com.pixel.qve.neoforge.api.event.ChunkPreDirectWriteEvent;
import com.pixel.qve.neoforge.bridge.writer.ChunkExclusivityGuard;
import com.pixel.qve.neoforge.bridge.writer.ChunkWriteContext;
import com.pixel.qve.world.VoxelSection;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

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
        org.mockito.Mockito.when(mockCache.hasChunk(5, 5)).thenReturn(true);
        // Chunk (6, 6) is not loaded
        org.mockito.Mockito.when(mockCache.hasChunk(6, 6)).thenReturn(false);

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
        org.mockito.Mockito.when(mockCache.hasChunk(999, 999)).thenReturn(false);

        ChunkWriteBatch batch = new ChunkWriteBatch(mockLevel);
        BlockState stone = Blocks.STONE.defaultBlockState();
        batch.setBlock(new BlockPos(999 * 16, 64, 999 * 16), stone);

        // Under EXISTING_ONLY without coordinator having chunk -> fails fast with FAIL_CHUNK_NOT_FOUND
        Optional<WriteResult> failOpt = batch.validate(WriteOptions.EXISTING_ONLY);
        assertTrue(failOpt.isPresent(), "FAIL_IF_MISSING must reject missing chunk during pre-flight");
        assertEquals(WriteStatus.FAIL_CHUNK_NOT_FOUND, failOpt.get().status());
    }
}
