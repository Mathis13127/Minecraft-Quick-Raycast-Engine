package com.pixel.qve.neoforge.api;

import com.pixel.qve.mca.writer.IChunkWriteContext;
import com.pixel.qve.mca.writer.WriteOptions;
import com.pixel.qve.neoforge.bridge.writer.ChunkExclusivityGuard;
import com.pixel.qve.neoforge.bridge.writer.MinecraftVoxelWriter;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Dedicated high-performance facade for writing blocks and chunks in Minecraft / NeoForge.
 * Seamlessly provides both transparent unified writes (routing to active RAM or offline MCA disk)
 * and strict direct disk writes for unloaded Anvil region files on background worker threads.
 */
public final class VoxelWriteAPI {

    private static final Map<ResourceKey<Level>, MinecraftVoxelWriter> WRITERS = new ConcurrentHashMap<>();

    private VoxelWriteAPI() {}

    /**
     * Retrieves or creates the MinecraftVoxelWriter instance for the given Level.
     *
     * @param level Minecraft Level
     * @return MinecraftVoxelWriter instance
     */
    public static MinecraftVoxelWriter getWriter(Level level) {
        if (level == null || level.dimension() == null) {
            return null;
        }
        return WRITERS.computeIfAbsent(level.dimension(), k -> {
            Path regionDir = MinecraftVoxelWriter.resolveRegionDirectory(level);
            return new MinecraftVoxelWriter(level, regionDir);
        });
    }

    // ==========================================
    // 1. Unified Transparent Writes (RAM or Disk)
    // ==========================================

    /**
     * Modifies a block without concern for whether its chunk is loaded in RAM or unloaded on disk.
     * If loaded in RAM -&gt; sets block on server thread and marks chunk dirty.
     * If unloaded on disk -&gt; writes directly into Anvil (.mca) region file via I/O thread pool.
     *
     * @param level Minecraft Level
     * @param pos   Absolute BlockPos
     * @param state Target BlockState
     * @return CompletableFuture completing with WriteResult
     */
    public static CompletableFuture<WriteResult> setBlockUnifiedAsync(Level level, BlockPos pos, BlockState state) {
        return setBlockUnifiedAsync(level, pos, state, 3, null);
    }

    /**
     * Modifies a block with custom BlockEntity NBT without concern for RAM vs Disk chunk state.
     *
     * @param level          Minecraft Level
     * @param pos            Absolute BlockPos
     * @param state          Target BlockState
     * @param flags          Block update flags
     * @param blockEntityNbt Optional BlockEntity NBT compound
     * @return CompletableFuture completing with WriteResult
     */
    public static CompletableFuture<WriteResult> setBlockUnifiedAsync(Level level, BlockPos pos, BlockState state, int flags, CompoundTag blockEntityNbt) {
        if (level == null || pos == null || state == null) {
            return CompletableFuture.completedFuture(
                    WriteResult.failure(WriteStatus.FAIL_INVALID_COORDINATES, 0, 0, "Arguments cannot be null")
            );
        }
        return getWriter(level).setBlockUnifiedAsync(pos, state, flags, blockEntityNbt);
    }

    /**
     * Modifies an existing chunk OR creates a brand new chunk ex-nihilo,
     * routing automatically to RAM if loaded or to MCA disk if unloaded.
     *
     * @param level    Minecraft Level
     * @param chunkX   World chunk X
     * @param chunkZ   World chunk Z
     * @param modifier Consumer modifying sections, blocks, or block entities
     * @return CompletableFuture completing with WriteResult
     */
    public static CompletableFuture<WriteResult> modifyChunkUnifiedAsync(Level level, int chunkX, int chunkZ, Consumer<IChunkWriteContext> modifier) {
        if (level == null || modifier == null) {
            return CompletableFuture.completedFuture(
                    WriteResult.failure(WriteStatus.FAIL_INVALID_COORDINATES, chunkX, chunkZ, "Arguments cannot be null")
            );
        }
        return getWriter(level).modifyChunkUnifiedAsync(chunkX, chunkZ, modifier);
    }

