package com.pixel.qve.neoforge.api;

import com.pixel.qve.mca.writer.McaWriteCoordinator;
import com.pixel.qve.mca.writer.WriteOptions;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelGrid;
import com.pixel.qve.neoforge.bridge.writer.ChunkExclusivityGuard;
import com.pixel.qve.neoforge.bridge.writer.MinecraftVoxelWriter;
import com.pixel.qve.world.VoxelSection;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Universal Heterogeneous Voxel Buffer for high-throughput multi-block, volumetric, and multi-chunk modifications.
 * Automatically buffers and partitions disparate block placements across arbitrary world coordinates,
 * routing transparently between active server RAM and offline Anvil (.mca) region files.
 */
public final class ChunkWriteBatch {

    private static final Set<ChunkWriteBatch> ACTIVE_BATCHES = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Gets all currently active or queued ChunkWriteBatches across the server.
     */
    public static List<ChunkWriteBatch> getActiveBatches() {
        return new ArrayList<>(ACTIVE_BATCHES);
    }

    private final Level level;
    private final Map<Long, ChunkEdits> chunkEditsMap = new LinkedHashMap<>();
    private final Set<Long> remainingChunkKeys = Collections.synchronizedSet(new HashSet<>());
    private volatile WriteOptions activeOptions = WriteOptions.DEFAULT;
    private int totalBlockCount = 0;

    /**
     * Gets the Minecraft Level targeted by this batch.
     */
    public Level getLevel() {
        return level;
    }

    /**
     * Gets the WriteOptions currently active for this batch.
     */
    public WriteOptions getActiveOptions() {
        return activeOptions;
    }

    /**
     * Returns a snapshot of ChunkEdits that have not yet finished execution.
     */
    public List<ChunkEdits> getPendingChunkEdits() {
        List<ChunkEdits> list = new ArrayList<>();
        synchronized (remainingChunkKeys) {
            if (remainingChunkKeys.isEmpty() && !ACTIVE_BATCHES.contains(this) && !chunkEditsMap.isEmpty()) {
                return new ArrayList<>(chunkEditsMap.values());
            }
            for (Long key : remainingChunkKeys) {
                ChunkEdits edits = chunkEditsMap.get(key);
                if (edits != null) {
                    list.add(edits);
                }
            }
        }
        return list;
    }

    /**
     * Spatial chunk execution ordering strategies.
     */
    public enum SortStrategy {
        /** Preserve arbitrary insertion order. */
        NONE,
        /** Prioritize chunks closest to active players, with equidistant fallback. */
        PLAYER_PROXIMITY,
        /** Prioritize chunks closest to the bounding box centroid. */
        CENTROID
    }

    /**
     * Returns a sorted list of ChunkEdits according to the requested spatial SortStrategy.
     *
     * @param strategy Desired SortStrategy
     * @return Sorted list of ChunkEdits
     */
    public List<ChunkEdits> getSortedChunkEdits(SortStrategy strategy) {
        List<ChunkEdits> list = new ArrayList<>(chunkEditsMap.values());
        if (strategy == SortStrategy.PLAYER_PROXIMITY && level instanceof ServerLevel sl && !sl.players().isEmpty()) {
            List<ServerPlayer> players = sl.players();
            list.sort(Comparator.comparingDouble(edits -> {
                double minD2 = Double.MAX_VALUE;
                for (ServerPlayer p : players) {
                    double pcx = p.chunkPosition().x;
                    double pcz = p.chunkPosition().z;
                    double dx = edits.getChunkX() - pcx;
                    double dz = edits.getChunkZ() - pcz;
                    double d2 = dx * dx + dz * dz;
                    if (d2 < minD2) {
                        minD2 = d2;
                    }
                }
                return minD2;
            }));
        } else if (strategy == SortStrategy.CENTROID && !list.isEmpty()) {
            double avgCx = 0;
            double avgCz = 0;
            for (ChunkEdits e : list) {
                avgCx += e.getChunkX();
                avgCz += e.getChunkZ();
            }
            avgCx /= list.size();
            avgCz /= list.size();
            final double cx0 = avgCx;
            final double cz0 = avgCz;
            list.sort(Comparator.comparingDouble(edits -> {
                double dx = edits.getChunkX() - cx0;
                double dz = edits.getChunkZ() - cz0;
                return dx * dx + dz * dz;
            }));
        }
        return list;
    }

