package com.pixel.qve.neoforge.bridge.writer;

import com.pixel.qve.mca.writer.IChunkWriteContext;
import com.pixel.qve.mca.writer.McaRegionWriter;
import com.pixel.qve.mca.writer.McaWriteCoordinator;
import com.pixel.qve.mca.writer.WriteOptions;
import com.pixel.qve.neoforge.api.WriteResult;
import com.pixel.qve.neoforge.api.WriteStatus;
import com.pixel.qve.neoforge.api.event.ChunkPostDirectWriteEvent;
import com.pixel.qve.neoforge.api.event.ChunkPreDirectWriteEvent;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
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
        } else {
            return setBlockDirectAsync(pos, state, blockEntityNbt);
        }
    }

    /**
     * Modifies or creates a chunk without concern for whether it is currently loaded in RAM or unloaded on disk.
     *
     * @param chunkX   World chunk X
     * @param chunkZ   World chunk Z
     * @param modifier Consumer modifying the chunk context
     * @return CompletableFuture completing with WriteResult
     */
    public CompletableFuture<WriteResult> modifyChunkUnifiedAsync(int chunkX, int chunkZ, Consumer<IChunkWriteContext> modifier) {
        if (ChunkExclusivityGuard.isChunkLoadedInRam(level, chunkX, chunkZ)) {
            return modifyChunkRamAsync(chunkX, chunkZ, modifier);
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
                MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
                if (grid != null) {
                    int readId = grid.getBlockId(pos.getX(), pos.getY(), pos.getZ());
                    String readName = MinecraftVoxelBridge.getBlockRegistry().getName(readId);
                    boolean verified = (readId == blockId) || (readName != null && readName.equals(expectedName));
                    return res.withVerification(verified, readName != null ? readName : "minecraft:air");
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

        // 5. Submit write to worker pool
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
     * Prepares a ChunkWriteContext pre-loading existing voxel data from disk if the chunk already exists.
     */
    public ChunkWriteContext prepareChunkContext(int chunkX, int chunkZ, Consumer<IChunkWriteContext> modifier) {
        MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
        VoxelChunkColumn existingColumn = (grid != null) ? grid.getColumn(chunkX, chunkZ) : null;
        boolean isNew = (existingColumn == null);

        int minSec = level.getMinSection();
        int maxSec = level.getMinSection() + level.getSectionsCount() - 1;
        ChunkWriteContext context = new ChunkWriteContext(chunkX, chunkZ, minSec, maxSec, isNew);

        if (existingColumn != null) {
            for (int sy = minSec; sy <= maxSec; sy++) {
                VoxelSection sec = existingColumn.getSection(sy);
                if (sec != null && !sec.isEmpty()) {
                    context.setSection(sy, sec.copy());
                }
            }
        } else if (regionDirectory != null) {
            int rx = chunkX >> 5;
            int rz = chunkZ >> 5;
            Path mcaFile = regionDirectory.resolve("r." + rx + "." + rz + ".mca");
            if (Files.isRegularFile(mcaFile)) {
                try (com.pixel.qve.mca.McaRegionReader directReader = new com.pixel.qve.mca.McaRegionReader(mcaFile, MinecraftVoxelBridge.getBlockRegistry())) {
                    int localCx = chunkX & 31;
                    int localCz = chunkZ & 31;
                    if (directReader.hasChunk(localCx, localCz)) {
                        directReader.readChunk(localCx, localCz, (secY, sec) -> {
                            if (sec != null && !sec.isEmpty()) {
                                context.setSection(secY, sec.copy());
                            }
                        });
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to direct-read existing chunk ({}, {}) from {}: {}",
                            chunkX, chunkZ, mcaFile, e.getMessage());
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

        List<McaWriteCoordinator.ChunkWriteTask> tasks = new ArrayList<>(editsList.size());
        List<ChunkWriteContext> contexts = new ArrayList<>(editsList.size());
        List<CompletableFuture<WriteResult>> ramFutures = new ArrayList<>();

        long startTime = System.nanoTime();

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

            ChunkWriteContext context = prepareChunkContext(cx, cz, ctx -> {
                for (Map.Entry<Integer, VoxelSection> secEntry : edits.getWholeSections().entrySet()) {
                    ctx.setSection(secEntry.getKey(), secEntry.getValue());
                }
                for (ChunkWriteBatch.BlockMutation m : edits.getMutations()) {
                    int curId = ctx.getBlock(m.worldX() & 15, m.worldY(), m.worldZ() & 15);
                    if (m.matchesFilter(curId, null)) {
                        ctx.setBlock(m.worldX() & 15, m.worldY(), m.worldZ() & 15, m.targetBlockId());
                        if (m.rawNbt() != null) {
                            ctx.setBlockEntityRaw(m.worldX() & 15, m.worldY(), m.worldZ() & 15, m.rawNbt());
                        }
                    }
                }
            });

            // Fire pre-write event
            ChunkPreDirectWriteEvent preEvent = new ChunkPreDirectWriteEvent(level, cx, cz, context);
            if (NeoForge.EVENT_BUS.post(preEvent).isCanceled()) {
                ramFutures.add(CompletableFuture.completedFuture(
                        WriteResult.failure(WriteStatus.FAIL_CANCELLED_BY_EVENT, cx, cz, "Cancelled by ChunkPreDirectWriteEvent")
                ));
                continue;
            }

            contexts.add(context);
            tasks.add(new McaWriteCoordinator.ChunkWriteTask(cx, cz, context.getSections(), context.getBlockEntities()));
        }

        if (tasks.isEmpty()) {
            return CompletableFuture.allOf(ramFutures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> ramFutures.stream().map(CompletableFuture::join).toList());
        }

        MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);

        return coordinator.writeRegionBatchAsync(rx, rz, tasks).thenCombine(
                CompletableFuture.allOf(ramFutures.toArray(new CompletableFuture[0])),
                (metricsList, v) -> {
                    long duration = System.nanoTime() - startTime;
                    List<WriteResult> results = new ArrayList<>(editsList.size());

                    // Collect disk write results
                    for (int t = 0; t < metricsList.size(); t++) {
                        McaRegionWriter.WriteMetrics m = metricsList.get(t);
                        ChunkWriteContext ctx = contexts.get(t);
                        int cx = ctx.getChunkX();
                        int cz = ctx.getChunkZ();

                        WriteResult res = WriteResult.successDisk(cx, cz, duration, m.compressedBytes(), m.sectorOffset(), m.isRelocated());
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
                }
        ).exceptionally(ex -> {
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
            try {
                for (ChunkWriteBatch.BlockMutation m : edits.getMutations()) {
                    BlockPos pos = new BlockPos(m.worldX(), m.worldY(), m.worldZ());
                    BlockState cur = level.getBlockState(pos);
                    if (m.matchesFilter(-1, cur)) {
                        level.setBlock(pos, m.targetState(), 2 | 16);
                        if (m.tagNbt() != null) {
                            BlockEntity be = level.getBlockEntity(pos);
                            if (be != null) {
                                be.loadWithComponents(m.tagNbt(), level.registryAccess());
                                be.setChanged();
                            }
                        }
                    }
                }
                LevelChunk lc = level.getChunk(edits.getChunkX(), edits.getChunkZ());
                if (lc != null) {
                    lc.setUnsaved(true);
                }
                MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
                if (grid != null) {
                    grid.getCache().invalidateChunk(edits.getChunkX(), edits.getChunkZ());
                }
                long elapsed = System.nanoTime() - t0;
                future.complete(WriteResult.successRam(edits.getChunkX(), edits.getChunkZ(), elapsed));
            } catch (Throwable t) {
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
                level.setBlock(pos, state, flags | 16);
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

                // Apply modified blocks to live LevelChunk
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
                                    BlockState bs = MinecraftVoxelBridge.getBlockRegistry().getStateDictionary().getCanonicalState(blockId) != null
                                            ? net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                                                    net.minecraft.resources.ResourceLocation.tryParse(MinecraftVoxelBridge.getBlockRegistry().getName(blockId))
                                              ).defaultBlockState()
                                            : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                                    level.setBlock(new BlockPos((chunkX << 4) | x, baseY + y, (chunkZ << 4) | z), bs, 2);
                                }
                            }
                        }
                    }
                }

                chunk.setUnsaved(true);
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