    // ==========================================
    // 2. Strict Unloaded Direct Disk Writes (.MCA)
    // ==========================================

    /**
     * Sets a block directly on disk in an UNLOADED chunk.
     * Strictly fails with {@link WriteStatus#FAIL_CHUNK_LOADED_IN_RAM} if the chunk is currently in RAM.
     *
     * @param level          Minecraft Level
     * @param pos            Absolute BlockPos
     * @param state          Target BlockState
     * @param blockEntityNbt Optional BlockEntity NBT
     * @return CompletableFuture completing with WriteResult
     */
    public static CompletableFuture<WriteResult> setBlockDirectAsync(Level level, BlockPos pos, BlockState state, CompoundTag blockEntityNbt) {
        if (level == null || pos == null || state == null) {
            return CompletableFuture.completedFuture(
                    WriteResult.failure(WriteStatus.FAIL_INVALID_COORDINATES, 0, 0, "Arguments cannot be null")
            );
        }
        return getWriter(level).setBlockDirectAsync(pos, state, blockEntityNbt);
    }

    /**
     * Creates a new chunk OR modifies an existing UNLOADED chunk directly in an Anvil (.mca) region file.
     * If the r.X.Z.mca file does not exist yet on disk, it is automatically created with an 8KB Anvil header.
     * Strictly fails with {@link WriteStatus#FAIL_CHUNK_LOADED_IN_RAM} if the chunk is currently in RAM.
     *
     * @param level    Minecraft Level
     * @param chunkX   World chunk X
     * @param chunkZ   World chunk Z
     * @param modifier Consumer modifying sections, blocks, or block entities
     * @return CompletableFuture completing with WriteResult
     */
    public static CompletableFuture<WriteResult> writeChunkDirectAsync(Level level, int chunkX, int chunkZ, Consumer<IChunkWriteContext> modifier) {
        return writeChunkDirectAsync(level, chunkX, chunkZ, modifier, WriteOptions.STRICT);
    }

    /**
     * Creates a new chunk OR modifies an existing UNLOADED chunk directly in an Anvil (.mca) region file using WriteOptions.
     *
     * @param level    Minecraft Level
     * @param chunkX   World chunk X
     * @param chunkZ   World chunk Z
     * @param modifier Consumer modifying sections, blocks, or block entities
     * @param options  WriteOptions governing execution and creation policies
     * @return CompletableFuture completing with WriteResult
     */
    public static CompletableFuture<WriteResult> writeChunkDirectAsync(Level level, int chunkX, int chunkZ, Consumer<IChunkWriteContext> modifier, WriteOptions options) {
        if (level == null || modifier == null) {
            return CompletableFuture.completedFuture(
                    WriteResult.failure(WriteStatus.FAIL_INVALID_COORDINATES, chunkX, chunkZ, "Arguments cannot be null")
            );
        }
        return getWriter(level).writeChunkDirectAsync(chunkX, chunkZ, modifier, options);
    }

    /**
     * Submits a multi-chunk batch for parallel direct disk writes across region worker threads.
     *
     * @param level Minecraft Level
     * @param batch ChunkWriteBatch containing modifications
     * @return CompletableFuture completing with BatchWriteResult
     */
    public static CompletableFuture<BatchWriteResult> writeBatchDirectAsync(Level level, ChunkWriteBatch batch) {
        if (level == null || batch == null) {
            return CompletableFuture.completedFuture(new BatchWriteResult(0, 0, 0, 0L, java.util.List.of()));
        }
        return batch.executeDirectAsync();
    }

    // ==========================================
    // 3. Status & Safety Checks
    // ==========================================

    /**
     * Checks if a chunk is currently active or loaded in RAM.
     *
     * @param level  Minecraft Level
     * @param chunkX World chunk X
     * @param chunkZ World chunk Z
     * @return True if chunk is in RAM
     */
    public static boolean isLoadedInRam(Level level, int chunkX, int chunkZ) {
        return ChunkExclusivityGuard.isChunkLoadedInRam(level, chunkX, chunkZ);
    }