    /**
     * Record representing a single block mutation, with optional NBT and conditional replacement filter.
     */
    public record BlockMutation(
            int worldX, int worldY, int worldZ,
            int targetBlockId, BlockState targetState,
            byte[] rawNbt, CompoundTag tagNbt,
            int filterBlockId, BlockState filterState
    ) {
        public boolean matchesFilter(int currentBlockId, BlockState currentState) {
            if (filterBlockId < 0 && filterState == null) {
                return true; // Unconditional
            }
            if (filterBlockId >= 0 && currentBlockId == filterBlockId) {
                return true;
            }
            return filterState != null && currentState != null &&
                    (currentState == filterState || currentState.getBlock() == filterState.getBlock());
        }
    }

    /**
     * Internal container storing all queued mutations and full section replacements for a single chunk.
     */
    public static final class ChunkEdits {
        private final int chunkX;
        private final int chunkZ;
        private final List<BlockMutation> mutations = new ArrayList<>();
        private final Map<Integer, VoxelSection> wholeSections = new HashMap<>();

        public ChunkEdits(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        public int getChunkX() {
            return chunkX;
        }

        public int getChunkZ() {
            return chunkZ;
        }

        public List<BlockMutation> getMutations() {
            return mutations;
        }

        public Map<Integer, VoxelSection> getWholeSections() {
            return wholeSections;
        }

        public void addMutation(BlockMutation mutation) {
            mutations.add(mutation);
        }

        public void setSection(int sectionY, VoxelSection section) {
            wholeSections.put(sectionY, section);
        }
    }

    /**
     * Constructs a ChunkWriteBatch targeting the given Level.
     *
     * @param level Target Minecraft Level
     */
    public ChunkWriteBatch(Level level) {
        this.level = Objects.requireNonNull(level, "Level cannot be null");
    }

    private ChunkEdits getOrCreateChunkEdits(int chunkX, int chunkZ) {
        long key = (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
        remainingChunkKeys.add(key);
        return chunkEditsMap.computeIfAbsent(key, k -> new ChunkEdits(chunkX, chunkZ));
    }

    /**
     * Enqueues a single block placement into the batch.
     *
     * @param pos   Absolute BlockPos
     * @param state BlockState to set
     * @return this builder
     */
    public ChunkWriteBatch setBlock(BlockPos pos, BlockState state) {
        return setBlock(pos.getX(), pos.getY(), pos.getZ(), state, null);
    }

    /**
     * Enqueues a single block placement with BlockEntity NBT into the batch.
     *
     * @param pos            Absolute BlockPos
     * @param state          BlockState to set
     * @param blockEntityNbt Optional BlockEntity CompoundTag
     * @return this builder
     */
    public ChunkWriteBatch setBlock(BlockPos pos, BlockState state, CompoundTag blockEntityNbt) {
        return setBlock(pos.getX(), pos.getY(), pos.getZ(), state, blockEntityNbt);
    }

    /**
     * Enqueues a single block placement at raw coordinates into the batch.
     *
     * @param x     World X coordinate
     * @param y     World Y coordinate
     * @param z     World Z coordinate
     * @param state BlockState to set
     * @return this builder
     */
    public ChunkWriteBatch setBlock(int x, int y, int z, BlockState state) {
        return setBlock(x, y, z, state, null);
    }

    /**
     * Enqueues a single block placement with BlockEntity NBT at raw coordinates into the batch.
     *
     * @param x              World X coordinate
     * @param y              World Y coordinate
     * @param z              World Z coordinate
     * @param state          BlockState to set
     * @param blockEntityNbt Optional BlockEntity CompoundTag
     * @return this builder
     */
    public ChunkWriteBatch setBlock(int x, int y, int z, BlockState state, CompoundTag blockEntityNbt) {
        return setBlock(x, y, z, state, blockEntityNbt, null);
    }

    /**
     * Enqueues a single block placement with BlockEntity NBT and optional replacement filter.
     *
     * @param pos            Absolute BlockPos
     * @param state          BlockState to set
     * @param blockEntityNbt Optional BlockEntity CompoundTag
     * @param filterState    Optional filter BlockState, or null for unconditional
     * @return this builder
     */
    public ChunkWriteBatch setBlock(BlockPos pos, BlockState state, CompoundTag blockEntityNbt, BlockState filterState) {
        return setBlock(pos.getX(), pos.getY(), pos.getZ(), state, blockEntityNbt, filterState);
    }

    /**
     * Enqueues a single block placement with BlockEntity NBT and optional replacement filter at raw coordinates.
     *
     * @param x              World X coordinate
     * @param y              World Y coordinate
     * @param z              World Z coordinate
     * @param state          BlockState to set
     * @param blockEntityNbt Optional BlockEntity CompoundTag
     * @param filterState    Optional filter BlockState, or null for unconditional
     * @return this builder
     */
    public ChunkWriteBatch setBlock(int x, int y, int z, BlockState state, CompoundTag blockEntityNbt, BlockState filterState) {
        int cx = x >> 4;
        int cz = z >> 4;
        int blockId = MinecraftVoxelBridge.getBlockId(state);
        int filterBlockId = (filterState != null) ? MinecraftVoxelBridge.getBlockId(filterState) : -1;

        byte[] rawNbt = null;
        if (blockEntityNbt != null) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                net.minecraft.nbt.NbtIo.write(blockEntityNbt, new DataOutputStream(baos));
                rawNbt = baos.toByteArray();
            } catch (Exception ignored) {
            }
        }

