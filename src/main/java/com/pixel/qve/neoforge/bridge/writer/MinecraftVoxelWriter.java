package com.pixel.qve.neoforge.bridge.writer;

import com.pixel.qve.mca.writer.IChunkWriteContext;
import com.pixel.qve.mca.writer.McaRegionWriter;
import com.pixel.qve.mca.writer.McaWriteCoordinator;
import com.pixel.qve.mca.writer.PrimitiveMutationBuffer;
import com.pixel.qve.mca.writer.WriteOptions;
import com.pixel.qve.neoforge.api.WriteResult;
import com.pixel.qve.neoforge.api.WriteStatus;
import com.pixel.qve.neoforge.api.event.ChunkPostDirectWriteEvent;
import com.pixel.qve.neoforge.api.event.ChunkPreDirectWriteEvent;
import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelGrid;
import com.pixel.qve.world.VoxelChunkColumn;
import com.pixel.qve.world.VoxelSection;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import com.pixel.qve.mca.writer.VoxelDiskWriterThreadPool;
import com.pixel.qve.neoforge.api.ChunkWriteBatch;

/**
 * Dimension-level voxel write manager unifying live RAM block placement and offline Anvil (.mca) direct writes.
 */
public final class MinecraftVoxelWriter implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftVoxelWriter.class);

    private final Level level;
    private final Path regionDirectory;
    private final McaWriteCoordinator coordinator;

    /**
     * Constructs a MinecraftVoxelWriter for the given Level and region directory.
     *
     * @param level           Minecraft Level
     * @param regionDirectory Root directory containing .mca region files, or null if RAM only
     */
    public MinecraftVoxelWriter(Level level, Path regionDirectory) {
        this.level = Objects.requireNonNull(level, "Level cannot be null");
        this.regionDirectory = regionDirectory;

        int minSec = level.getMinSection();
        int maxSec = level.getMinSection() + level.getSectionsCount() - 1;

        if (regionDirectory != null) {
            this.coordinator = new McaWriteCoordinator(
                    regionDirectory,
                    MinecraftVoxelBridge.getBlockRegistry(),
                    minSec,
                    maxSec
            );
        } else {
            this.coordinator = null;
        }
    }

    /**
     * Resolves the Anvil region directory for a given Level.
     *
     * @param level Minecraft Level
     * @return Path to region directory, or null if unavailable
     */
    public static Path resolveRegionDirectory(Level level) {
        if (level instanceof ServerLevel serverLevel) {
            try {
                if (serverLevel.getServer() == null) {
                    return null;
                }
                Path rootPath = serverLevel.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
                Path dimFolder = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(serverLevel.dimension(), rootPath);
                Path regionDir = dimFolder.resolve("region");
                if (!Files.exists(regionDir)) {
                    Files.createDirectories(regionDir);
                }
                return regionDir;
            } catch (Exception e) {
                LOGGER.error("Failed to resolve region directory for dimension {}: {}",
                        level.dimension().location(), e.getMessage(), e);
            }
        }
        return null;
    }

    /**
     * Modifies a block without concern for whether its chunk is currently loaded in RAM or unloaded on disk.
     *
     * @param pos            Block position
     * @param state          Target BlockState
     * @param flags          Block update flags
     * @param blockEntityNbt Optional BlockEntity NBT compound
     * @return CompletableFuture completing with WriteResult
     */
    public CompletableFuture<WriteResult> setBlockUnifiedAsync(BlockPos pos, BlockState state, int flags, CompoundTag blockEntityNbt) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;

        if (ChunkExclusivityGuard.isChunkLoadedInRam(level, cx, cz)) {
            return setBlockRamAsync(pos, state, flags, blockEntityNbt);
        } else if (ChunkExclusivityGuard.isRegionActiveInRam(level, cx >> 5, cz >> 5)) {
            ChunkWriteBatch.ChunkEdits edits = new ChunkWriteBatch.ChunkEdits(cx, cz);
            byte[] rawNbt = null;
            if (blockEntityNbt != null) {
                try {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    net.minecraft.nbt.NbtIo.write(blockEntityNbt, new java.io.DataOutputStream(baos));
                    rawNbt = baos.toByteArray();
                } catch (Exception e) {
                    LOGGER.warn("Failed to serialize BE NBT for pos {}: {}", pos, e.getMessage());
                }
            }
            edits.addMutation(new ChunkWriteBatch.BlockMutation(
                    pos.getX(), pos.getY(), pos.getZ(),
                    MinecraftVoxelBridge.getBlockId(state),
                    state,
                    rawNbt,
                    blockEntityNbt,
                    -1,
                    null
            ));
            DeferredChunkQueue.enqueue(level, edits);
            return CompletableFuture.completedFuture(WriteResult.successDeferred(cx, cz, 0L));
        } else {
            return setBlockDirectAsync(pos, state, blockEntityNbt);
        }
    }

    /**
     * Modifies or creates a chunk without concern for whether it is currently loaded in RAM or unloaded on disk.
     * If the chunk is unloaded within an active hybrid region, mutations are queued in {@link DeferredChunkQueue}
     * to prevent Anvil header collisions.
     *
     * @param chunkX   World chunk X
     * @param chunkZ   World chunk Z
     * @param modifier Consumer modifying the chunk context
     * @return CompletableFuture completing with WriteResult
     */
    public CompletableFuture<WriteResult> modifyChunkUnifiedAsync(int chunkX, int chunkZ, Consumer<IChunkWriteContext> modifier) {
        if (ChunkExclusivityGuard.isChunkLoadedInRam(level, chunkX, chunkZ)) {
            return modifyChunkRamAsync(chunkX, chunkZ, modifier);
        } else if (ChunkExclusivityGuard.isRegionActiveInRam(level, chunkX >> 5, chunkZ >> 5)) {
            long t0 = System.nanoTime();
            try {
                ChunkWriteContext context = prepareChunkContext(chunkX, chunkZ, null, null, modifier);
                ChunkWriteBatch.ChunkEdits edits = new ChunkWriteBatch.ChunkEdits(chunkX, chunkZ);
                int minSec = level.getMinSection();
                int mask = context.getModifiedSectionMask();
                for (Map.Entry<Integer, VoxelSection> secEntry : context.getSections().entrySet()) {
                    int sy = secEntry.getKey();
                    if ((mask & (1 << (sy - minSec))) != 0) {
                        edits.setSection(sy, secEntry.getValue());
                    }
                }
                for (Map.Entry<Long, byte[]> beEntry : context.getBlockEntities().entrySet()) {
                    long key = beEntry.getKey();
                    int lx = (int) (key & 0xF);
                    int lz = (int) ((key >> 4) & 0xF);
                    int wy = (int) ((short) (key >> 8));
                    byte[] rawNbt = beEntry.getValue();
                    CompoundTag tag = null;
                    if (rawNbt != null) {
                        try {
                            tag = net.minecraft.nbt.NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(rawNbt)));
                        } catch (Exception e) {
                            LOGGER.warn("Failed to parse BE NBT in modifyChunkUnifiedAsync for ({}, {}): {}", chunkX, chunkZ, e.getMessage());
                        }
                    }
                    int bId = context.getBlock(lx, wy, lz);
                    BlockState bs = MinecraftVoxelBridge.getBlockState(bId);
                    edits.addMutation(new ChunkWriteBatch.BlockMutation(
                            (chunkX << 4) | lx, wy, (chunkZ << 4) | lz,
                            bId, bs, rawNbt, tag, -1, null
                    ));
                }
                DeferredChunkQueue.enqueue(level, edits);
                return CompletableFuture.completedFuture(WriteResult.successDeferred(chunkX, chunkZ, System.nanoTime() - t0));
            } catch (Throwable t) {
                LOGGER.error("Failed to apply modifier to deferred chunk ({}, {}): {}", chunkX, chunkZ, t.getMessage(), t);
                return CompletableFuture.completedFuture(WriteResult.failure(WriteStatus.FAIL_IO_ERROR, chunkX, chunkZ, t.getMessage()));
            }
        } else {
            return writeChunkDirectAsync(chunkX, chunkZ, modifier);
        }
    }

    /**
     * Sets a single block directly in an UNLOADED chunk on disk.
     * Strictly fails if the chunk is currently loaded in RAM.
     *
     * @param pos            Block position
     * @param state          Target BlockState
     * @param blockEntityNbt Optional BlockEntity NBT
     * @return CompletableFuture completing with WriteResult
     */
    public CompletableFuture<WriteResult> setBlockDirectAsync(BlockPos pos, BlockState state, CompoundTag blockEntityNbt) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        int blockId = MinecraftVoxelBridge.getBlockId(state);
        String expectedName = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

        return writeChunkDirectAsync(cx, cz, ctx -> {
            ctx.setBlock(pos.getX() & 15, pos.getY(), pos.getZ() & 15, blockId);
            if (blockEntityNbt != null) {
                // Serialize CompoundTag to raw byte array
                try {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    net.minecraft.nbt.NbtIo.write(blockEntityNbt, new java.io.DataOutputStream(baos));
                    ctx.setBlockEntityRaw(pos.getX() & 15, pos.getY(), pos.getZ() & 15, baos.toByteArray());
                } catch (Exception e) {
                    LOGGER.error("Failed to serialize BlockEntity NBT for pos {}: {}", pos, e.getMessage(), e);
                }
            }
        }).thenApply(res -> {
            if (res.isSuccess()) {
                boolean verified = verifyPhysicalDiskWrite(cx, cz, pos.getX() & 15, pos.getY(), pos.getZ() & 15, blockId);
                if (verified) {
                    return res.withVerification(true, expectedName);
                } else {
                    MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
                    int readId = (grid != null) ? grid.getBlockId(pos.getX(), pos.getY(), pos.getZ()) : -1;
                    String readName = MinecraftVoxelBridge.getBlockRegistry().getName(readId);
                    return WriteResult.failure(WriteStatus.FAIL_VERIFICATION_MISMATCH, cx, cz,
                            "Physical disk verification failed at " + pos + ": expected " + expectedName + ", found " + (readName != null ? readName : "unreadable"))
                            .withVerification(false, readName != null ? readName : "minecraft:air");
                }
            }
            return res;
        });
    }

    public McaWriteCoordinator getCoordinator() {
        return coordinator;
    }

    /**
     * Writes or modifies an UNLOADED chunk directly to an Anvil region file (.mca) on disk.
     * Strictly fails if the chunk is currently loaded in RAM.
     *
     * @param chunkX   World chunk X
     * @param chunkZ   World chunk Z
     * @param modifier Consumer modifying the chunk context
     * @return CompletableFuture completing with WriteResult
     */
    public CompletableFuture<WriteResult> writeChunkDirectAsync(int chunkX, int chunkZ, Consumer<IChunkWriteContext> modifier) {
        return writeChunkDirectAsync(chunkX, chunkZ, modifier, WriteOptions.STRICT);
    }

    /**
     * Writes or modifies an UNLOADED chunk directly to an Anvil region file (.mca) on disk using specified WriteOptions.
     *
     * @param chunkX   World chunk X
     * @param chunkZ   World chunk Z
     * @param modifier Consumer modifying the chunk context
     * @param options  WriteOptions governing execution and creation policies
     * @return CompletableFuture completing with WriteResult
     */
    public CompletableFuture<WriteResult> writeChunkDirectAsync(int chunkX, int chunkZ, Consumer<IChunkWriteContext> modifier, WriteOptions options) {
        long startTime = System.nanoTime();
        WriteOptions opts = (options != null) ? options : WriteOptions.STRICT;

        // 1. Assert mutual exclusion if strict
        if (opts.isStrict()) {
            try {
                ChunkExclusivityGuard.assertSafeForDirectDiskWrite(level, chunkX, chunkZ);
            } catch (ChunkExclusivityGuard.ChunkLoadedInRamException e) {
                return CompletableFuture.completedFuture(
                        WriteResult.failure(WriteStatus.FAIL_CHUNK_LOADED_IN_RAM, chunkX, chunkZ, e.getMessage())
                );
            }
        } else if (ChunkExclusivityGuard.isChunkLoadedInRam(level, chunkX, chunkZ)) {
            // Late RAM check in UNIFIED mode: chunk is resident in RAM
            return CompletableFuture.completedFuture(
                    WriteResult.failure(WriteStatus.FAIL_CHUNK_LOADED_IN_RAM, chunkX, chunkZ,
                            "Chunk (" + chunkX + ", " + chunkZ + ") is currently loaded in RAM (use unified RAM routing)")
            );
        }

        if (coordinator == null) {
            return CompletableFuture.completedFuture(
                    WriteResult.failure(WriteStatus.FAIL_IO_ERROR, chunkX, chunkZ, "Region directory unavailable for dimension " + level.dimension().location())
            );
        }

        // 2. Check CreationPolicy
        if (opts.shouldFailIfMissing() && !coordinator.hasChunk(chunkX, chunkZ)) {
            return CompletableFuture.completedFuture(
                    WriteResult.failure(WriteStatus.FAIL_CHUNK_NOT_FOUND, chunkX, chunkZ,
                            "Chunk (" + chunkX + ", " + chunkZ + ") does not exist on disk and FAIL_IF_MISSING policy is active")
            );
        }

        // 3. Prepare Context
        ChunkWriteContext context;
        try {
            context = prepareChunkContext(chunkX, chunkZ, modifier);
        } catch (Throwable t) {
            LOGGER.error("Exception during chunk modification callback for chunk ({}, {}): {}",
                    chunkX, chunkZ, t.getMessage(), t);
            return CompletableFuture.completedFuture(
                    WriteResult.failure(WriteStatus.FAIL_IO_ERROR, chunkX, chunkZ, t.getMessage())
            );
        }

        // 4. Fire Pre-write Event
        ChunkPreDirectWriteEvent preEvent = new ChunkPreDirectWriteEvent(level, chunkX, chunkZ, context);
        if (NeoForge.EVENT_BUS.post(preEvent).isCanceled()) {
            return CompletableFuture.completedFuture(
                    WriteResult.failure(WriteStatus.FAIL_CANCELLED_BY_EVENT, chunkX, chunkZ, "Cancelled by ChunkPreDirectWriteEvent")
            );
        }

        // 5. Evict Minecraft's cached RegionFile handle to avoid stale header desynchronization
        if (level instanceof ServerLevel sl) {
            MinecraftRegionFileBridge.evictAndFlushRegion(sl, chunkX >> 5, chunkZ >> 5);
        }

        // 6. Submit write to worker pool
        MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
        return coordinator.writeChunkAsync(chunkX, chunkZ, context.getSections(), context.getBlockEntities())
                .thenApply(metrics -> {
                    long duration = System.nanoTime() - startTime;
                    WriteResult result = WriteResult.successDisk(
                            chunkX, chunkZ, duration,
                            metrics.compressedBytes(),
                            metrics.sectorOffset(),
                            metrics.isRelocated()
                    );

                    // 6. Invalidate QVE Cache immediately so raycasts see new voxels
                    if (grid != null) {
                        grid.getCache().invalidateChunk(chunkX, chunkZ);
                    }

                    // 7. Fire Post-write Event
                    NeoForge.EVENT_BUS.post(new ChunkPostDirectWriteEvent(level, chunkX, chunkZ, result, context.getModifiedSectionMask()));

                    return result;
                }).exceptionally(ex -> {
                    LOGGER.error("Failed to write chunk ({}, {}) to MCA disk: {}", chunkX, chunkZ, ex.getMessage(), ex);
                    return WriteResult.failure(WriteStatus.FAIL_IO_ERROR, chunkX, chunkZ, ex.getMessage());
                });
    }

    /**
     * Reads the physical Anvil (.mca) file from disk and verifies that the block at the specified
     * position matches the expected block ID.
     *
     * @param chunkX          World chunk X
     * @param chunkZ          World chunk Z
     * @param localX          Local block X [0..15]
     * @param worldY          World block Y
     * @param localZ          Local block Z [0..15]
     * @param expectedBlockId Expected Block ID in BlockIdRegistry
     * @return True if block physically on disk matches expectedBlockId
     */
    public boolean verifyPhysicalDiskWrite(int chunkX, int chunkZ, int localX, int worldY, int localZ, int expectedBlockId) {
        if (coordinator != null) {
            return coordinator.verifyVoxel(chunkX, chunkZ, localX, worldY, localZ, expectedBlockId);
        }
        if (regionDirectory == null) {
            return false;
        }
        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        Path mcaFile = regionDirectory.resolve("r." + rx + "." + rz + ".mca");
        if (!Files.isRegularFile(mcaFile)) {
            return expectedBlockId == BlockIdRegistry.AIR_ID;
        }

        try (com.pixel.qve.mca.McaRegionReader directReader = new com.pixel.qve.mca.McaRegionReader(mcaFile, MinecraftVoxelBridge.getBlockRegistry())) {
            int localCx = chunkX & 31;
            int localCz = chunkZ & 31;
            java.nio.ByteBuffer payload = directReader.decompressChunk(localCx, localCz);
            if (payload == null) {
                return expectedBlockId == BlockIdRegistry.AIR_ID;
            }
            return com.pixel.qve.mca.writer.FastChunkVerifier.verifyChunkVoxel(
                    payload, chunkX, chunkZ, localX, worldY, localZ, expectedBlockId, MinecraftVoxelBridge.getBlockRegistry());
        } catch (Exception e) {
            LOGGER.warn("Physical disk verification failed for chunk ({}, {}): {}", chunkX, chunkZ, e.getMessage());
            return false;
        }
    }

    /**
     * Prepares a ChunkWriteContext pre-loading existing voxel data from disk if the chunk already exists.
     */
    public ChunkWriteContext prepareChunkContext(int chunkX, int chunkZ, Consumer<IChunkWriteContext> modifier) {
        return prepareChunkContext(chunkX, chunkZ, null, null, modifier);
    }

    /**
     * Prepares a ChunkWriteContext pre-loading only the required sections from disk or cache.
     *
     * @param chunkX              World chunk X
     * @param chunkZ              World chunk Z
     * @param requiredSectionYs   Set of section Y levels to pre-load (null to load all non-empty sections)
     * @param sharedRegionReader  Optional open McaRegionReader to reuse (null to open ad-hoc)
     * @param modifier            Modifier callback
     * @return Prepared ChunkWriteContext
     */
    public ChunkWriteContext prepareChunkContext(int chunkX, int chunkZ,
                                                 Set<Integer> requiredSectionYs,
                                                 com.pixel.qve.mca.McaRegionReader sharedRegionReader,
                                                 Consumer<IChunkWriteContext> modifier) {
        MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
        VoxelChunkColumn existingColumn = (grid != null) ? grid.getColumn(chunkX, chunkZ) : null;
        boolean hasOnDisk = false;
        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        int localCx = chunkX & 31;
        int localCz = chunkZ & 31;
        Path mcaFile = (regionDirectory != null) ? regionDirectory.resolve("r." + rx + "." + rz + ".mca") : null;

        com.pixel.qve.mca.McaRegionReader directReader = sharedRegionReader;
        boolean mustCloseReader = false;

        if (existingColumn == null) {
            if (directReader != null) {
                hasOnDisk = directReader.hasChunk(localCx, localCz);
            } else if (mcaFile != null && Files.isRegularFile(mcaFile)) {
                try {
                    if (Files.size(mcaFile) >= 8192) {
                        directReader = new com.pixel.qve.mca.McaRegionReader(mcaFile, MinecraftVoxelBridge.getBlockRegistry());
                        mustCloseReader = true;
                        hasOnDisk = directReader.hasChunk(localCx, localCz);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to check existing chunk ({}, {}) in {}: {}", chunkX, chunkZ, mcaFile, e.getMessage());
                }
            }
        }

        boolean isNew = (existingColumn == null && !hasOnDisk);
        int minSec = level.getMinSection();
        int maxSec = level.getMinSection() + level.getSectionsCount() - 1;
        ChunkWriteContext context = new ChunkWriteContext(chunkX, chunkZ, minSec, maxSec, isNew);

        try {
            if (existingColumn != null) {
                for (int sy = minSec; sy <= maxSec; sy++) {
                    if (requiredSectionYs != null && !requiredSectionYs.contains(sy)) {
                        continue;
                    }
                    VoxelSection sec = existingColumn.getSection(sy);
                    if (sec != null && !sec.isEmpty()) {
                        context.setSection(sy, sec.copy());
                    }
                }
            } else if (hasOnDisk && directReader != null) {
                try {
                    java.util.function.IntPredicate filter = (requiredSectionYs != null) ? requiredSectionYs::contains : null;
                    directReader.readChunk(localCx, localCz, filter, (secY, sec) -> {
                        if (sec != null && !sec.isEmpty()) {
                            context.setSection(secY, sec.copy());
                        }
                    });
                } catch (Exception e) {
                    LOGGER.warn("Failed to direct-read existing chunk ({}, {}) from {}: {}",
                            chunkX, chunkZ, mcaFile, e.getMessage());
                }
            }
        } finally {
            if (mustCloseReader && directReader != null) {
                try {
                    directReader.close();
                } catch (Exception ignored) {
                }
            }
        }

        if (modifier != null) {
            modifier.accept(context);
        }
        return context;
    }

    /**
     * Executes a batch of chunk writes belonging to a single region file (.mca) with a single header synchronization.
     *
     * @param rx        Region X
     * @param rz        Region Z
     * @param editsList List of ChunkEdits targeting this region
     * @param options   WriteOptions governing execution
     * @return CompletableFuture completing with list of WriteResults
     */
    public CompletableFuture<List<WriteResult>> writeRegionBatchAsync(int rx, int rz, List<ChunkWriteBatch.ChunkEdits> editsList, WriteOptions options) {
        if (editsList == null || editsList.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        WriteOptions opts = (options != null) ? options : WriteOptions.DEFAULT;
        if (coordinator == null) {
            List<WriteResult> errs = new ArrayList<>(editsList.size());
            for (ChunkWriteBatch.ChunkEdits e : editsList) {
                errs.add(WriteResult.failure(WriteStatus.FAIL_IO_ERROR, e.getChunkX(), e.getChunkZ(), "Region directory unavailable"));
            }
            return CompletableFuture.completedFuture(errs);
        }

        long startTime = System.nanoTime();

        return VoxelDiskWriterThreadPool.submit(() -> {
            List<McaWriteCoordinator.ChunkWriteTask> tasks = new ArrayList<>(editsList.size());
            List<IChunkWriteContext> contexts = new ArrayList<>(editsList.size());
            List<ChunkWriteBatch.ChunkEdits> diskEdits = new ArrayList<>(editsList.size());
            List<CompletableFuture<WriteResult>> ramFutures = new ArrayList<>();

            // 1. Evict Minecraft's cached RegionFile handle in background pool to prevent server thread blocking if still cached
            if (level instanceof ServerLevel sl) {
                if (MinecraftRegionFileBridge.isRegionCached(sl, rx, rz)) {
                    MinecraftRegionFileBridge.evictAndFlushRegion(sl, rx, rz);
                }
            }

            for (ChunkWriteBatch.ChunkEdits edits : editsList) {
                int cx = edits.getChunkX();
                int cz = edits.getChunkZ();

                // Late-binding RAM check
                if (ChunkExclusivityGuard.isChunkLoadedInRam(level, cx, cz)) {
                    if (opts.isStrict()) {
                        WriteResult err = WriteResult.failure(WriteStatus.FAIL_CHUNK_LOADED_IN_RAM, cx, cz,
                                "Chunk (" + cx + ", " + cz + ") became resident in RAM");
                        ramFutures.add(CompletableFuture.completedFuture(err));
                    } else {
                        ramFutures.add(applyChunkEditsToRam(edits));
                    }
                    continue;
                }

                // Check CreationPolicy
                if (opts.shouldFailIfMissing() && !coordinator.hasChunk(cx, cz)) {
                    WriteResult err = WriteResult.failure(WriteStatus.FAIL_CHUNK_NOT_FOUND, cx, cz,
                            "Chunk (" + cx + ", " + cz + ") does not exist on disk and FAIL_IF_MISSING policy is active");
                    ramFutures.add(CompletableFuture.completedFuture(err));
                    continue;
                }

                // Prepare VoxelMutations and BlockEntities for single-pass in-place patching
                PrimitiveMutationBuffer mutationBuf = edits.getMutationBuffer();
                Map<Long, byte[]> blockEntities = null;
                if (mutationBuf != null && mutationBuf.hasRawNbts()) {
                    for (int mi = 0, sz = mutationBuf.size(); mi < sz; mi++) {
                        byte[] raw = mutationBuf.rawNbt(mi);
                        if (raw != null) {
                            if (blockEntities == null) {
                                blockEntities = new HashMap<>();
                            }
                            int lx = mutationBuf.localX(mi);
                            int ly = mutationBuf.worldY(mi);
                            int lz = mutationBuf.localZ(mi);
                            long key = (((long) (ly & 0xFFFF)) << 8) | (((long) (lz & 0xF)) << 4) | ((long) (lx & 0xF));
                            blockEntities.put(key, raw);
                        }
                    }
                }

                int minSec = level.getMinSection();
                int maxSec = level.getMinSection() + level.getSectionsCount() - 1;
                BatchChunkWriteContext context = new BatchChunkWriteContext(cx, cz, minSec, maxSec, edits, blockEntities);

                // Fire pre-write event
                ChunkPreDirectWriteEvent preEvent = new ChunkPreDirectWriteEvent(level, cx, cz, context);
                if (NeoForge.EVENT_BUS.post(preEvent).isCanceled()) {
                    ramFutures.add(CompletableFuture.completedFuture(
                            WriteResult.failure(WriteStatus.FAIL_CANCELLED_BY_EVENT, cx, cz, "Cancelled by ChunkPreDirectWriteEvent")
                    ));
                    continue;
                }

                // Prepare single-pass voxel verification target if mutations present
                McaWriteCoordinator.VoxelCheck voxelCheck = null;
                if (mutationBuf != null && !mutationBuf.isEmpty()) {
                    voxelCheck = new McaWriteCoordinator.VoxelCheck(
                            mutationBuf.localX(0),
                            mutationBuf.worldY(0),
                            mutationBuf.localZ(0),
                            mutationBuf.targetBlockId(0)
                    );
                } else if (!edits.getMutations().isEmpty()) {
                    ChunkWriteBatch.BlockMutation firstMut = edits.getMutations().get(0);
                    voxelCheck = new McaWriteCoordinator.VoxelCheck(
                            firstMut.worldX() & 15,
                            firstMut.worldY(),
                            firstMut.worldZ() & 15,
                            firstMut.targetBlockId()
                    );
                }

                contexts.add(context);
                tasks.add(new McaWriteCoordinator.ChunkWriteTask(
                        cx, cz, edits.getWholeSections(), mutationBuf, blockEntities, voxelCheck
                ));
                diskEdits.add(edits);
            }

            if (tasks.isEmpty()) {
                List<WriteResult> res = new ArrayList<>();
                for (CompletableFuture<WriteResult> rf : ramFutures) {
                    res.add(rf.join());
                }
                return res;
            }

            // Write and sync region batch on disk pool
            List<McaRegionWriter.WriteMetrics> metricsList = coordinator.writeRegionBatchSync(rx, rz, tasks);
            MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
            long duration = System.nanoTime() - startTime;
            List<WriteResult> results = new ArrayList<>(editsList.size());

            for (int t = 0; t < metricsList.size(); t++) {
                McaRegionWriter.WriteMetrics m = metricsList.get(t);
                IChunkWriteContext ctx = contexts.get(t);
                ChunkWriteBatch.ChunkEdits edits = diskEdits.get(t);
                int cx = ctx.getChunkX();
                int cz = ctx.getChunkZ();

                boolean verified = m.isVerified();
                String verifiedName = null;
                PrimitiveMutationBuffer pmb = edits.getMutationBuffer();
                if (pmb != null && !pmb.isEmpty()) {
                    int bId = pmb.targetBlockId(0);
                    BlockState bs = MinecraftVoxelBridge.getBlockState(bId);
                    verifiedName = (bs != null) ? bs.getBlock().getName().getString() : "id:" + bId;
                } else if (!edits.getMutations().isEmpty()) {
                    ChunkWriteBatch.BlockMutation firstMut = edits.getMutations().get(0);
                    verifiedName = (firstMut.targetState() != null)
                            ? firstMut.targetState().getBlock().getName().getString()
                            : "id:" + firstMut.targetBlockId();
                }

                WriteResult res;
                if (verified) {
                    res = WriteResult.successDisk(cx, cz, duration, m.compressedBytes(), m.sectorOffset(), m.isRelocated());
                    if (verifiedName != null) {
                        res = res.withVerification(true, verifiedName);
                    }
                } else {
                    String err = "Physical disk verification mismatch in chunk (" + cx + ", " + cz + "): expected " + verifiedName;
                    LOGGER.error(err);
                    res = WriteResult.failure(WriteStatus.FAIL_VERIFICATION_MISMATCH, cx, cz, err)
                            .withVerification(false, "mismatch");
                }

                if (grid != null) {
                    grid.getCache().invalidateChunk(cx, cz);
                }
                NeoForge.EVENT_BUS.post(new ChunkPostDirectWriteEvent(level, cx, cz, res, ctx.getModifiedSectionMask()));
                results.add(res);
            }

            // Add late RAM results
            for (CompletableFuture<WriteResult> rf : ramFutures) {
                results.add(rf.join());
            }

            return results;
        }).exceptionally(ex -> {
            LOGGER.error("Failed region batch write for r.{}.{}.mca: {}", rx, rz, ex.getMessage(), ex);
            List<WriteResult> errs = new ArrayList<>(editsList.size());
            for (ChunkWriteBatch.ChunkEdits e : editsList) {
                errs.add(WriteResult.failure(WriteStatus.FAIL_IO_ERROR, e.getChunkX(), e.getChunkZ(), ex.getMessage()));
            }
            return errs;
        });
    }

    /**
     * Applies a chunk's mutations directly to active RAM on the server thread.
     *
     * @param edits ChunkEdits instance
     * @return CompletableFuture completing with WriteResult
     */
    public CompletableFuture<WriteResult> applyChunkEditsToRam(ChunkWriteBatch.ChunkEdits edits) {
        long t0 = System.nanoTime();
        CompletableFuture<WriteResult> future = new CompletableFuture<>();
        Runnable task = () -> {
            List<ChunkEditsApplicator.AppliedRamMutation> applied = new ArrayList<>();
            try {
                LevelChunk lc = (level instanceof ServerLevel sl)
                        ? sl.getChunkSource().getChunkNow(edits.getChunkX(), edits.getChunkZ())
                        : level.getChunk(edits.getChunkX(), edits.getChunkZ());
                if (lc == null) {
                    LOGGER.warn("Chunk ({}, {}) was scheduled for RAM mutation but is no longer loaded in RAM. Falling back to direct disk write.",
                            edits.getChunkX(), edits.getChunkZ());
                    WriteResult diskRes = writeChunkDirectAsync(edits.getChunkX(), edits.getChunkZ(), ctx -> {
                        for (Map.Entry<Integer, VoxelSection> secEntry : edits.getWholeSections().entrySet()) {
                            ctx.setSection(secEntry.getKey(), secEntry.getValue());
                        }
                        PrimitiveMutationBuffer buf = edits.getMutationBuffer();
                        if (buf != null && !buf.isEmpty()) {
                            for (int mi = 0, sz = buf.size(); mi < sz; mi++) {
                                ctx.setBlock(buf.localX(mi), buf.worldY(mi), buf.localZ(mi), buf.targetBlockId(mi));
                            }
                        } else {
                            for (ChunkWriteBatch.BlockMutation m : edits.getMutations()) {
                                ctx.setBlock(m.worldX() & 15, m.worldY(), m.worldZ() & 15, MinecraftVoxelBridge.getBlockId(m.targetState()));
                            }
                        }
                    }).join();
                    future.complete(diskRes);
                    return;
                }
                if (level instanceof ServerLevel sl) {
                    ChunkEditsApplicator.applyToLiveChunk(sl, lc, edits, applied);
                } else {
                    ChunkEditsApplicator.applyToLoadingChunk(lc, edits);
                }
                long elapsed = System.nanoTime() - t0;
                future.complete(WriteResult.successRam(edits.getChunkX(), edits.getChunkZ(), elapsed));
            } catch (Throwable t) {
                LOGGER.error("Error applying chunk edits in RAM for ({}, {}), rolling back: {}",
                        edits.getChunkX(), edits.getChunkZ(), t.getMessage(), t);
                if (level instanceof ServerLevel sl) {
                    ChunkEditsApplicator.rollback(sl, applied);
                }
                future.complete(WriteResult.failure(WriteStatus.FAIL_IO_ERROR, edits.getChunkX(), edits.getChunkZ(), t.getMessage()));
            }
        };

        if (level instanceof ServerLevel sl) {
            if (Thread.currentThread() == sl.getServer().getRunningThread()) {
                task.run();
            } else {
                sl.getServer().execute(task);
            }
        } else {
            task.run();
        }
        return future;
    }

    /**
     * Sets a block in a RAM-loaded chunk on the server thread.
     */
    public CompletableFuture<WriteResult> setBlockRamAsync(BlockPos pos, BlockState state, int flags, CompoundTag blockEntityNbt) {
        long startTime = System.nanoTime();
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;

        CompletableFuture<WriteResult> future = new CompletableFuture<>();

        Runnable task = () -> {
            try {
                // Enforce flag 16 (UPDATE_KNOWN_SHAPE) to prevent Vanilla from force-loading adjacent chunk borders
                boolean placed = level.setBlock(pos, state, flags | 16);
                if (!placed) {
                    throw new IllegalStateException("Failed to place block in RAM at " + pos + " with state " + state);
                }
                if (blockEntityNbt != null) {
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be != null) {
                        be.loadWithComponents(blockEntityNbt, level.registryAccess());
                        be.setChanged();
                    }
                }
                LevelChunk lc = level.getChunk(cx, cz);
                if (lc != null) {
                    lc.setUnsaved(true);
                }

                long duration = System.nanoTime() - startTime;

                // Cache invalidation and read-back verification
                MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
                if (grid != null) {
                    grid.getCache().invalidateChunk(cx, cz);
                }

                BlockState readState = level.getBlockState(pos);
                String expectedName = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                String actualName = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(readState.getBlock()).toString();
                boolean verified = expectedName.equals(actualName);

                future.complete(WriteResult.successRam(cx, cz, duration).withVerification(verified, actualName));
            } catch (Throwable t) {
                LOGGER.error("Error setting block in RAM at {}: {}", pos, t.getMessage(), t);
                future.complete(WriteResult.failure(WriteStatus.FAIL_IO_ERROR, cx, cz, t.getMessage()));
            }
        };

        if (level instanceof ServerLevel serverLevel) {
            if (Thread.currentThread() == serverLevel.getServer().getRunningThread()) {
                task.run();
            } else {
                serverLevel.getServer().execute(task);
            }
        } else {
            task.run();
        }

        return future;
    }

    private CompletableFuture<WriteResult> modifyChunkRamAsync(int chunkX, int chunkZ, Consumer<IChunkWriteContext> modifier) {
        long startTime = System.nanoTime();
        CompletableFuture<WriteResult> future = new CompletableFuture<>();

        Runnable task = () -> {
            try {
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                int minSec = level.getMinSection();
                int maxSec = level.getMinSection() + level.getSectionsCount() - 1;
                ChunkWriteContext context = new ChunkWriteContext(chunkX, chunkZ, minSec, maxSec, false);

                modifier.accept(context);

                // Apply modified blocks to live LevelChunk using reusable MutableBlockPos
                BlockPos.MutableBlockPos mutPos = new BlockPos.MutableBlockPos();
                for (Map.Entry<Integer, VoxelSection> entry : context.getSections().entrySet()) {
                    int secY = entry.getKey();
                    VoxelSection voxSec = entry.getValue();
                    if (voxSec == null) continue;

                    int baseY = secY << 4;
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            for (int y = 0; y < 16; y++) {
                                int blockId = voxSec.getBlockId(x, y, z);
                                if (blockId != 0) {
                                    BlockState bs = MinecraftVoxelBridge.getBlockState(blockId);
                                    if (bs != null) {
                                        mutPos.set((chunkX << 4) | x, baseY + y, (chunkZ << 4) | z);
                                        boolean placed = level.setBlock(mutPos, bs, 2 | 16);
                                        if (!placed) {
                                            throw new IllegalStateException("Failed to place block in RAM at " + mutPos + " with state " + bs);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Apply block entities if present
                for (Map.Entry<Long, byte[]> beEntry : context.getBlockEntities().entrySet()) {
                    long key = beEntry.getKey();
                    int lx = (int) (key & 0xF);
                    int lz = (int) ((key >> 4) & 0xF);
                    int wy = (int) ((short) (key >> 8));
                    byte[] rawNbt = beEntry.getValue();
                    if (rawNbt != null) {
                        mutPos.set((chunkX << 4) | lx, wy, (chunkZ << 4) | lz);
                        BlockEntity be = level.getBlockEntity(mutPos);
                        if (be != null) {
                            try {
                                CompoundTag tag = net.minecraft.nbt.NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(rawNbt)));
                                if (tag != null) {
                                    be.loadWithComponents(tag, level.registryAccess());
                                    be.setChanged();
                                }
                            } catch (Exception e) {
                                LOGGER.warn("Failed to load BE NBT in modifyChunkRamAsync at {}: {}", mutPos, e.getMessage());
                            }
                        }
                    }
                }

                chunk.setUnsaved(true);
                MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
                if (grid != null) {
                    grid.getCache().invalidateChunk(chunkX, chunkZ);
                }
                long duration = System.nanoTime() - startTime;
                future.complete(WriteResult.successRam(chunkX, chunkZ, duration));
            } catch (Throwable t) {
                LOGGER.error("Error modifying RAM chunk ({}, {}): {}", chunkX, chunkZ, t.getMessage(), t);
                future.complete(WriteResult.failure(WriteStatus.FAIL_IO_ERROR, chunkX, chunkZ, t.getMessage()));
            }
        };

        if (level instanceof ServerLevel serverLevel) {
            if (Thread.currentThread() == serverLevel.getServer().getRunningThread()) {
                task.run();
            } else {
                serverLevel.getServer().execute(task);
            }
        } else {
            task.run();
        }

        return future;
    }

    @Override
    public synchronized void close() {
        if (coordinator != null) {
            coordinator.close();
        }
    }
}
