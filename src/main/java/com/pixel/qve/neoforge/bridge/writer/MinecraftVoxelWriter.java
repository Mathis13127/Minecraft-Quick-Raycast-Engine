package com.pixel.qve.neoforge.bridge.writer;

import com.pixel.qve.mca.writer.IChunkWriteContext;
import com.pixel.qve.mca.writer.McaRegionWriter;
import com.pixel.qve.mca.writer.McaWriteCoordinator;
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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

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
        long startTime = System.nanoTime();

        // 1. Assert mutual exclusion
        try {
            ChunkExclusivityGuard.assertSafeForDirectDiskWrite(level, chunkX, chunkZ);
        } catch (ChunkExclusivityGuard.ChunkLoadedInRamException e) {
            return CompletableFuture.completedFuture(
                    WriteResult.failure(WriteStatus.FAIL_CHUNK_LOADED_IN_RAM, chunkX, chunkZ, e.getMessage())
            );
        }

        if (coordinator == null) {
            return CompletableFuture.completedFuture(
                    WriteResult.failure(WriteStatus.FAIL_IO_ERROR, chunkX, chunkZ, "Region directory unavailable for dimension " + level.dimension().location())
            );
        }

        // 2. Prepare Context (pre-loading existing sections if modifying existing chunk)
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
            // Direct disk fallback: verify if the chunk already exists in the .mca file to avoid wiping terrain
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

        // 3. Apply caller modifications
        try {
            modifier.accept(context);
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
     * Sets a block in a RAM-loaded chunk on the server thread.
     */
    public CompletableFuture<WriteResult> setBlockRamAsync(BlockPos pos, BlockState state, int flags, CompoundTag blockEntityNbt) {
        long startTime = System.nanoTime();
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;

        CompletableFuture<WriteResult> future = new CompletableFuture<>();

        Runnable task = () -> {
            try {
                level.setBlock(pos, state, flags);
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
