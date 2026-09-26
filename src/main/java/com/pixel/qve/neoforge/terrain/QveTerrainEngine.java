package com.pixel.qve.neoforge.terrain;

import com.pixel.qve.mca.writer.McaRegionWriter;
import com.pixel.qve.mca.writer.McaWriteCoordinator;
import com.pixel.qve.neoforge.api.VoxelWriteAPI;
import com.pixel.qve.neoforge.bridge.writer.ChunkExclusivityGuard;
import com.pixel.qve.neoforge.bridge.writer.MinecraftRegionFileBridge;
import com.pixel.qve.neoforge.bridge.writer.MinecraftVoxelWriter;
import com.pixel.qve.raycast.RaycastThreadPool;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * High-performance orchestrator for generating isolated Minecraft chunks from world seed
 * using the level's active {@link ChunkGenerator}.
 * <p>
 * Bypasses the main server thread by executing generation stages on background worker threads
 * and safely writes output directly through the unified {@link MinecraftVoxelWriter} / {@link McaWriteCoordinator} pipeline.
 * </p>
 */
public final class QveTerrainEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(QveTerrainEngine.class);

    private QveTerrainEngine() {}

    /**
     * Generates a chunk at the specified coordinates up to the requested {@link TerrainStage}
     * and streams it to the unified MCA disk storage.
     *
     * @param level  Target ServerLevel
     * @param chunkX World chunk X
     * @param chunkZ World chunk Z
     * @param stage  Cutoff generation stage
     * @return CompletableFuture completing with TerrainGenerationResult
     */
    public static CompletableFuture<TerrainGenerationResult> generateChunkAsync(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            TerrainStage stage
    ) {
        Objects.requireNonNull(level, "level cannot be null");
        Objects.requireNonNull(stage, "stage cannot be null");

        // 1. Guard against modifying active live chunks in RAM
        if (ChunkExclusivityGuard.isChunkLoadedInRam(level, chunkX, chunkZ)) {
            return CompletableFuture.completedFuture(
                    TerrainGenerationResult.failure(chunkX, chunkZ, stage,
                            "Chunk (" + chunkX + ", " + chunkZ + ") is currently loaded in active RAM. Cannot overwrite live terrain.")
            );
        }

        return CompletableFuture.supplyAsync(() -> {
            long t0 = System.nanoTime();
            try {
                ServerChunkCache chunkSource = level.getChunkSource();
                ChunkGenerator generator = chunkSource.getGenerator();
                RandomState randomState = chunkSource.randomState();
                StructureManager structureManager = level.structureManager();

                ChunkPos pos = new ChunkPos(chunkX, chunkZ);
                var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);

                // 2. Instantiate headless ProtoChunk in pure memory
                ProtoChunk protoChunk = new ProtoChunk(pos, UpgradeData.EMPTY, level, biomeRegistry, null);

                // 3. Stage 1: Biomes
                generator.createBiomes(
                        randomState,
                        Blender.empty(),
                        structureManager,
                        protoChunk
                ).join();

                // 4. Stage 2: 3D Density Noise (Stone, Water, Air)
                if (stage.ordinal() >= TerrainStage.NOISE.ordinal()) {
                    generator.fillFromNoise(
                            Blender.empty(),
                            randomState,
                            structureManager,
                            protoChunk
                    ).join();
                }

                // 5. Stage 3: Biome Surface Rules (Grass, Sand, Dirt)
                if (stage.ordinal() >= TerrainStage.SURFACE.ordinal()) {
                    if (generator instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator noiseGen) {
                        net.minecraft.world.level.levelgen.WorldGenerationContext context =
                                new net.minecraft.world.level.levelgen.WorldGenerationContext(generator, level);
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

                // 7. Serialize ProtoChunk into Minecraft Anvil CompoundTag
                CompoundTag chunkTag = ChunkSerializer.write(level, protoChunk);

                // Ensure the status reflects the stage in NBT
                chunkTag.putString("Status", stage.getChunkStatus().getName());

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                NbtIo.write(chunkTag, new DataOutputStream(baos));
                byte[] rawNbtBytes = baos.toByteArray();
                ByteBuffer payloadBuffer = ByteBuffer.wrap(rawNbtBytes);

                // 8. Evict cached RegionFile handle to prevent header overwrite
                MinecraftRegionFileBridge.evictAndFlushRegion(level, chunkX >> 5, chunkZ >> 5);

                // 9. Write via unified McaWriteCoordinator
                MinecraftVoxelWriter voxelWriter = VoxelWriteAPI.getWriter(level);
                if (voxelWriter == null || voxelWriter.getCoordinator() == null) {
                    throw new IllegalStateException("Failed to resolve McaWriteCoordinator for dimension: " + level.dimension().location());
                }

                McaRegionWriter.WriteMetrics metrics = voxelWriter.getCoordinator().writeRawChunkNbtSync(chunkX, chunkZ, payloadBuffer);

                long duration = System.nanoTime() - t0;
                LOGGER.info("[QveTerrainEngine] Successfully generated and wrote chunk ({}, {}) at stage [{}] in {} ms (Sector: {}, Bytes: {})",
                        chunkX, chunkZ, stage.getName(), duration / 1_000_000.0, metrics.sectorOffset(), metrics.compressedBytes());

                return TerrainGenerationResult.success(chunkX, chunkZ, stage, duration, metrics.sectorOffset(), metrics.compressedBytes());

            } catch (Throwable t) {
                LOGGER.error("[QveTerrainEngine] Failed to generate chunk ({}, {}): {}", chunkX, chunkZ, t.getMessage(), t);
                return TerrainGenerationResult.failure(chunkX, chunkZ, stage, t.getMessage());
            }
        }, RaycastThreadPool.getExecutor());
    }
}
