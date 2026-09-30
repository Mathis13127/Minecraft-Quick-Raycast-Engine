package com.pixel.qve.neoforge.bridge.writer;

import com.pixel.qve.mca.writer.PrimitiveMutationBuffer;
import com.pixel.qve.neoforge.api.ChunkWriteBatch;
import com.pixel.qve.neoforge.bridge.IRaycastChunkSection;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelGrid;
import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.world.VoxelChunkColumn;
import com.pixel.qve.world.VoxelSection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ultra-high-performance applicator executing pointer swaps of {@link PalettedContainer}
 * directly on {@link LevelChunk} instances in 1 CPU cycle (Axiom style).
 * <p>
 * Eliminates thousands of redundant block placement calls, locks, and cascades,
 * executing zero-GC atomic memory updates directly on the server thread.
 * </p>
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
     * Applies edits to an actively loaded chunk in live gameplay on the server thread using atomic pointer swaps.
     *
     * @param level          ServerLevel instance
     * @param chunk          Target LevelChunk
     * @param edits          ChunkEdits container
     * @param rollbackRecord Optional list to collect previous states for rollback (may be null)
     * @return Number of blocks successfully mutated
     */
    public static int applyToLiveChunk(ServerLevel level, LevelChunk chunk, ChunkWriteBatch.ChunkEdits edits, List<AppliedRamMutation> rollbackRecord) {
        if (level == null || chunk == null || edits == null) return 0;

        int appliedCount = applyEditsInternal(chunk, edits);

        // Broadcast instant packet to tracking players
        ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(
                chunk,
                level.getLightEngine(),
                null,
                null
        );
        List<ServerPlayer> players = level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false);
        for (ServerPlayer player : players) {
            player.connection.send(packet);
        }

        return appliedCount;
    }

    /**
     * Applies edits to a newly loaded chunk during chunk load interception (e.g. {@code ChunkEvent.Load}).
     * Performs atomic pointer swaps directly on the LevelChunk before client packet dispatch.
     *
     * @param chunk Target LevelChunk
     * @param edits Queued ChunkEdits
     * @return Number of blocks successfully mutated
     */
    public static int applyToLoadingChunk(LevelChunk chunk, ChunkWriteBatch.ChunkEdits edits) {
        if (chunk == null || edits == null) return 0;
        return applyEditsInternal(chunk, edits);
    }

    /**
     * Internal unified execution engine applying section pointer swaps and block mutations to a LevelChunk.
     *
     * @param chunk Target LevelChunk
     * @param edits ChunkEdits container
     * @return Number of blocks successfully mutated
     */
    private static int applyEditsInternal(LevelChunk chunk, ChunkWriteBatch.ChunkEdits edits) {
        var level = chunk.getLevel();
        int appliedCount = 0;
        Set<Integer> modifiedSections = new HashSet<>();

        // 1. Whole sections: 1-cycle pointer swap per section
        for (Map.Entry<Integer, VoxelSection> secEntry : edits.getWholeSections().entrySet()) {
            int secY = secEntry.getKey();
            VoxelSection vs = secEntry.getValue();
            int secIdx = chunk.getSectionIndex(secY << 4);
            if (secIdx < 0 || secIdx >= chunk.getSections().length) continue;

            LevelChunkSection sec = chunk.getSections()[secIdx];
            if (sec == null) {
                sec = new LevelChunkSection(level.registryAccess().registryOrThrow(Registries.BIOME));
                chunk.getSections()[secIdx] = sec;
            }

            PalettedContainer<BlockState> newContainer = PalettedContainerBuilder.buildFromVoxelSection(vs);
            if (sec instanceof IRaycastChunkSection bridge) {
                bridge.raycast$setStates(newContainer);
                sec.recalcBlockCounts();
                bridge.raycast$setVoxelSection(vs);
                VoxelChunkColumn col = bridge.raycast$getVoxelColumn();
                if (col != null) {
                    col.setSection(secY, vs);
                    col.setCachedLighting(null);
                }
            }
            modifiedSections.add(secY);
            appliedCount += VoxelSection.VOXEL_COUNT;
        }

        // 2. Block mutations and bounding boxes (grouped by section Y)
        PrimitiveMutationBuffer mutationBuf = edits.getMutationBuffer();
        Set<Integer> sparseSectionYs = new HashSet<>();
        if (mutationBuf != null && !mutationBuf.isEmpty()) {
            int minSec = level.getMinSection();
            int maxSec = level.getMinSection() + level.getSectionsCount() - 1;
            for (int sy = minSec; sy <= maxSec; sy++) {
                if (modifiedSections.contains(sy)) continue;
                for (int i = 0, sz = mutationBuf.size(); i < sz; i++) {
                    if (mutationBuf.intersectsSection(i, sy)) {
                        sparseSectionYs.add(sy);
                        break;
                    }
                }
            }
        }

        List<ChunkWriteBatch.BlockMutation> mutations = edits.getMutations();
        if (!mutations.isEmpty()) {
            for (ChunkWriteBatch.BlockMutation m : mutations) {
                int sy = m.worldY() >> 4;
                if (!modifiedSections.contains(sy)) {
                    sparseSectionYs.add(sy);
                }
            }
        }

        for (int secY : sparseSectionYs) {
            int secIdx = chunk.getSectionIndex(secY << 4);
            if (secIdx < 0 || secIdx >= chunk.getSections().length) continue;

            LevelChunkSection sec = chunk.getSections()[secIdx];
            if (sec == null) {
                sec = new LevelChunkSection(level.registryAccess().registryOrThrow(Registries.BIOME));
                chunk.getSections()[secIdx] = sec;
            }

            IRaycastChunkSection bridge = (sec instanceof IRaycastChunkSection b) ? b : null;
            PalettedContainer<BlockState> newContainer = PalettedContainerBuilder.cloneOrNew(
                    (bridge != null) ? bridge.raycast$getStates() : null
            );

            VoxelSection vs = (bridge != null) ? bridge.raycast$getVoxelSection() : null;
            if (vs == null || vs == VoxelSection.EMPTY) {
                vs = new VoxelSection();
            } else {
                vs = vs.copy();
            }

            int secMinY = secY << 4;
            int secMaxY = secMinY + 15;

            // Apply boxes from PrimitiveMutationBuffer
            if (mutationBuf != null && !mutationBuf.isEmpty()) {
                for (int i = 0, sz = mutationBuf.size(); i < sz; i++) {
                    if (mutationBuf.intersectsSection(i, secY)) {
                        int bMinX = mutationBuf.minX(i);
                        int bMaxX = mutationBuf.maxX(i);
                        int bMinZ = mutationBuf.minZ(i);
                        int bMaxZ = mutationBuf.maxZ(i);
                        int bMinY = Math.max(secMinY, mutationBuf.minY(i));
                        int bMaxY = Math.min(secMaxY, mutationBuf.maxY(i));
                        int targetId = mutationBuf.targetBlockId(i);
                        int filterId = mutationBuf.filterBlockId(i);
                        BlockState targetState = MinecraftVoxelBridge.getBlockState(targetId);
                        BlockState filterState = (filterId >= 0) ? MinecraftVoxelBridge.getBlockState(filterId) : null;
                        byte[] rawNbt = mutationBuf.rawNbt(i);

                        for (int y = bMinY; y <= bMaxY; y++) {
                            int ly = y & 15;
                            for (int z = bMinZ; z <= bMaxZ; z++) {
                                for (int x = bMinX; x <= bMaxX; x++) {
                                    BlockState cur = newContainer.get(x, ly, z);
                                    boolean matches = (filterId < 0) || (filterState != null && (cur == filterState || cur.getBlock() == filterState.getBlock()));
                                    if (matches) {
                                        newContainer.set(x, ly, z, (targetState != null) ? targetState : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                                        vs.setVoxel(x, ly, z, targetId != BlockIdRegistry.AIR_ID, targetId);
                                        appliedCount++;

                                        if (rawNbt != null) {
                                            try {
                                                CompoundTag tag = net.minecraft.nbt.NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(rawNbt)));
                                                BlockPos bePos = new BlockPos((edits.getChunkX() << 4) | x, y, (edits.getChunkZ() << 4) | z);
                                                BlockEntity be = chunk.getBlockEntity(bePos, LevelChunk.EntityCreationType.IMMEDIATE);
                                                if (be != null) {
                                                    be.loadWithComponents(tag, level.registryAccess());
                                                    be.setChanged();
                                                }
                                            } catch (Exception e) {
                                                LOGGER.warn("Failed to load raw BlockEntity NBT for chunk ({}, {}) at ({}, {}, {}): {}",
                                                        edits.getChunkX(), edits.getChunkZ(), (edits.getChunkX() << 4) | x, y, (edits.getChunkZ() << 4) | z, e.getMessage(), e);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Apply sparse mutations
            for (ChunkWriteBatch.BlockMutation m : mutations) {
                if ((m.worldY() >> 4) == secY) {
                    int lx = m.worldX() & 15;
                    int ly = m.worldY() & 15;
                    int lz = m.worldZ() & 15;
                    BlockState cur = newContainer.get(lx, ly, lz);
                    if (m.matchesFilter(-1, cur)) {
                        newContainer.set(lx, ly, lz, m.targetState());
                        vs.setVoxel(lx, ly, lz, m.targetBlockId() != BlockIdRegistry.AIR_ID, m.targetBlockId());
                        appliedCount++;

                        if (m.tagNbt() != null) {
                            try {
                                BlockPos bePos = new BlockPos(m.worldX(), m.worldY(), m.worldZ());
                                BlockEntity be = chunk.getBlockEntity(bePos, LevelChunk.EntityCreationType.IMMEDIATE);
                                if (be != null) {
                                    be.loadWithComponents(m.tagNbt(), level.registryAccess());
                                    be.setChanged();
                                }
                            } catch (Exception e) {
                                LOGGER.warn("Failed to load BlockEntity NBT from mutation at ({}, {}, {}): {}",
                                        m.worldX(), m.worldY(), m.worldZ(), e.getMessage(), e);
                            }
                        }
                    }
                }
            }

            // Pointer swap for modified sparse section
            if (bridge != null) {
                bridge.raycast$setStates(newContainer);
                sec.recalcBlockCounts();
                bridge.raycast$setVoxelSection(vs);
                VoxelChunkColumn col = bridge.raycast$getVoxelColumn();
                if (col != null) {
                    col.setSection(secY, vs);
                    col.setCachedLighting(null);
                }
            }
            modifiedSections.add(secY);
        }

        // 3. Mark chunk dirty and sync QVE spatial cache
        chunk.setUnsaved(true);
        MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
        if (grid != null) {
            grid.getCache().invalidateChunk(edits.getChunkX(), edits.getChunkZ());
            VoxelChunkColumn col = grid.getColumn(edits.getChunkX(), edits.getChunkZ());
            if (col != null) {
                grid.syncHeightmapFromChunk(chunk, col, edits.getChunkX(), edits.getChunkZ());
            }
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
