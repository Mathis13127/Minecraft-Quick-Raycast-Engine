package com.pixel.qve.neoforge.bridge.writer;

import com.pixel.qve.neoforge.mixin.ChunkMapAccessor;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Strict safety guard enforcing mutual exclusion between live RAM chunks and direct Anvil disk writes.
 * Prevents race conditions, data loss, and file corruption by verifying that target chunks are NOT
 * currently loaded or being saved by Minecraft's internal chunk pipeline.
 */
public final class ChunkExclusivityGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkExclusivityGuard.class);

    private ChunkExclusivityGuard() {}

    /**
     * Checks if a chunk is currently loaded in memory (RAM).
     *
     * @param level  Minecraft Level
     * @param chunkX Chunk X
     * @param chunkZ Chunk Z
     * @return True if chunk is loaded in RAM
     */
    public static boolean isChunkLoadedInRam(Level level, int chunkX, int chunkZ) {
        if (level == null) return false;

        if (level instanceof ServerLevel serverLevel) {
            ServerChunkCache scc = serverLevel.getChunkSource();
            if (scc == null) {
                return false;
            }
            // 1. Check active ticking status
            if (scc.hasChunk(chunkX, chunkZ)) {
                return true;
            }
            if (scc.getChunkNow(chunkX, chunkZ) != null) {
                return true;
            }

            // 2. Check full ChunkMap memory residency (border chunks, proto-chunks, and pending unloads)
            ChunkMap chunkMap = scc.chunkMap;
            if (chunkMap != null) {
                long posLong = ChunkPos.asLong(chunkX, chunkZ);

                // Visible/distance-tracked chunks (volatile, O(1))
                if (chunkMap.getVisibleChunkIfPresent(posLong) != null) {
                    return true;
                }

                // Internal updating and pending unloads maps via ChunkMapAccessor
                try {
                    ChunkMapAccessor accessor = (ChunkMapAccessor) chunkMap;
                    var updating = accessor.qve$getUpdatingChunkMap();
                    if (updating != null && updating.containsKey(posLong)) {
                        return true;
                    }
                    var unloads = accessor.qve$getPendingUnloads();
                    if (unloads != null && unloads.containsKey(posLong)) {
                        return true;
                    }
                } catch (Throwable t) {
                    LOGGER.warn("Failed to inspect ChunkMapAccessor for chunk ({}, {}): {}", chunkX, chunkZ, t.getMessage());
                }
            }
            return false;
        }

        // Client side singleplayer check
        if (level.isClientSide() && net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            try {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc != null && mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
                    ServerLevel serverLevel = mc.getSingleplayerServer().getLevel(level.dimension());
                    if (serverLevel != null) {
                        return isChunkLoadedInRam(serverLevel, chunkX, chunkZ);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        return false;
    }

    /**
     * Checks if a chunk is completely safe for direct Anvil (.mca) disk writing.
     * Requires the chunk to be strictly UNLOADED in RAM.
     *
     * @param level  Minecraft Level
     * @param chunkX Chunk X
     * @param chunkZ Chunk Z
     * @return True if safe for direct disk writing
     */
    public static boolean isSafeForDirectDiskWrite(Level level, int chunkX, int chunkZ) {
        if (level == null) return false;
        return !isChunkLoadedInRam(level, chunkX, chunkZ);
    }

    /**
     * Asserts that a chunk is strictly safe for direct Anvil disk writing.
     * Throws ChunkLoadedInRamException if chunk is resident in RAM.
     *
     * @param level  Minecraft Level
     * @param chunkX Chunk X
     * @param chunkZ Chunk Z
     * @throws ChunkLoadedInRamException If chunk is in RAM
     */
    public static void assertSafeForDirectDiskWrite(Level level, int chunkX, int chunkZ) {
        if (isChunkLoadedInRam(level, chunkX, chunkZ)) {
            String dimName = (level.dimension() != null && level.dimension().location() != null)
                    ? level.dimension().location().toString()
                    : "unknown";
            String msg = String.format("Direct MCA disk write rejected: chunk (%d, %d) in dimension %s is currently LOADED in RAM!",
                    chunkX, chunkZ, dimName);
            LOGGER.error(msg);
            throw new ChunkLoadedInRamException(msg);
        }
    }

    /**
     * Exception thrown when attempting strict direct MCA write on a chunk loaded in RAM.
     */
    public static class ChunkLoadedInRamException extends IllegalStateException {
        public ChunkLoadedInRamException(String message) {
            super(message);
        }
    }
}