        ChunkEdits edits = getOrCreateChunkEdits(cx, cz);
        edits.addMutation(new BlockMutation(x, y, z, blockId, state, rawNbt, blockEntityNbt, filterBlockId, filterState));
        totalBlockCount++;
        return this;
    }

    /**
     * Enqueues an axis-aligned volumetric fill between two corner coordinates.
     *
     * @param from  First corner
     * @param to    Opposite corner
     * @param state Target BlockState to fill
     * @return this builder
     */
    public ChunkWriteBatch fill(BlockPos from, BlockPos to, BlockState state) {
        return fill(from, to, state, null);
    }

    /**
     * Enqueues a conditional axis-aligned volumetric fill replacing only matching blocks.
     *
     * @param from          First corner
     * @param to            Opposite corner
     * @param state         Target BlockState to fill
     * @param replaceFilter Filter BlockState to replace, or null for unconditional fill
     * @return this builder
     */
    public ChunkWriteBatch fill(BlockPos from, BlockPos to, BlockState state, BlockState replaceFilter) {
        int minX = Math.min(from.getX(), to.getX());
        int maxX = Math.max(from.getX(), to.getX());
        int minY = Math.min(from.getY(), to.getY());
        int maxY = Math.max(from.getY(), to.getY());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxZ = Math.max(from.getZ(), to.getZ());

        return fill(minX, minY, minZ, maxX, maxY, maxZ, state, replaceFilter);
    }

