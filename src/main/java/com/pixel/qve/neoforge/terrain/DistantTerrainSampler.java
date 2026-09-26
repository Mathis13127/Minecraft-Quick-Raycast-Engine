package com.pixel.qve.neoforge.terrain;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Objects;

/**
 * Pure mathematical terrain sampler designed for distant Level of Detail (LOD) systems (e.g. NoFogGiven)
 * and long-range radar obstacle detection (e.g. Create Aeronautics: Phalanx).
 * <p>
 * Evaluates the world's 3D noise router directly without allocating chunks or performing disk I/O.
 * </p>
 */
public final class DistantTerrainSampler {

    private DistantTerrainSampler() {}

    /**
     * Samples the natural surface height at the given world coordinates in nanoseconds.
     *
     * @param level  ServerLevel instance
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Natural surface Y coordinate (including water/ice surface)
     */
    public static int sampleSurfaceHeight(ServerLevel level, int worldX, int worldZ) {
        Objects.requireNonNull(level, "level cannot be null");
        ServerChunkCache chunkSource = level.getChunkSource();
        ChunkGenerator generator = chunkSource.getGenerator();
        RandomState randomState = chunkSource.randomState();

        return generator.getBaseHeight(worldX, worldZ, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
    }

    /**
     * Samples the ocean floor / solid rock terrain height at the given world coordinates.
     *
     * @param level  ServerLevel instance
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Solid bedrock/stone floor Y coordinate below any water
     */
    public static int sampleOceanFloorHeight(ServerLevel level, int worldX, int worldZ) {
        Objects.requireNonNull(level, "level cannot be null");
        ServerChunkCache chunkSource = level.getChunkSource();
        ChunkGenerator generator = chunkSource.getGenerator();
        RandomState randomState = chunkSource.randomState();

        return generator.getBaseHeight(worldX, worldZ, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
    }
}
