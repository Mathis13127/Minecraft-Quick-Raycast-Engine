package com.pixel.qve.neoforge.recovery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.pixel.qve.mca.writer.WriteOptions;
import com.pixel.qve.mca.writer.WriteOptions.CreationPolicy;
import com.pixel.qve.mca.writer.WriteOptions.ExecutionPolicy;
import com.pixel.qve.neoforge.api.ChunkWriteBatch;
import com.pixel.qve.neoforge.api.ChunkWriteBatch.BlockMutation;
import com.pixel.qve.neoforge.api.ChunkWriteBatch.ChunkEdits;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Journaling service that persists incomplete voxel write batches to a temporary JSON file upon server shutdown,
 * and automatically recovers and executes them when the world restarts.
 */
public final class BatchRecoveryJournal {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchRecoveryJournal.class);
    private static final String JOURNAL_FILENAME = "qve_recovery_queue.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record JournalData(
            int version,
            long timestamp,
            List<JournalBatchEntry> batches
    ) {}

    public record JournalBatchEntry(
            String dimension,
            String executionPolicy,
            String creationPolicy,
            List<JournalChunkEntry> chunks
    ) {}

    public record JournalChunkEntry(
            int chunkX,
            int chunkZ,
            List<JournalMutationEntry> mutations
    ) {}

    public record JournalMutationEntry(
            int x, int y, int z,
            String block,
            String filter,
            String nbt
    ) {}

    private BatchRecoveryJournal() {}

    /**
     * Resolves the recovery journal path inside the root world storage directory.
     *
     * @param server MinecraftServer instance
     * @return Path to qve_recovery_queue.json
     */
    public static Path getJournalPath(MinecraftServer server) {
        if (server == null) return null;
        try {
            return server.getWorldPath(LevelResource.ROOT).resolve(JOURNAL_FILENAME);
        } catch (Throwable t) {
            LOGGER.error("Failed to resolve recovery journal path: {}", t.getMessage());
            return null;
        }
    }

    /**
     * Saves a list of incomplete batches to disk before server shutdown.
     *
     * @param server  MinecraftServer instance
     * @param batches Active batches containing uncompleted chunk edits
     * @return Number of chunks preserved in journal
     */
    public static int savePendingBatches(MinecraftServer server, List<ChunkWriteBatch> batches) {
        Path path = getJournalPath(server);
        if (path == null || batches == null || batches.isEmpty()) {
            return 0;
        }

        List<JournalBatchEntry> batchEntries = new ArrayList<>();
        int totalSavedChunks = 0;

        for (ChunkWriteBatch batch : batches) {
            Level level = batch.getLevel();
            if (level == null) continue;

            String dimStr = level.dimension().location().toString();
            WriteOptions opts = batch.getActiveOptions();
            String execPol = (opts != null) ? opts.executionPolicy().name() : ExecutionPolicy.UNIFIED.name();
            String creatPol = (opts != null) ? opts.creationPolicy().name() : CreationPolicy.CREATE_IF_MISSING.name();

            List<ChunkEdits> pendingChunks = batch.getPendingChunkEdits();
            if (pendingChunks.isEmpty()) continue;

            List<JournalChunkEntry> chunkEntries = new ArrayList<>(pendingChunks.size());
            for (ChunkEdits edits : pendingChunks) {
                List<JournalMutationEntry> mutationEntries = new ArrayList<>(edits.getMutations().size());
                for (BlockMutation m : edits.getMutations()) {
                    String blockStr = BuiltInRegistries.BLOCK.getKey(m.targetState().getBlock()).toString();
                    String filterStr = (m.filterState() != null)
                            ? BuiltInRegistries.BLOCK.getKey(m.filterState().getBlock()).toString()
                            : null;
                    String nbtStr = (m.tagNbt() != null) ? m.tagNbt().toString() : null;
                    mutationEntries.add(new JournalMutationEntry(m.worldX(), m.worldY(), m.worldZ(), blockStr, filterStr, nbtStr));
                }
                chunkEntries.add(new JournalChunkEntry(edits.getChunkX(), edits.getChunkZ(), mutationEntries));
                totalSavedChunks++;
            }

            if (!chunkEntries.isEmpty()) {
                batchEntries.add(new JournalBatchEntry(dimStr, execPol, creatPol, chunkEntries));
            }
        }

        if (batchEntries.isEmpty()) {
            return 0;
        }

        JournalData data = new JournalData(1, System.currentTimeMillis(), batchEntries);
        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(data, writer);
            LOGGER.info("[QVE] Successfully saved {} pending chunks across {} batch(es) to recovery journal: {}",
                    totalSavedChunks, batchEntries.size(), path.getFileName());
            return totalSavedChunks;
        } catch (IOException e) {
            LOGGER.error("[QVE] Failed to write recovery journal to {}: {}", path, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Checks if a recovery journal exists on disk and resumes all pending batches.
     *
     * @param server MinecraftServer instance
     * @return Number of resumed batches, or 0 if none
     */
    public static int resumeBatchesIfPresent(MinecraftServer server) {
        Path path = getJournalPath(server);
        if (path == null || !Files.isRegularFile(path)) {
            return 0;
        }

        JournalData data;
        try (Reader reader = Files.newBufferedReader(path)) {
            data = GSON.fromJson(reader, JournalData.class);
        } catch (Exception e) {
            LOGGER.error("[QVE] Failed to read recovery journal from {}: {}", path, e.getMessage(), e);
            return 0;
        }

        if (data == null || data.batches() == null || data.batches().isEmpty()) {
            deleteJournal(server);
            return 0;
        }

        LOGGER.info("[QVE] Found recovery journal from {} with {} batch(es). Resuming operations...",
                new Date(data.timestamp()), data.batches().size());

        int resumedCount = 0;
        for (JournalBatchEntry batchEntry : data.batches()) {
            ResourceLocation dimLoc = ResourceLocation.tryParse(batchEntry.dimension());
            if (dimLoc == null) {
                LOGGER.warn("[QVE] Skipping recovery batch with invalid dimension ID: {}", batchEntry.dimension());
                continue;
            }

            ResourceKey<Level> dimKey = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimLoc);
            ServerLevel targetLevel = server.getLevel(dimKey);
            if (targetLevel == null) {
                LOGGER.warn("[QVE] Target dimension {} is not currently loaded on server. Cannot resume batch.", dimLoc);
                continue;
            }

            ExecutionPolicy execPol;
            try {
                execPol = ExecutionPolicy.valueOf(batchEntry.executionPolicy());
            } catch (Exception ignored) {
                execPol = ExecutionPolicy.UNIFIED;
            }

            CreationPolicy creatPol;
            try {
                creatPol = CreationPolicy.valueOf(batchEntry.creationPolicy());
            } catch (Exception ignored) {
                creatPol = CreationPolicy.CREATE_IF_MISSING;
            }

            WriteOptions options = new WriteOptions(execPol, creatPol);
            ChunkWriteBatch batch = new ChunkWriteBatch(targetLevel);

            for (JournalChunkEntry chunkEntry : batchEntry.chunks()) {
                for (JournalMutationEntry m : chunkEntry.mutations()) {
                    ResourceLocation blockLoc = ResourceLocation.tryParse(m.block());
                    Block block = (blockLoc != null) ? BuiltInRegistries.BLOCK.get(blockLoc) : null;
                    if (block == null) continue;

                    BlockState state = block.defaultBlockState();
                    BlockState filterState = null;
                    if (m.filter() != null) {
                        ResourceLocation filterLoc = ResourceLocation.tryParse(m.filter());
                        Block filterBlock = (filterLoc != null) ? BuiltInRegistries.BLOCK.get(filterLoc) : null;
                        if (filterBlock != null) {
                            filterState = filterBlock.defaultBlockState();
                        }
                    }

                    CompoundTag tagNbt = null;
                    if (m.nbt() != null && !m.nbt().isEmpty()) {
                        try {
                            tagNbt = TagParser.parseTag(m.nbt());
                        } catch (Exception e) {
                            LOGGER.warn("[QVE] Failed to parse recovered NBT for block at ({}, {}, {}): {}",
                                    m.x(), m.y(), m.z(), e.getMessage());
                        }
                    }

                    batch.setBlock(new BlockPos(m.x(), m.y(), m.z()), state, tagNbt, filterState);
                }
            }

            if (batch.getTotalBlockCount() > 0) {
                LOGGER.info("[QVE] Submitting resumed batch for dimension {} with {} blocks across {} chunks (Policy: {})...",
                        dimLoc, batch.getTotalBlockCount(), batch.getAffectedChunkCount(), options.isStrict() ? "Strict" : "Unified");
                batch.executeAsync(options).thenAccept(res -> {
                    if (res.isAllSuccessful()) {
                        LOGGER.info("[QVE] Resumed batch for {} SUCCESS: {} blocks placed in {:.2f} ms ({:.0f} voxels/s).",
                                dimLoc, res.totalBlocks(), res.durationMs(), res.throughputBlocksPerSecond());
                    } else {
                        LOGGER.error("[QVE] Resumed batch for {} encountered failures: {}/{} chunks failed.",
                                dimLoc, res.totalFailed(), res.totalSubmitted());
                    }
                });
                resumedCount++;
            }
        }

        // Clean up journal file once resumed
        deleteJournal(server);
        return resumedCount;
    }

    /**
     * Deletes the recovery journal file from disk.
     *
     * @param server MinecraftServer instance
     */
    public static void deleteJournal(MinecraftServer server) {
        Path path = getJournalPath(server);
        if (path != null && Files.exists(path)) {
            try {
                Files.deleteIfExists(path);
                LOGGER.info("[QVE] Cleaned up recovery journal file: {}", path.getFileName());
            } catch (IOException e) {
                LOGGER.warn("[QVE] Failed to delete recovery journal {}: {}", path, e.getMessage());
            }
        }
    }
}