    /**
     * Enqueues a volumetric fill within absolute integer bounds.
     *
     * @param minX          Minimum X bound
     * @param minY          Minimum Y bound
     * @param minZ          Minimum Z bound
     * @param maxX          Maximum X bound
     * @param maxY          Maximum Y bound
     * @param maxZ          Maximum Z bound
     * @param state         Target BlockState
     * @param replaceFilter Filter BlockState, or null for unconditional
     * @return this builder
     */
    public ChunkWriteBatch fill(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, BlockState state, BlockState replaceFilter) {
        int worldMinY = level.getMinBuildHeight();
        int worldMaxY = level.getMaxBuildHeight() - 1;
        int clampedMinY = Math.max(minY, worldMinY);
        int clampedMaxY = Math.min(maxY, worldMaxY);

        if (minX > maxX || clampedMinY > clampedMaxY || minZ > maxZ) {
            return this;
        }

        int blockId = MinecraftVoxelBridge.getBlockId(state);
        int filterId = (replaceFilter != null) ? MinecraftVoxelBridge.getBlockId(replaceFilter) : -1;

        for (int y = clampedMinY; y <= clampedMaxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    int cx = x >> 4;
                    int cz = z >> 4;
                    ChunkEdits edits = getOrCreateChunkEdits(cx, cz);
                    edits.addMutation(new BlockMutation(x, y, z, blockId, state, null, null, filterId, replaceFilter));
                    totalBlockCount++;
                }
            }
        }

        return this;
    }

    /**
     * Enqueues a whole VoxelSection replacement into the batch.
     *
     * @param sectionX Section X coordinate
     * @param sectionY Section Y coordinate
     * @param sectionZ Section Z coordinate
     * @param section  VoxelSection instance to replace
     * @return this builder
     */
    public ChunkWriteBatch setSection(int sectionX, int sectionY, int sectionZ, VoxelSection section) {
        ChunkEdits edits = getOrCreateChunkEdits(sectionX, sectionZ);
        edits.setSection(sectionY, section);
        totalBlockCount += 4096;
        return this;
    }

    /**
     * Gets the total number of individual block mutations enqueued in this batch.
     *
     * @return Total voxel count
     */
    public int getTotalBlockCount() {
        return totalBlockCount;
    }

    /**
     * Gets the number of distinct chunks affected by this batch.
     *
     * @return Affected chunk count
     */
    public int getAffectedChunkCount() {
        return chunkEditsMap.size();
    }

    /**
     * Performs a strict pre-flight validation of the entire batch against the given WriteOptions.
     * Evaluates world coordinate height boundaries, strict RAM exclusivity, and chunk existence.
     *
     * @param options Target WriteOptions
     * @return Empty Optional if validation succeeded, or Optional containing the first failure WriteResult
     */
    public Optional<WriteResult> validate(WriteOptions options) {
        WriteOptions opts = (options != null) ? options : WriteOptions.DEFAULT;

        int worldMinY = level.getMinBuildHeight();
        int worldMaxY = level.getMaxBuildHeight() - 1;

        MinecraftVoxelWriter writer = VoxelWriteAPI.getWriter(level);
        McaWriteCoordinator coordinator = (writer != null) ? writer.getCoordinator() : null;

        for (ChunkEdits edits : chunkEditsMap.values()) {
            int cx = edits.getChunkX();
            int cz = edits.getChunkZ();

            // 1. Validate coordinate heights
            for (BlockMutation m : edits.getMutations()) {
                if (m.worldY() < worldMinY || m.worldY() > worldMaxY) {
                    return Optional.of(WriteResult.failure(
                            WriteStatus.FAIL_INVALID_COORDINATES,
                            cx, cz,
                            String.format("Block mutation at (%d, %d, %d) Y=%d is outside world bounds [%d..%d]",
                                    m.worldX(), m.worldY(), m.worldZ(), m.worldY(), worldMinY, worldMaxY)
                    ));
                }
            }

            // 2. Strict RAM mutual exclusion check
            boolean inRam = ChunkExclusivityGuard.isChunkLoadedInRam(level, cx, cz);
            if (opts.isStrict() && inRam) {
                return Optional.of(WriteResult.failure(
                        WriteStatus.FAIL_CHUNK_LOADED_IN_RAM,
                        cx, cz,
                        String.format("Direct MCA write rejected by strict policy: chunk (%d, %d) is currently loaded in RAM", cx, cz)
                ));
            }

            // 3. Existence check if CreationPolicy == FAIL_IF_MISSING
            if (opts.shouldFailIfMissing() && !inRam) {
                if (coordinator == null || !coordinator.hasChunk(cx, cz)) {
                    return Optional.of(WriteResult.failure(
                            WriteStatus.FAIL_CHUNK_NOT_FOUND,
                            cx, cz,
                            String.format("Chunk (%d, %d) does not exist on disk and FAIL_IF_MISSING policy is active", cx, cz)
                    ));
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Executes the batch asynchronously using default {@link WriteOptions#DEFAULT} (Unified routing).
     *
     * @return CompletableFuture completing with BatchWriteResult
     */
    public CompletableFuture<BatchWriteResult> executeAsync() {
        return executeAsync(WriteOptions.DEFAULT);
    }

    /**
     * Executes the batch asynchronously with the specified write mode (backward compatibility).
     *
     * @param mode Target write mode (UNIFIED or STRICT_DIRECT)
     * @return CompletableFuture completing with BatchWriteResult
     */
    public CompletableFuture<BatchWriteResult> executeAsync(VoxelWriteMode mode) {
        WriteOptions opts = (mode == VoxelWriteMode.STRICT_DIRECT) ? WriteOptions.STRICT : WriteOptions.DEFAULT;
        return executeAsync(opts);
    }

    /**
     * Executes the batch asynchronously with the specified WriteOptions.
     * Enforces fail-fast pre-flight validation before modifying any storage.
     *
     * @param options Target WriteOptions governing execution and creation policies
     * @return CompletableFuture completing with BatchWriteResult
     */
    public CompletableFuture<BatchWriteResult> executeAsync(WriteOptions options) {
        long startTime = System.nanoTime();
        if (chunkEditsMap.isEmpty()) {
            return CompletableFuture.completedFuture(new BatchWriteResult(0, 0, 0, 0, 0, 0, 0L, List.of()));
        }

        WriteOptions opts = (options != null) ? options : WriteOptions.DEFAULT;
        this.activeOptions = opts;
        ACTIVE_BATCHES.add(this);
        synchronized (remainingChunkKeys) {
            remainingChunkKeys.clear();
            remainingChunkKeys.addAll(chunkEditsMap.keySet());
        }

        int totalBlocks = totalBlockCount;

        // 1. Strict Pre-Flight Fail-Fast Validation (Zero blocks written if invalid)
        Optional<WriteResult> failFast = validate(opts);
        if (failFast.isPresent()) {
            ACTIVE_BATCHES.remove(this);
            synchronized (remainingChunkKeys) {
                remainingChunkKeys.clear();
            }
            long duration = System.nanoTime() - startTime;
            return CompletableFuture.completedFuture(new BatchWriteResult(
                    chunkEditsMap.size(), 0, chunkEditsMap.size(),
                    totalBlocks, 0, 0, duration, List.of(failFast.get())
            ));
        }

        // 2. Spatial Ordering: Prioritize active players, then centroid
        List<ChunkEdits> sortedChunks = getSortedChunkEdits(SortStrategy.PLAYER_PROXIMITY);

        // 3. Partition chunks into RAM and Disk pools
        List<ChunkEdits> ramChunks = new ArrayList<>();
        List<ChunkEdits> diskChunks = new ArrayList<>();

        for (ChunkEdits edits : sortedChunks) {
            boolean isRam = !opts.isStrict() && ChunkExclusivityGuard.isChunkLoadedInRam(level, edits.chunkX, edits.chunkZ);
            if (isRam) {
                ramChunks.add(edits);
            } else {
                diskChunks.add(edits);
            }
        }

        List<CompletableFuture<WriteResult>> futures = new ArrayList<>(chunkEditsMap.size());

        // 4. Dispatch RAM chunks on server thread
        if (!ramChunks.isEmpty()) {
            CompletableFuture<List<WriteResult>> ramFuture = new CompletableFuture<>();
            Runnable ramTask = () -> {
                List<WriteResult> resList = new ArrayList<>(ramChunks.size());
                MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);

                for (ChunkEdits edits : ramChunks) {
                    long t0 = System.nanoTime();
                    record AppliedRamMutation(BlockPos pos, BlockState previousState, CompoundTag previousBeNbt) {}
                    List<AppliedRamMutation> applied = new ArrayList<>();
                    try {
                        for (BlockMutation m : edits.mutations) {
                            BlockPos pos = new BlockPos(m.worldX(), m.worldY(), m.worldZ());
                            BlockState cur = level.getBlockState(pos);
                            if (m.matchesFilter(-1, cur)) {
                                BlockEntity oldBe = level.getBlockEntity(pos);
                                CompoundTag oldBeNbt = (oldBe != null) ? oldBe.saveWithFullMetadata(level.registryAccess()) : null;
                                applied.add(new AppliedRamMutation(pos, cur, oldBeNbt));

                                // Enforce flag 16 (UPDATE_KNOWN_SHAPE) to prevent Vanilla from force-loading adjacent chunk borders
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
                        LevelChunk lc = level.getChunk(edits.chunkX, edits.chunkZ);
                        if (lc != null) {
                            lc.setUnsaved(true);
                        }
                        if (grid != null) {
                            grid.getCache().invalidateChunk(edits.chunkX, edits.chunkZ);
                        }
                        long elapsed = System.nanoTime() - t0;
                        resList.add(WriteResult.successRam(edits.chunkX, edits.chunkZ, elapsed));
                    } catch (Throwable t) {
                        for (int i = applied.size() - 1; i >= 0; i--) {
                            AppliedRamMutation arm = applied.get(i);
                            try {
                                level.setBlock(arm.pos(), arm.previousState(), 2 | 16);
                                if (arm.previousBeNbt() != null) {
                                    BlockEntity be = level.getBlockEntity(arm.pos());
                                    if (be != null) {
                                        be.loadWithComponents(arm.previousBeNbt(), level.registryAccess());
                                        be.setChanged();
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        resList.add(WriteResult.failure(WriteStatus.FAIL_IO_ERROR, edits.chunkX, edits.chunkZ, t.getMessage()));
                    }
                }
                ramFuture.complete(resList);
            };

            if (level instanceof ServerLevel sl) {
                if (Thread.currentThread() == sl.getServer().getRunningThread()) {
                    ramTask.run();
                } else {
                    sl.getServer().execute(ramTask);
                }
            } else {
                ramTask.run();
            }

            for (int i = 0; i < ramChunks.size(); i++) {
                final int idx = i;
                final ChunkEdits ce = ramChunks.get(i);
                futures.add(ramFuture.thenApply(list -> {
                    WriteResult res = list.get(idx);
                    long key = (((long) ce.chunkX) << 32) | (ce.chunkZ & 0xFFFFFFFFL);
                    remainingChunkKeys.remove(key);
                    return res;
                }));
            }
        }

        // 5. Dispatch Disk chunks via Region-Batching if enabled, with late-binding RAM check
        MinecraftVoxelWriter writer = VoxelWriteAPI.getWriter(level);
        boolean useRegionBatching = com.pixel.qve.neoforge.config.QveConfig.REGION_BATCHING_ENABLED.get();

        if (writer != null && useRegionBatching && !diskChunks.isEmpty()) {
            Map<Long, List<ChunkEdits>> byRegion = new LinkedHashMap<>();
            for (ChunkEdits edits : diskChunks) {
                int rx = edits.chunkX >> 5;
                int rz = edits.chunkZ >> 5;
                long rKey = (((long) rx) << 32) | (rz & 0xFFFFFFFFL);
                byRegion.computeIfAbsent(rKey, k -> new ArrayList<>()).add(edits);
            }

            for (Map.Entry<Long, List<ChunkEdits>> entry : byRegion.entrySet()) {
                long rKey = entry.getKey();
                int rx = (int) (rKey >> 32);
                int rz = (int) rKey;
                List<ChunkEdits> regionChunks = entry.getValue();

                CompletableFuture<List<WriteResult>> regFuture = writer.writeRegionBatchAsync(rx, rz, regionChunks, opts);
                for (int i = 0; i < regionChunks.size(); i++) {
                    final int idx = i;
                    final ChunkEdits ce = regionChunks.get(i);
                    futures.add(regFuture.thenApply(list -> {
                        WriteResult res = (idx < list.size()) ? list.get(idx)
                                : WriteResult.failure(WriteStatus.FAIL_IO_ERROR, ce.chunkX, ce.chunkZ, "Missing region batch result");
                        long key = (((long) ce.chunkX) << 32) | (ce.chunkZ & 0xFFFFFFFFL);
                        remainingChunkKeys.remove(key);
                        return res;
                    }));
                }
            }
        } else {
            // Fallback single chunk dispatch with late RAM check
            for (ChunkEdits edits : diskChunks) {
                long key = (((long) edits.chunkX) << 32) | (edits.chunkZ & 0xFFFFFFFFL);
                if (opts.isUnified() && ChunkExclusivityGuard.isChunkLoadedInRam(level, edits.chunkX, edits.chunkZ)) {
                    futures.add(applyChunkToRamAsync(edits).thenApply(res -> {
                        remainingChunkKeys.remove(key);
                        return res;
                    }));
                } else {
                    futures.add(VoxelWriteAPI.writeChunkDirectAsync(level, edits.chunkX, edits.chunkZ, ctx -> {
                        for (Map.Entry<Integer, VoxelSection> secEntry : edits.wholeSections.entrySet()) {
                            ctx.setSection(secEntry.getKey(), secEntry.getValue());
                        }
                        for (BlockMutation m : edits.mutations) {
                            int curId = ctx.getBlock(m.worldX() & 15, m.worldY(), m.worldZ() & 15);
                            if (m.matchesFilter(curId, null)) {
                                ctx.setBlock(m.worldX() & 15, m.worldY(), m.worldZ() & 15, m.targetBlockId());
                                if (m.rawNbt() != null) {
                                    ctx.setBlockEntityRaw(m.worldX() & 15, m.worldY(), m.worldZ() & 15, m.rawNbt());
                                }
                            }
                        }
                    }, opts).thenApply(res -> {
                        remainingChunkKeys.remove(key);
                        return res;
                    }));
                }
            }
        }

        // 6. Aggregate all results into BatchWriteResult and deregister active batch
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    ACTIVE_BATCHES.remove(this);
                    synchronized (remainingChunkKeys) {
                        remainingChunkKeys.clear();
                    }
                    List<WriteResult> results = new ArrayList<>(futures.size());
                    int succeeded = 0;
                    int failed = 0;
                    for (CompletableFuture<WriteResult> f : futures) {
                        WriteResult res = f.join();
                        results.add(res);
                        if (res.isSuccess()) {
                            succeeded++;
                        } else {
                            failed++;
                        }
                    }
                    long duration = System.nanoTime() - startTime;
                    return new BatchWriteResult(
                            futures.size(), succeeded, failed,
                            totalBlocks, ramChunks.size(), diskChunks.size(),
                            duration, results
                    );
                });
    }

    private CompletableFuture<WriteResult> applyChunkToRamAsync(ChunkEdits edits) {
        long startTime = System.nanoTime();
        CompletableFuture<WriteResult> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                for (BlockMutation m : edits.mutations) {
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
                LevelChunk lc = level.getChunk(edits.chunkX, edits.chunkZ);
                if (lc != null) {
                    lc.setUnsaved(true);
                }
                MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
                if (grid != null) {
                    grid.getCache().invalidateChunk(edits.chunkX, edits.chunkZ);
                }
                long duration = System.nanoTime() - startTime;
                future.complete(WriteResult.successRam(edits.chunkX, edits.chunkZ, duration));
            } catch (Throwable t) {
                future.complete(WriteResult.failure(WriteStatus.FAIL_IO_ERROR, edits.chunkX, edits.chunkZ, t.getMessage()));
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
     * Backward-compatible alias executing strictly in direct disk mode.
     */
    public CompletableFuture<BatchWriteResult> executeDirectAsync() {
        return executeAsync(WriteOptions.STRICT);
    }

    /**
     * Backward-compatible alias executing in unified mode.
     */
    public CompletableFuture<BatchWriteResult> executeUnifiedAsync() {
        return executeAsync(WriteOptions.DEFAULT);
    }
}