    /**
     * Checks if a chunk is safe for direct disk writing (i.e. strictly UNLOADED in RAM).
     *
     * @param level  Minecraft Level
     * @param chunkX World chunk X
     * @param chunkZ World chunk Z
     * @return True if safe for direct disk write
     */
    public static boolean isSafeForDirectDiskWrite(Level level, int chunkX, int chunkZ) {
        return ChunkExclusivityGuard.isSafeForDirectDiskWrite(level, chunkX, chunkZ);
    }

    // ==========================================
    // 4. Batch Factory
    // ==========================================

    /**
     * Creates a new ChunkWriteBatch for fluent multi-chunk assembly.
     *
     * @param level Minecraft Level
     * @return ChunkWriteBatch builder
     */
    public static ChunkWriteBatch createBatch(Level level) {
        return new ChunkWriteBatch(level);
    }

    // ==========================================
    // 5. Unified Routing Facade (with VoxelWriteMode flag)
    // ==========================================

    /**
     * Modifies a block with the specified write mode.
     *
     * @param level Minecraft Level
     * @param pos   Absolute BlockPos
     * @param state Target BlockState
     * @param mode  Target write mode (UNIFIED or STRICT_DIRECT)
     * @return CompletableFuture completing with WriteResult
     */
    public static CompletableFuture<WriteResult> setBlockAsync(Level level, BlockPos pos, BlockState state, VoxelWriteMode mode) {
        return setBlockAsync(level, pos, state, 3, null, mode);
    }

    /**
     * Modifies a block with custom BlockEntity NBT using the specified write mode.
     *
     * @param level          Minecraft Level
     * @param pos            Absolute BlockPos
     * @param state          Target BlockState
     * @param flags          Block update flags
     * @param blockEntityNbt Optional BlockEntity NBT compound
     * @param mode           Target write mode (UNIFIED or STRICT_DIRECT)
     * @return CompletableFuture completing with WriteResult
     */
    public static CompletableFuture<WriteResult> setBlockAsync(Level level, BlockPos pos, BlockState state, int flags, CompoundTag blockEntityNbt, VoxelWriteMode mode) {
        if (mode == VoxelWriteMode.STRICT_DIRECT) {
            return setBlockDirectAsync(level, pos, state, blockEntityNbt);
        } else {
            return setBlockUnifiedAsync(level, pos, state, flags, blockEntityNbt);
        }
    }

    /**
     * Fills an axis-aligned bounding volume with the given block using default {@link WriteOptions#DEFAULT}.
     *
     * @param level Minecraft Level
     * @param from  First corner
     * @param to    Opposite corner
     * @param state Target BlockState
     * @return CompletableFuture completing with BatchWriteResult
     */
    public static CompletableFuture<BatchWriteResult> fillAsync(Level level, BlockPos from, BlockPos to, BlockState state) {
        return fillAsync(level, from, to, state, null, WriteOptions.DEFAULT);
    }

    /**
     * Fills an axis-aligned bounding volume with the given block using the specified write mode.
     *
     * @param level Minecraft Level
     * @param from  First corner
     * @param to    Opposite corner
     * @param state Target BlockState
     * @param mode  Target write mode
     * @return CompletableFuture completing with BatchWriteResult
     */
    public static CompletableFuture<BatchWriteResult> fillAsync(Level level, BlockPos from, BlockPos to, BlockState state, VoxelWriteMode mode) {
        return fillAsync(level, from, to, state, null, mode);
    }

    /**
     * Fills an axis-aligned bounding volume with the given block using the specified WriteOptions.
     *
     * @param level   Minecraft Level
     * @param from    First corner
     * @param to      Opposite corner
     * @param state   Target BlockState
     * @param options Target WriteOptions
     * @return CompletableFuture completing with BatchWriteResult
     */
    public static CompletableFuture<BatchWriteResult> fillAsync(Level level, BlockPos from, BlockPos to, BlockState state, WriteOptions options) {
        return fillAsync(level, from, to, state, null, options);
    }

