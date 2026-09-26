package com.pixel.qve.neoforge.bridge.writer;

import com.pixel.qve.neoforge.api.ChunkWriteBatch;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelGrid;
import com.pixel.qve.world.VoxelSection;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * High-performance, zero-allocation applicator for applying {@link ChunkWriteBatch.ChunkEdits}
 * directly to a {@link LevelChunk} in memory.
 * <p>
 * Unifies RAM mutation logic between active server thread operations and asynchronous
 * {@link DeferredChunkQueue} chunk load interceptions, eliminating code duplication and GC churn.
 */
public final class ChunkEditsApplicator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkEditsApplicator.class);

    /**
     * Record capturing a mutation applied to RAM for transactional rollback in case of error.
     */
    public record AppliedRamMutation(long packedPos, BlockState previousState, CompoundTag previousBeNbt) {
        public BlockPos pos() {
            return BlockPos.of(packedPos);
        }
    }

    private ChunkEditsApplicator() {}

    /**
     * Applies edits to an actively loaded chunk in live gameplay on the server thread.
     * Enforces client networking flags (2 | 16 | 128) and records previous states for rollback if requested.
     *
     * @param level          ServerLevel instance
     * @param chunk          Target LevelChunk
     * @param edits          ChunkEdits container
     * @param rollbackRecord Optional list to collect previous states for rollback (may be null)
     * @return Number of blocks successfully mutated
     */
    public static int applyToLiveChunk(ServerLevel level, LevelChunk chunk, ChunkWriteBatch.ChunkEdits edits, List<AppliedRamMutation> rollbackRecord) {
        if (level == null || chunk == null || edits == null) return 0;

        int appliedCount = 0;
        BlockPos.MutableBlockPos mutPos = new BlockPos.MutableBlockPos();
        int chunkBaseX = edits.getChunkX() << 4;
        int chunkBaseZ = edits.getChunkZ() << 4;

        // 1. Whole sections
        for (Map.Entry<Integer, VoxelSection> secEntry : edits.getWholeSections().entrySet()) {
            int secY = secEntry.getKey();
            VoxelSection vs = secEntry.getValue();
            int baseY = secY << 4;

            if (vs.isHomogeneous()) {
                BlockState target = MinecraftVoxelBridge.getBlockState(vs.getSingleBlockId());
                if (target != null) {
                    for (int dy = 0; dy < 16; dy++) {
                        int wy = baseY | dy;
                        for (int dz = 0; dz < 16; dz++) {
                            int wz = chunkBaseZ | dz;
                            for (int dx = 0; dx < 16; dx++) {
                                int wx = chunkBaseX | dx;
                                mutPos.set(wx, wy, wz);
                                if (rollbackRecord != null) {
                                    rollbackRecord.add(new AppliedRamMutation(BlockPos.asLong(wx, wy, wz), chunk.getBlockState(mutPos), null));
                                }
                                chunk.setBlockState(mutPos, target, false);
                                appliedCount++;
                            }
                        }
                    }
                }
            } else {
                int[] blockIds = vs.getBlockIds();
                for (int dy = 0; dy < 16; dy++) {
                    int wy = baseY | dy;
                    for (int dz = 0; dz < 16; dz++) {
                        int wz = chunkBaseZ | dz;
                        for (int dx = 0; dx < 16; dx++) {
                            int idx = VoxelSection.voxelIndex(dx, dy, dz);
                            int bId = blockIds[idx];
                            BlockState target = MinecraftVoxelBridge.getBlockState(bId);
                            if (target != null) {
                                int wx = chunkBaseX | dx;
                                mutPos.set(wx, wy, wz);
                                if (rollbackRecord != null) {
                                    rollbackRecord.add(new AppliedRamMutation(BlockPos.asLong(wx, wy, wz), chunk.getBlockState(mutPos), null));
                                }
                                chunk.setBlockState(mutPos, target, false);
                                appliedCount++;
                            }
                        }
                    }
                }
            }
        }

        // 2. Individual block mutations
        List<ChunkWriteBatch.BlockMutation> mutations = edits.getMutations();
        for (int i = 0, size = mutations.size(); i < size; i++) {
            ChunkWriteBatch.BlockMutation m = mutations.get(i);
            mutPos.set(m.worldX(), m.worldY(), m.worldZ());
            BlockState cur = chunk.getBlockState(mutPos);
            if (m.matchesFilter(-1, cur)) {
                if (rollbackRecord != null) {
                    BlockEntity oldBe = level.getBlockEntity(mutPos);
                    CompoundTag oldBeNbt = (oldBe != null) ? oldBe.saveWithFullMetadata(level.registryAccess()) : null;
                    rollbackRecord.add(new AppliedRamMutation(m.posAsLong(), cur, oldBeNbt));
                }

                // Flags: 2 (send client packet) | 16 (UPDATE_KNOWN_SHAPE) | 128 (suppress light updates)
                boolean placed = level.setBlock(mutPos, m.targetState(), 2 | 16 | 128);
                if (!placed) {
                    throw new IllegalStateException("Failed to place block in RAM at " + mutPos + " with state " + m.targetState());
                }
                appliedCount++;

                if (m.tagNbt() != null) {
                    BlockEntity be = level.getBlockEntity(mutPos);
                    if (be != null) {
                        be.loadWithComponents(m.tagNbt(), level.registryAccess());
                        be.setChanged();
                    }
                }
            }
        }

        chunk.setUnsaved(true);
        MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
        if (grid != null) {
            grid.getCache().invalidateChunk(edits.getChunkX(), edits.getChunkZ());
        }

        return appliedCount;
    }

    /**
     * Applies edits to a newly loaded chunk during chunk load interception (e.g. {@code ChunkEvent.Load}).
     * Performs zero heap allocations and modifies the LevelChunk directly before client packet dispatch.
     *
     * @param chunk Target LevelChunk
     * @param edits Queued ChunkEdits
     * @return Number of blocks successfully mutated
     */
    public static int applyToLoadingChunk(LevelChunk chunk, ChunkWriteBatch.ChunkEdits edits) {
        if (chunk == null || edits == null) return 0;

        int appliedCount = 0;
        BlockPos.MutableBlockPos mutPos = new BlockPos.MutableBlockPos();
        int chunkBaseX = edits.getChunkX() << 4;
        int chunkBaseZ = edits.getChunkZ() << 4;

        // 1. Whole sections
        for (Map.Entry<Integer, VoxelSection> secEntry : edits.getWholeSections().entrySet()) {
            int secY = secEntry.getKey();
            VoxelSection vs = secEntry.getValue();
            int baseY = secY << 4;

            if (vs.isHomogeneous()) {
                BlockState target = MinecraftVoxelBridge.getBlockState(vs.getSingleBlockId());
                if (target != null) {
                    for (int dy = 0; dy < 16; dy++) {
                        int wy = baseY | dy;
                        for (int dz = 0; dz < 16; dz++) {
                            int wz = chunkBaseZ | dz;
                            for (int dx = 0; dx < 16; dx++) {
                                mutPos.set(chunkBaseX | dx, wy, wz);
                                chunk.setBlockState(mutPos, target, false);
                                appliedCount++;
                            }
                        }
                    }
                }
            } else {
                int[] blockIds = vs.getBlockIds();
                for (int dy = 0; dy < 16; dy++) {
                    int wy = baseY | dy;
                    for (int dz = 0; dz < 16; dz++) {
                        int wz = chunkBaseZ | dz;
                        for (int dx = 0; dx < 16; dx++) {
                            int idx = VoxelSection.voxelIndex(dx, dy, dz);
                            int bId = blockIds[idx];
                            BlockState target = MinecraftVoxelBridge.getBlockState(bId);
                            if (target != null) {
                                mutPos.set(chunkBaseX | dx, wy, wz);
                                chunk.setBlockState(mutPos, target, false);
                                appliedCount++;
                            }
                        }
                    }
                }
            }
        }

        // 2. Individual block mutations
        List<ChunkWriteBatch.BlockMutation> mutations = edits.getMutations();
        for (int i = 0, size = mutations.size(); i < size; i++) {
            ChunkWriteBatch.BlockMutation m = mutations.get(i);
            mutPos.set(m.worldX(), m.worldY(), m.worldZ());
            BlockState cur = chunk.getBlockState(mutPos);
            if (m.matchesFilter(-1, cur)) {
                chunk.setBlockState(mutPos, m.targetState(), false);
                appliedCount++;

                if (m.tagNbt() != null) {
                    BlockEntity be = chunk.getBlockEntity(mutPos, LevelChunk.EntityCreationType.IMMEDIATE);
                    if (be != null) {
                        be.loadWithComponents(m.tagNbt(), chunk.getLevel().registryAccess());
                        be.setChanged();
                    }
                }
            }
        }

        chunk.setUnsaved(true);
        var level = chunk.getLevel();
        MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
        if (grid != null) {
            grid.getCache().invalidateChunk(edits.getChunkX(), edits.getChunkZ());
        }

        return appliedCount;
    }

    /**
     * Rolls back previously applied mutations upon failure during live RAM writes.
     *
     * @param level   ServerLevel
     * @param applied List of applied mutations to revert
     */
    public static void rollback(ServerLevel level, List<AppliedRamMutation> applied) {
        if (level == null || applied == null || applied.isEmpty()) return;

        BlockPos.MutableBlockPos mutPos = new BlockPos.MutableBlockPos();
        for (int i = applied.size() - 1; i >= 0; i--) {
            AppliedRamMutation arm = applied.get(i);
            try {
                mutPos.set(arm.packedPos());
                level.setBlock(mutPos, arm.previousState(), 2 | 16 | 128);
                if (arm.previousBeNbt() != null) {
                    BlockEntity be = level.getBlockEntity(mutPos);
                    if (be != null) {
                        be.loadWithComponents(arm.previousBeNbt(), level.registryAccess());
                        be.setChanged();
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("Failed to rollback block mutation at {}: {}", arm.pos(), t.getMessage());
            }
        }
    }
}
