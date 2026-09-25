package com.pixel.qve.neoforge.api;

import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelGrid;
import com.pixel.qve.neoforge.bridge.writer.ChunkExclusivityGuard;
import com.pixel.qve.world.VoxelSection;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Universal Heterogeneous Voxel Buffer for high-throughput multi-block, volumetric, and multi-chunk modifications.
 * Automatically buffers and partitions disparate block placements across arbitrary world coordinates,
 * routing transparently between active server RAM and offline Anvil (.mca) region files.
 */
public final class ChunkWriteBatch {

    private final Level level;
    private final Map<Long, ChunkEdits> chunkEditsMap = new LinkedHashMap<>();
    private int totalBlockCount = 0;

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
        int cx = x >> 4;
        int cz = z >> 4;
        int blockId = MinecraftVoxelBridge.getBlockId(state);

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
        edits.addMutation(new BlockMutation(x, y, z, blockId, state, rawNbt, blockEntityNbt, -1, null));
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
     * Executes the batch asynchronously using default {@link VoxelWriteMode#UNIFIED} routing.
     *
     * @return CompletableFuture completing with BatchWriteResult
     */
    public CompletableFuture<BatchWriteResult> executeAsync() {
        return executeAsync(VoxelWriteMode.UNIFIED);
    }

    /**
     * Executes the batch asynchronously with the specified write mode.
     *
     * @param mode Target write mode (UNIFIED or STRICT_DIRECT)
     * @return CompletableFuture completing with BatchWriteResult
     */
    public CompletableFuture<BatchWriteResult> executeAsync(VoxelWriteMode mode) {
        long startTime = System.nanoTime();
        if (chunkEditsMap.isEmpty()) {
            return CompletableFuture.completedFuture(new BatchWriteResult(0, 0, 0, 0, 0, 0, 0L, List.of()));
        }

        int totalBlocks = totalBlockCount;

        // 1. Strict Direct pre-flight mutual exclusion check
        if (mode == VoxelWriteMode.STRICT_DIRECT) {
            for (ChunkEdits edits : chunkEditsMap.values()) {
                if (ChunkExclusivityGuard.isChunkLoadedInRam(level, edits.chunkX, edits.chunkZ)) {
                    WriteResult failure = WriteResult.failure(
                            WriteStatus.FAIL_CHUNK_LOADED_IN_RAM,
                            edits.chunkX, edits.chunkZ,
                            "Chunk (" + edits.chunkX + ", " + edits.chunkZ + ") is currently loaded in RAM"
                    );
                    return CompletableFuture.completedFuture(
                            new BatchWriteResult(chunkEditsMap.size(), 0, chunkEditsMap.size(), totalBlocks, 0, 0,
                                    System.nanoTime() - startTime, List.of(failure))
                    );
                }
            }
        }

        // 2. Partition chunks into RAM and Disk pools
        List<ChunkEdits> ramChunks = new ArrayList<>();
        List<ChunkEdits> diskChunks = new ArrayList<>();

        for (ChunkEdits edits : chunkEditsMap.values()) {
            boolean isRam = (mode == VoxelWriteMode.UNIFIED) && ChunkExclusivityGuard.isChunkLoadedInRam(level, edits.chunkX, edits.chunkZ);
            if (isRam) {
                ramChunks.add(edits);
            } else {
                diskChunks.add(edits);
            }
        }

        List<CompletableFuture<WriteResult>> futures = new ArrayList<>(chunkEditsMap.size());

        // 3. Dispatch RAM chunks on server thread
        if (!ramChunks.isEmpty()) {
            CompletableFuture<List<WriteResult>> ramFuture = new CompletableFuture<>();
            Runnable ramTask = () -> {
                List<WriteResult> resList = new ArrayList<>(ramChunks.size());
                MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);

                for (ChunkEdits edits : ramChunks) {
                    long t0 = System.nanoTime();
                    try {
                        for (BlockMutation m : edits.mutations) {
                            BlockPos pos = new BlockPos(m.worldX(), m.worldY(), m.worldZ());
                            BlockState cur = level.getBlockState(pos);
                            if (m.matchesFilter(-1, cur)) {
                                level.setBlock(pos, m.targetState(), 2);
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
                futures.add(ramFuture.thenApply(list -> list.get(idx)));
            }
        }

        // 4. Dispatch Disk chunks via direct MCA coordinator
        for (ChunkEdits edits : diskChunks) {
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
            }));
        }

        // 5. Aggregate all results into BatchWriteResult
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
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

    /**
     * Backward-compatible alias executing strictly in direct disk mode.
     */
    public CompletableFuture<BatchWriteResult> executeDirectAsync() {
        return executeAsync(VoxelWriteMode.STRICT_DIRECT);
    }

    /**
     * Backward-compatible alias executing in unified mode.
     */
    public CompletableFuture<BatchWriteResult> executeUnifiedAsync() {
        return executeAsync(VoxelWriteMode.UNIFIED);
    }
}