    /**
     * Conditionally fills an axis-aligned bounding volume, replacing only matching blocks.
     *
     * @param level         Minecraft Level
     * @param from          First corner
     * @param to            Opposite corner
     * @param state         Target BlockState
     * @param replaceFilter Filter BlockState to replace, or null for unconditional fill
     * @param mode          Target write mode
     * @return CompletableFuture completing with BatchWriteResult
     */
    public static CompletableFuture<BatchWriteResult> fillAsync(Level level, BlockPos from, BlockPos to, BlockState state, BlockState replaceFilter, VoxelWriteMode mode) {
        WriteOptions opts = (mode == VoxelWriteMode.STRICT_DIRECT) ? WriteOptions.STRICT : WriteOptions.DEFAULT;
        return fillAsync(level, from, to, state, replaceFilter, opts);
    }

    /**
     * Conditionally fills an axis-aligned bounding volume using the specified WriteOptions.
     *
     * @param level         Minecraft Level
     * @param from          First corner
     * @param to            Opposite corner
     * @param state         Target BlockState
     * @param replaceFilter Filter BlockState to replace, or null for unconditional fill
     * @param options       Target WriteOptions
     * @return CompletableFuture completing with BatchWriteResult
     */
    public static CompletableFuture<BatchWriteResult> fillAsync(Level level, BlockPos from, BlockPos to, BlockState state, BlockState replaceFilter, WriteOptions options) {
        if (level == null || from == null || to == null || state == null) {
            return CompletableFuture.completedFuture(new BatchWriteResult(0, 0, 0, 0, 0, 0, 0L, java.util.List.of()));
        }
        ChunkWriteBatch batch = new ChunkWriteBatch(level);
        batch.fill(from, to, state, replaceFilter);
        return batch.executeAsync(options);
    }

    /**
     * Executes a pre-configured ChunkWriteBatch with default {@link WriteOptions#DEFAULT}.
     *
     * @param level Minecraft Level
     * @param batch ChunkWriteBatch instance
     * @return CompletableFuture completing with BatchWriteResult
     */
    public static CompletableFuture<BatchWriteResult> executeBatchAsync(Level level, ChunkWriteBatch batch) {
        return executeBatchAsync(level, batch, WriteOptions.DEFAULT);
    }

    /**
     * Executes a pre-configured ChunkWriteBatch with the specified write mode.
     *
     * @param level Minecraft Level
     * @param batch ChunkWriteBatch instance
     * @param mode  Target write mode
     * @return CompletableFuture completing with BatchWriteResult
     */
    public static CompletableFuture<BatchWriteResult> executeBatchAsync(Level level, ChunkWriteBatch batch, VoxelWriteMode mode) {
        WriteOptions opts = (mode == VoxelWriteMode.STRICT_DIRECT) ? WriteOptions.STRICT : WriteOptions.DEFAULT;
        return executeBatchAsync(level, batch, opts);
    }

    /**
     * Executes a pre-configured ChunkWriteBatch with the specified WriteOptions.
     *
     * @param level   Minecraft Level
     * @param batch   ChunkWriteBatch instance
     * @param options Target WriteOptions
     * @return CompletableFuture completing with BatchWriteResult
     */
    public static CompletableFuture<BatchWriteResult> executeBatchAsync(Level level, ChunkWriteBatch batch, WriteOptions options) {
        if (batch == null) {
            return CompletableFuture.completedFuture(new BatchWriteResult(0, 0, 0, 0, 0, 0, 0L, java.util.List.of()));
        }
        return batch.executeAsync(options);
    }

    /**
     * Cleans up all writer coordinators and flushes files on server stop or level unload.
     */
    public static synchronized void reset() {
        for (MinecraftVoxelWriter writer : WRITERS.values()) {
            try {
                writer.close();
            } catch (Exception ignored) {
            }
        }
        WRITERS.clear();
        com.pixel.qve.mca.writer.VoxelDiskWriterThreadPool.shutdown();
    }
}
