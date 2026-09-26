package com.pixel.qve.neoforge.terrain;

import com.pixel.qve.mca.writer.McaRegionWriter;
import com.pixel.qve.neoforge.api.VoxelWriteAPI;
import com.pixel.qve.neoforge.api.WriteResult;
import com.pixel.qve.neoforge.api.terrain.VirtualChunk;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelGrid;
import com.pixel.qve.neoforge.bridge.writer.ChunkExclusivityGuard;
import com.pixel.qve.neoforge.bridge.writer.MinecraftRegionFileBridge;
import com.pixel.qve.neoforge.bridge.writer.MinecraftVoxelWriter;
import com.pixel.qve.raycast.RaycastThreadPool;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * High-performance orchestrator for generating isolated Minecraft chunks from world seed
 * using the level's active {@link ChunkGenerator}.
 * <p>
 * Provides both in-memory {@link VirtualChunk} generation and spatial stamping into target world chunks
 * (updating live RAM chunks in real time or streaming directly to offline MCA disk).
 * </p>
 */
public final class QveTerrainEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(QveTerrainEngine.class);

    private QveTerrainEngine() {}

    /**
     * Generates an in-memory {@link VirtualChunk} from the level's seed without touching disk.
     *
     * @param level  Target ServerLevel
     * @param chunkX Source chunk X to sample from seed
     * @param chunkZ Source chunk Z to sample from seed
     * @param stage  Cutoff generation stage
     * @return CompletableFuture completing with VirtualChunk
     */
    public static CompletableFuture<VirtualChunk> generateVirtualChunkAsync(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            TerrainStage stage
    ) {
        Objects.requireNonNull(level, "level cannot be null");
        Objects.requireNonNull(stage, "stage cannot be null");

        return CompletableFuture.supplyAsync(() -> {
            long t0 = System.nanoTime();
            ServerChunkCache chunkSource = level.getChunkSource();
            ChunkGenerator generator = chunkSource.getGenerator();
            RandomState randomState = chunkSource.randomState();
            StructureManager structureManager = level.structureManager();

            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);

            // 1. Instantiate headless ProtoChunk in pure RAM
            ProtoChunk protoChunk = new ProtoChunk(pos, UpgradeData.EMPTY, level, biomeRegistry, null);

            // 2. Stage 1: Biomes
            generator.createBiomes(
                    randomState,
                    Blender.empty(),
                    structureManager,
                    protoChunk
            ).join();

            // 3. Stage 2: 3D Density Noise (Stone, Water, Air)
            if (stage.ordinal() >= TerrainStage.NOISE.ordinal()) {
                generator.fillFromNoise(
                        Blender.empty(),
                        randomState,
                        structureManager,
                        protoChunk
                ).join();
            }

            // 4. Stage 3: Biome Surface Rules (Grass, Sand, Dirt)
            if (stage.ordinal() >= TerrainStage.SURFACE.ordinal()) {
                if (generator instanceof NoiseBasedChunkGenerator noiseGen) {
                    WorldGenerationContext context = new WorldGenerationContext(generator, level);
                    noiseGen.buildSurface(
                            protoChunk,
                            context,
                            randomState,
                            structureManager,
                            level.getBiomeManager(),
                            biomeRegistry,
                            Blender.empty()
                    );
                }
            }

            protoChunk.setPersistedStatus(stage.getChunkStatus());
            long duration = System.nanoTime() - t0;
            LOGGER.debug("[QveTerrainEngine] Generated virtual chunk ({}, {}) at stage [{}] in {} ms",
                    chunkX, chunkZ, stage.getName(), duration / 1_000_000.0);

            return new DefaultVirtualChunk(chunkX, chunkZ, stage, protoChunk);
        }, RaycastThreadPool.getExecutor());
    }

    /**
     * Stamps an in-memory {@link VirtualChunk} into a destination world chunk.
     * <p>
     * If the destination chunk is currently resident in live RAM, sections are injected live,
     * marked dirty, and synchronized to tracking clients via chunk packets.
     * If unloaded on disk, written directly via the unified {@link MinecraftVoxelWriter} MCA coordinator.
     * </p>
     *
     * @param level        Target ServerLevel
     * @param virtualChunk In-memory virtual chunk buffer
     * @param targetChunkX Destination chunk X
     * @param targetChunkZ Destination chunk Z
     * @return CompletableFuture completing with WriteResult
     */
    public static CompletableFuture<WriteResult> stampVirtualChunkAsync(
            ServerLevel level,
            VirtualChunk virtualChunk,
            int targetChunkX,
            int targetChunkZ
    ) {
        Objects.requireNonNull(level, "level cannot be null");
        Objects.requireNonNull(virtualChunk, "virtualChunk cannot be null");

        long enqueuedAt = System.nanoTime();

        // 1. Live RAM Stamping: chunk is currently active / loaded
        if (ChunkExclusivityGuard.isChunkLoadedInRam(level, targetChunkX, targetChunkZ)) {
            CompletableFuture<WriteResult> future = new CompletableFuture<>();
            level.getServer().execute(() -> {
                long t0 = System.nanoTime();
                long queueWaitNs = t0 - enqueuedAt;
                try {
                    LevelChunk liveChunk = level.getChunk(targetChunkX, targetChunkZ);
                    LevelChunkSection[] srcSecs = virtualChunk.getSections();
                    LevelChunkSection[] dstSecs = liveChunk.getSections();

                    for (int i = 0; i < Math.min(srcSecs.length, dstSecs.length); i++) {
                        LevelChunkSection srcSec = srcSecs[i];
                        LevelChunkSection dstSec = dstSecs[i];
                        if (srcSec != null) {
                            if (dstSec == null) {
                                dstSec = new LevelChunkSection(level.registryAccess().registryOrThrow(Registries.BIOME));
                                dstSecs[i] = dstSec;
                            }
                            for (int y = 0; y < 16; y++) {
                                for (int z = 0; z < 16; z++) {
                                    for (int x = 0; x < 16; x++) {
                                        dstSec.setBlockState(x, y, z, srcSec.getBlockState(x, y, z), false);
                                    }
                                }
                            }
                        }
                    }

                    liveChunk.setUnsaved(true);

                    // Dispatch full chunk update packet to all players tracking this chunk
                    ClientboundLevelChunkWithLightPacket updatePacket =
                            new ClientboundLevelChunkWithLightPacket(liveChunk, level.getLightEngine(), null, null);
                    for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(liveChunk.getPos(), false)) {
                        player.connection.send(updatePacket);
                    }

                    // Invalidate spatial grid cache
                    MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
                    if (grid != null) {
                        grid.getCache().invalidateChunk(targetChunkX, targetChunkZ);
                    }

                    long duration = System.nanoTime() - t0;
                    LOGGER.info(String.format(
                            "[QveTerrainEngine] Stamped virtual chunk (%d, %d) -> live RAM chunk (%d, %d) in %.2f ms (server queue delay: %.2f ms)",
                            virtualChunk.getSourceChunkX(), virtualChunk.getSourceChunkZ(),
                            targetChunkX, targetChunkZ, duration / 1_000_000.0, queueWaitNs / 1_000_000.0
                    ));

                    future.complete(WriteResult.successRam(targetChunkX, targetChunkZ, duration));
                } catch (Throwable t) {
                    LOGGER.error("[QveTerrainEngine] Failed to stamp virtual chunk into live RAM ({}, {}): {}",
                            targetChunkX, targetChunkZ, t.getMessage(), t);
                    future.complete(WriteResult.failure(com.pixel.qve.neoforge.api.WriteStatus.FAIL_IO_ERROR, targetChunkX, targetChunkZ, t.getMessage()));
                }
            });
            return future;
        }

        // 2. Offline Disk Stamping: chunk is not loaded in RAM
        return CompletableFuture.supplyAsync(() -> {
            long t0 = System.nanoTime();
            try {
                ProtoChunk proto = virtualChunk.getProtoChunk();
                CompoundTag chunkTag = ChunkSerializer.write(level, proto);

                // Re-bind coordinates to destination chunk
                chunkTag.putInt("xPos", targetChunkX);
                chunkTag.putInt("zPos", targetChunkZ);
                chunkTag.putString("Status", virtualChunk.getStage().getChunkStatus().getName());

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                NbtIo.write(chunkTag, new DataOutputStream(baos));
                ByteBuffer payloadBuffer = ByteBuffer.wrap(baos.toByteArray());

                // Evict cached RegionFile handle to prevent header conflicts
                MinecraftRegionFileBridge.evictAndFlushRegion(level, targetChunkX >> 5, targetChunkZ >> 5);

                MinecraftVoxelWriter voxelWriter = VoxelWriteAPI.getWriter(level);
                if (voxelWriter == null || voxelWriter.getCoordinator() == null) {
                    throw new IllegalStateException("Failed to resolve McaWriteCoordinator for dimension: " + level.dimension().location());
                }

                McaRegionWriter.WriteMetrics metrics = voxelWriter.getCoordinator().writeRawChunkNbtSync(targetChunkX, targetChunkZ, payloadBuffer);

                // Invalidate spatial grid cache
                MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
                if (grid != null) {
                    grid.getCache().invalidateChunk(targetChunkX, targetChunkZ);
                }

                long duration = System.nanoTime() - t0;
                LOGGER.info("[QveTerrainEngine] Stamped virtual chunk ({}, {}) -> offline MCA chunk ({}, {}) in {} ms (Sector: {}, Bytes: {})",
                        virtualChunk.getSourceChunkX(), virtualChunk.getSourceChunkZ(),
                        targetChunkX, targetChunkZ, duration / 1_000_000.0, metrics.sectorOffset(), metrics.compressedBytes());

                return WriteResult.successDisk(targetChunkX, targetChunkZ, duration, metrics.compressedBytes(), metrics.sectorOffset(), metrics.isRelocated());
            } catch (Throwable t) {
                LOGGER.error("[QveTerrainEngine] Failed to stamp virtual chunk into offline disk ({}, {}): {}",
                        targetChunkX, targetChunkZ, t.getMessage(), t);
                return WriteResult.failure(com.pixel.qve.neoforge.api.WriteStatus.FAIL_IO_ERROR, targetChunkX, targetChunkZ, t.getMessage());
            }
        }, RaycastThreadPool.getExecutor());
    }

    /**
     * Generates a chunk at the specified coordinates from seed and writes it directly to disk/RAM.
     */
    public static CompletableFuture<TerrainGenerationResult> generateChunkAsync(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            TerrainStage stage
    ) {
        long t0 = System.nanoTime();
        return generateVirtualChunkAsync(level, chunkX, chunkZ, stage)
                .thenCompose(vc -> stampVirtualChunkAsync(level, vc, chunkX, chunkZ))
                .thenApply(wr -> {
                    long duration = System.nanoTime() - t0;
                    if (wr.isSuccess()) {
                        return TerrainGenerationResult.success(chunkX, chunkZ, stage, duration, wr.sectorOffset(), wr.compressedBytes());
                    } else {
                        return TerrainGenerationResult.failure(chunkX, chunkZ, stage, wr.errorMessage());
                    }
                });
    }
}
