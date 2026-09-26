package com.pixel.qve.neoforge.api;

import com.pixel.qve.mca.writer.McaWriteCoordinator;
import com.pixel.qve.mca.writer.WriteOptions;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelGrid;
import com.pixel.qve.neoforge.bridge.writer.ChunkEditsApplicator;
import com.pixel.qve.neoforge.bridge.writer.ChunkExclusivityGuard;
import com.pixel.qve.neoforge.bridge.writer.DeferredChunkQueue;
import com.pixel.qve.neoforge.bridge.writer.MinecraftRegionFileBridge;
import com.pixel.qve.neoforge.bridge.writer.MinecraftVoxelWriter;
import com.pixel.qve.world.VoxelSection;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import com.pixel.qve.mca.writer.PrimitiveMutationBuffer;

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

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ChunkWriteBatch.class);
    private static final Set<ChunkWriteBatch> ACTIVE_BATCHES = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Gets all currently active or queued ChunkWriteBatches across the server.
     */
    public static List<ChunkWriteBatch> getActiveBatches() {
        return new ArrayList<>(ACTIVE_BATCHES);
    }

    private final Level level;
    private final Long2ObjectLinkedOpenHashMap<ChunkEdits> chunkEditsMap = new Long2ObjectLinkedOpenHashMap<>();
    private final LongOpenHashSet remainingChunkKeys = new LongOpenHashSet();
    private volatile WriteOptions activeOptions = WriteOptions.DEFAULT;
    private long totalBlockCount = 0;

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
            for (long key : remainingChunkKeys) {
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
        public long posAsLong() {
            return BlockPos.asLong(worldX, worldY, worldZ);
        }

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
        private final PrimitiveMutationBuffer mutationBuffer = new PrimitiveMutationBuffer();
        private final Map<Integer, VoxelSection> wholeSections = new HashMap<>();
        private List<BlockMutation> legacyMutations = null;

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

        public PrimitiveMutationBuffer getMutationBuffer() {
            return mutationBuffer;
        }

        public List<BlockMutation> getMutations() {
            if (legacyMutations == null) {
                if (mutationBuffer.isEmpty()) {
                    return Collections.emptyList();
                }
                legacyMutations = new ArrayList<>();
                int chunkBaseX = chunkX << 4;
                int chunkBaseZ = chunkZ << 4;
                for (int i = 0, sz = mutationBuffer.size(); i < sz; i++) {
                    int bMinX = mutationBuffer.minX(i);
                    int bMaxX = mutationBuffer.maxX(i);
                    int bMinZ = mutationBuffer.minZ(i);
                    int bMaxZ = mutationBuffer.maxZ(i);
                    int bMinY = mutationBuffer.minY(i);
                    int bMaxY = mutationBuffer.maxY(i);
                    int targetId = mutationBuffer.targetBlockId(i);
                    int filterId = mutationBuffer.filterBlockId(i);
                    byte[] raw = mutationBuffer.rawNbt(i);
                    BlockState targetState = MinecraftVoxelBridge.getBlockState(targetId);
                    BlockState filterState = (filterId >= 0) ? MinecraftVoxelBridge.getBlockState(filterId) : null;

                    for (int y = bMinY; y <= bMaxY; y++) {
                        for (int z = bMinZ; z <= bMaxZ; z++) {
                            for (int x = bMinX; x <= bMaxX; x++) {
                                legacyMutations.add(new BlockMutation(
                                        chunkBaseX | x, y, chunkBaseZ | z,
                                        targetId, targetState,
                                        raw, null,
                                        filterId, filterState
                                ));
                            }
                        }
                    }
                }
            }
            return legacyMutations;
        }

        public Map<Integer, VoxelSection> getWholeSections() {
            return wholeSections;
        }

        public void addBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int targetBlockId, int filterBlockId, byte[] rawNbt) {
            mutationBuffer.addBox(minX, minY, minZ, maxX, maxY, maxZ, targetBlockId, filterBlockId, rawNbt);
            legacyMutations = null;
        }

        public void addMutation(int localX, int worldY, int localZ, int targetBlockId, int filterBlockId, byte[] rawNbt) {
            addBox(localX, worldY, localZ, localX, worldY, localZ, targetBlockId, filterBlockId, rawNbt);
        }

        public void addMutation(BlockMutation mutation) {
            int lx = mutation.worldX() & 15;
            int ly = mutation.worldY();
            int lz = mutation.worldZ() & 15;
            addBox(lx, ly, lz, lx, ly, lz, mutation.targetBlockId(), mutation.filterBlockId(), mutation.rawNbt());
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
        synchronized (remainingChunkKeys) {
            remainingChunkKeys.add(key);
        }
        return chunkEditsMap.computeIfAbsent(key, k -> new ChunkEdits(chunkX, chunkZ));
    }

    /**
     * Retrieves the queued ChunkEdits for a specific chunk coordinate, if present.
     *
     * @param chunkX World chunk X
     * @param chunkZ World chunk Z
     * @return ChunkEdits instance or null
     */
    public ChunkEdits getChunkEdits(int chunkX, int chunkZ) {
        long key = (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
        return chunkEditsMap.get(key);
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
        edits.addBox(x & 15, y, z & 15, x & 15, y, z & 15, blockId, filterBlockId, rawNbt);
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
     * Enqueues an unconditional volumetric fill within absolute integer bounds.
     *
     * @param minX  Minimum X bound
     * @param minY  Minimum Y bound
     * @param minZ  Minimum Z bound
     * @param maxX  Maximum X bound
     * @param maxY  Maximum Y bound
     * @param maxZ  Maximum Z bound
     * @param state Target BlockState
     * @return this builder
     */
    public ChunkWriteBatch fill(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, BlockState state) {
        return fill(minX, minY, minZ, maxX, maxY, maxZ, state, null);
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
        boolean isFullCube = MinecraftVoxelBridge.isFullCube(state);

        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;
        int minSecY = clampedMinY >> 4;
        int maxSecY = clampedMaxY >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            int cMinX = Math.max(minX, cx << 4);
            int cMaxX = Math.min(maxX, (cx << 4) + 15);
            boolean fullSpanX = (cMinX == (cx << 4) && cMaxX == (cx << 4) + 15);
            int localMinX = cMinX & 15;
            int localMaxX = cMaxX & 15;

            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                int cMinZ = Math.max(minZ, cz << 4);
                int cMaxZ = Math.min(maxZ, (cz << 4) + 15);
                boolean fullSpanZ = (cMinZ == (cz << 4) && cMaxZ == (cz << 4) + 15);
                int localMinZ = cMinZ & 15;
                int localMaxZ = cMaxZ & 15;

                ChunkEdits edits = getOrCreateChunkEdits(cx, cz);

                if (replaceFilter == null && fullSpanX && fullSpanZ) {
                    // Check if there are whole sections enclosed
                    int firstFullSec = -1;
                    int lastFullSec = -1;
                    for (int secY = minSecY; secY <= maxSecY; secY++) {
                        int secBase = secY << 4;
                        if (clampedMinY <= secBase && clampedMaxY >= secBase + 15) {
                            if (firstFullSec == -1) firstFullSec = secY;
                            lastFullSec = secY;
                        }
                    }

                    if (firstFullSec == -1) {
                        // No full sections: single box for the entire vertical slice
                        edits.addBox(0, clampedMinY, 0, 15, clampedMaxY, 15, blockId, -1, null);
                        totalBlockCount += 16 * 16 * (clampedMaxY - clampedMinY + 1);
                    } else {
                        // 1. Bottom partial slice (if any)
                        int bottomTopY = (firstFullSec << 4) - 1;
                        if (clampedMinY <= bottomTopY) {
                            edits.addBox(0, clampedMinY, 0, 15, bottomTopY, 15, blockId, -1, null);
                            totalBlockCount += 16 * 16 * (bottomTopY - clampedMinY + 1);
                        }
                        // 2. Full sections
                        for (int secY = firstFullSec; secY <= lastFullSec; secY++) {
                            edits.setSection(secY, VoxelSection.createHomogeneous(blockId, isFullCube));
                            totalBlockCount += VoxelSection.VOXEL_COUNT;
                        }
                        // 3. Top partial slice (if any)
                        int topBottomY = (lastFullSec << 4) + 16;
                        if (clampedMaxY >= topBottomY) {
                            edits.addBox(0, topBottomY, 0, 15, clampedMaxY, 15, blockId, -1, null);
                            totalBlockCount += 16 * 16 * (clampedMaxY - topBottomY + 1);
                        }
                    }
                } else {
                    // Partial X/Z span or conditional filter: add a single 3D box for the entire column!
                    edits.addBox(localMinX, clampedMinY, localMinZ, localMaxX, clampedMaxY, localMaxZ, blockId, filterId, null);
                    totalBlockCount += (localMaxX - localMinX + 1) * (localMaxZ - localMinZ + 1) * (clampedMaxY - clampedMinY + 1);
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
    public long getTotalBlockCount() {
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
            PrimitiveMutationBuffer pmb = edits.getMutationBuffer();
            if (pmb != null && !pmb.isEmpty()) {
                for (int i = 0, sz = pmb.size(); i < sz; i++) {
                    int minY = pmb.minY(i);
                    int maxY = pmb.maxY(i);
                    if (minY < worldMinY || maxY > worldMaxY) {
                        return Optional.of(WriteResult.failure(
                                WriteStatus.FAIL_INVALID_COORDINATES,
                                cx, cz,
                                String.format("Block mutation bounds Y=[%d..%d] outside world bounds [%d..%d]",
                                        minY, maxY, worldMinY, worldMaxY)
                        ));
                    }
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
            if (opts.isStrict() && ChunkExclusivityGuard.isRegionActiveInRam(level, cx >> 5, cz >> 5)) {
                return Optional.of(WriteResult.failure(
                        WriteStatus.FAIL_CHUNK_LOADED_IN_RAM,
                        cx, cz,
                        String.format("Direct MCA write rejected by strict policy: region r.%d.%d.mca is currently active in RAM / RegionFileStorage", cx >> 5, cz >> 5)
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
            return CompletableFuture.completedFuture(new BatchWriteResult(0, 0, 0, 0L, 0, 0, 0L, List.of()));
        }

        WriteOptions opts = (options != null) ? options : WriteOptions.DEFAULT;
        this.activeOptions = opts;
        ACTIVE_BATCHES.add(this);
        synchronized (remainingChunkKeys) {
            remainingChunkKeys.clear();
            remainingChunkKeys.addAll(chunkEditsMap.keySet());
        }

        long totalBlocks = totalBlockCount;

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

        // 3. Partition chunks into RAM, Deferred (unloaded in hybrid/active region), and Pure Disk pools
        List<ChunkEdits> ramChunks = new ArrayList<>();
        List<ChunkEdits> deferredChunks = new ArrayList<>();
        Long2ObjectLinkedOpenHashMap<List<ChunkEdits>> pureDiskRegions = new Long2ObjectLinkedOpenHashMap<>();

        // Group by region first to check region activity once per region
        Long2ObjectLinkedOpenHashMap<List<ChunkEdits>> byRegion = new Long2ObjectLinkedOpenHashMap<>();
        for (ChunkEdits edits : sortedChunks) {
            int rx = edits.getChunkX() >> 5;
            int rz = edits.getChunkZ() >> 5;
            long rKey = ChunkPos.asLong(rx, rz);
            byRegion.computeIfAbsent(rKey, k -> new ArrayList<>()).add(edits);
        }

        for (Long2ObjectMap.Entry<List<ChunkEdits>> entry : byRegion.long2ObjectEntrySet()) {
            long rKey = entry.getLongKey();
            int rx = ChunkPos.getX(rKey);
            int rz = ChunkPos.getZ(rKey);
            List<ChunkEdits> chunkList = entry.getValue();

            boolean regionActive = !opts.isStrict() && ChunkExclusivityGuard.isRegionActiveInRam(level, rx, rz);

            if (!regionActive) {
                // Pure disk region: zero chunks in RAM, zero open RegionFile handles
                pureDiskRegions.put(rKey, chunkList);
            } else {
                // Hybrid or active region: partition individual chunks into RAM vs Deferred
                for (ChunkEdits edits : chunkList) {
                    boolean inRam = ChunkExclusivityGuard.isChunkLoadedInRam(level, edits.getChunkX(), edits.getChunkZ());
                    if (inRam) {
                        ramChunks.add(edits);
                    } else {
                        deferredChunks.add(edits);
                    }
                }
            }
        }

        List<CompletableFuture<List<WriteResult>>> batchFutures = new ArrayList<>();

        // 4. Dispatch RAM chunks on server thread
        if (!ramChunks.isEmpty()) {
            CompletableFuture<List<WriteResult>> ramFuture = new CompletableFuture<>();
            Runnable ramTask = () -> {
                List<WriteResult> resList = new ArrayList<>(ramChunks.size());
                MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);

                for (ChunkEdits edits : ramChunks) {
                    long t0 = System.nanoTime();
                    List<ChunkEditsApplicator.AppliedRamMutation> applied = new ArrayList<>();
                    try {
                        LevelChunk lc = (level instanceof ServerLevel sl)
                                ? sl.getChunkSource().getChunkNow(edits.getChunkX(), edits.getChunkZ())
                                : level.getChunk(edits.getChunkX(), edits.getChunkZ());
                        if (lc == null) {
                            if (opts.isUnified()) {
                                LOGGER.warn("Chunk ({}, {}) was scheduled for RAM mutation but is no longer loaded in RAM. Enqueueing into DeferredChunkQueue.",
                                        edits.getChunkX(), edits.getChunkZ());
                                DeferredChunkQueue.enqueue(level, edits);
                                resList.add(WriteResult.successDeferred(edits.getChunkX(), edits.getChunkZ(), System.nanoTime() - t0));
                                continue;
                            }
                            throw new IllegalStateException("Chunk (" + edits.getChunkX() + ", " + edits.getChunkZ() + ") is not loaded in RAM");
                        }
                        if (level instanceof ServerLevel sl) {
                            ChunkEditsApplicator.applyToLiveChunk(sl, lc, edits, applied);
                        } else {
                            ChunkEditsApplicator.applyToLoadingChunk(lc, edits);
                        }
                        long elapsed = System.nanoTime() - t0;
                        resList.add(WriteResult.successRam(edits.getChunkX(), edits.getChunkZ(), elapsed));
                    } catch (Throwable t) {
                        LOGGER.error("Error executing RAM mutation batch for chunk ({}, {}), rolling back: {}",
                                edits.getChunkX(), edits.getChunkZ(), t.getMessage(), t);
                        if (level instanceof ServerLevel sl) {
                            ChunkEditsApplicator.rollback(sl, applied);
                        }
                        resList.add(WriteResult.failure(WriteStatus.FAIL_IO_ERROR, edits.getChunkX(), edits.getChunkZ(), t.getMessage()));
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

            batchFutures.add(ramFuture.thenApply(list -> {
                synchronized (remainingChunkKeys) {
                    for (ChunkEdits ce : ramChunks) {
                        remainingChunkKeys.remove(ChunkPos.asLong(ce.getChunkX(), ce.getChunkZ()));
                    }
                }
                return list;
            }));
        }

        // 5. Enqueue Deferred chunks for hybrid regions
        if (!deferredChunks.isEmpty()) {
            List<WriteResult> defResults = new ArrayList<>(deferredChunks.size());
            for (ChunkEdits edits : deferredChunks) {
                long t0 = System.nanoTime();
                DeferredChunkQueue.enqueue(level, edits);
                long elapsed = System.nanoTime() - t0;
                defResults.add(WriteResult.successDeferred(edits.getChunkX(), edits.getChunkZ(), elapsed));
            }
            synchronized (remainingChunkKeys) {
                for (ChunkEdits edits : deferredChunks) {
                    remainingChunkKeys.remove(ChunkPos.asLong(edits.getChunkX(), edits.getChunkZ()));
                }
            }
            batchFutures.add(CompletableFuture.completedFuture(defResults));
        }

        // 6. Dispatch Pure Disk regions via Region-Batching if enabled
        MinecraftVoxelWriter writer = VoxelWriteAPI.getWriter(level);
        boolean useRegionBatching = com.pixel.qve.neoforge.config.QveConfig.REGION_BATCHING_ENABLED.get();

        if (writer != null && useRegionBatching && !pureDiskRegions.isEmpty()) {
            if (level instanceof ServerLevel sl) {
                List<Long> cachedKeys = new ArrayList<>();
                for (long rKey : pureDiskRegions.keySet()) {
                    int rx = ChunkPos.getX(rKey);
                    int rz = ChunkPos.getZ(rKey);
                    if (MinecraftRegionFileBridge.isRegionCached(sl, rx, rz)) {
                        cachedKeys.add(rKey);
                    }
                }
                if (!cachedKeys.isEmpty()) {
                    MinecraftRegionFileBridge.evictAndFlushRegions(sl, cachedKeys);
                }
            }

            for (Long2ObjectMap.Entry<List<ChunkEdits>> entry : pureDiskRegions.long2ObjectEntrySet()) {
                long rKey = entry.getLongKey();
                int rx = ChunkPos.getX(rKey);
                int rz = ChunkPos.getZ(rKey);
                List<ChunkEdits> regionChunks = entry.getValue();

                CompletableFuture<List<WriteResult>> regFuture = writer.writeRegionBatchAsync(rx, rz, regionChunks, opts);
                batchFutures.add(regFuture.thenApply(list -> {
                    synchronized (remainingChunkKeys) {
                        for (ChunkEdits ce : regionChunks) {
                            remainingChunkKeys.remove(ChunkPos.asLong(ce.getChunkX(), ce.getChunkZ()));
                        }
                    }
                    return list;
                }));
            }
        } else if (!pureDiskRegions.isEmpty()) {
            for (List<ChunkEdits> list : pureDiskRegions.values()) {
                List<CompletableFuture<WriteResult>> chunkFutures = new ArrayList<>(list.size());
                for (ChunkEdits edits : list) {
                    long key = ChunkPos.asLong(edits.getChunkX(), edits.getChunkZ());
                    chunkFutures.add(VoxelWriteAPI.writeChunkDirectAsync(level, edits.getChunkX(), edits.getChunkZ(), ctx -> {
                        for (Map.Entry<Integer, VoxelSection> secEntry : edits.getWholeSections().entrySet()) {
                            ctx.setSection(secEntry.getKey(), secEntry.getValue());
                        }
                        PrimitiveMutationBuffer pmb = edits.getMutationBuffer();
                        if (pmb != null && !pmb.isEmpty()) {
                            for (int mi = 0, sz = pmb.size(); mi < sz; mi++) {
                                int bMinX = pmb.minX(mi);
                                int bMaxX = pmb.maxX(mi);
                                int bMinZ = pmb.minZ(mi);
                                int bMaxZ = pmb.maxZ(mi);
                                int bMinY = pmb.minY(mi);
                                int bMaxY = pmb.maxY(mi);
                                int targetId = pmb.targetBlockId(mi);
                                int filterId = pmb.filterBlockId(mi);
                                byte[] rawNbt = pmb.rawNbt(mi);

                                for (int y = bMinY; y <= bMaxY; y++) {
                                    for (int z = bMinZ; z <= bMaxZ; z++) {
                                        for (int x = bMinX; x <= bMaxX; x++) {
                                            int curId = ctx.getBlock(x, y, z);
                                            if (filterId < 0 || curId == filterId) {
                                                ctx.setBlock(x, y, z, targetId);
                                                if (rawNbt != null) {
                                                    ctx.setBlockEntityRaw(x, y, z, rawNbt);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            for (BlockMutation m : edits.getMutations()) {
                                int curId = ctx.getBlock(m.worldX() & 15, m.worldY(), m.worldZ() & 15);
                                if (m.matchesFilter(curId, null)) {
                                    ctx.setBlock(m.worldX() & 15, m.worldY(), m.worldZ() & 15, m.targetBlockId());
                                    if (m.rawNbt() != null) {
                                        ctx.setBlockEntityRaw(m.worldX() & 15, m.worldY(), m.worldZ() & 15, m.rawNbt());
                                    }
                                }
                            }
                        }
                    }, opts).thenApply(res -> {
                        synchronized (remainingChunkKeys) {
                            remainingChunkKeys.remove(key);
                        }
                        return res;
                    }));
                }
                batchFutures.add(CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0])).thenApply(v -> {
                    List<WriteResult> resList = new ArrayList<>(chunkFutures.size());
                    for (CompletableFuture<WriteResult> cf : chunkFutures) {
                        resList.add(cf.join());
                    }
                    return resList;
                }));
            }
        }

        // 7. Aggregate all results into BatchWriteResult and deregister active batch
        return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    ACTIVE_BATCHES.remove(this);
                    synchronized (remainingChunkKeys) {
                        remainingChunkKeys.clear();
                    }
                    List<WriteResult> results = new ArrayList<>(chunkEditsMap.size());
                    int succeeded = 0;
                    int failed = 0;
                    for (CompletableFuture<List<WriteResult>> f : batchFutures) {
                        List<WriteResult> list = f.join();
                        for (WriteResult res : list) {
                            results.add(res);
                            if (res.isSuccess()) {
                                succeeded++;
                            } else {
                                failed++;
                            }
                        }
                    }
                    long duration = System.nanoTime() - startTime;
                    int diskCount = chunkEditsMap.size() - ramChunks.size();
                    return new BatchWriteResult(
                            results.size(), succeeded, failed,
                            totalBlocks, ramChunks.size(), diskCount,
                            duration, results
                    );
                });
    }

    private CompletableFuture<WriteResult> applyChunkToRamAsync(ChunkEdits edits) {
        long startTime = System.nanoTime();
        CompletableFuture<WriteResult> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                for (BlockMutation m : edits.getMutations()) {
                    BlockPos pos = new BlockPos(m.worldX(), m.worldY(), m.worldZ());
                    BlockState cur = level.getBlockState(pos);
                    if (m.matchesFilter(-1, cur)) {
                        boolean placed = level.setBlock(pos, m.targetState(), 2 | 16);
                        if (!placed) {
                            throw new IllegalStateException("Failed to place block in RAM at " + pos + " with state " + m.targetState());
                        }
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
