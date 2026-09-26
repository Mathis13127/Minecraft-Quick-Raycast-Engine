package com.pixel.qve.neoforge.terrain;

import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Defines the cutoff generation stages for QVE's isolated headless terrain generator.
 * <p>
 * To prevent cascading neighbor chunk loading, stages are strictly confined to self-contained
 * chunk operations (BIOMES, NOISE, SURFACE, and CARVERS).
 * </p>
 */
public enum TerrainStage {

    /**
     * Resolves multi-noise and 3D Voronoi biome distribution.
     */
    BIOMES("biomes", ChunkStatus.BIOMES),

    /**
     * Computes 3D density noise, aquifers, oceans, stone, deepslate, and cave voids.
     */
    NOISE("noise", ChunkStatus.NOISE),

    /**
     * Applies biome surface rules (grass blocks, sand, dirt, sandstone, gravel).
     * Recommended default: leaves chunk ready for Minecraft to generate trees and light naturally.
     */
    SURFACE("surface", ChunkStatus.SURFACE),

    /**
     * Carves natural cave systems and canyon ravines.
     */
    CARVERS("carvers", ChunkStatus.CARVERS);

    private final String name;
    private final ChunkStatus chunkStatus;

    TerrainStage(String name, ChunkStatus chunkStatus) {
        this.name = name;
        this.chunkStatus = chunkStatus;
    }

    public String getName() {
        return name;
    }

    public ChunkStatus getChunkStatus() {
        return chunkStatus;
    }

    /**
     * Parses a string into a TerrainStage, defaulting to {@link #SURFACE} if unknown.
     *
     * @param str Input string
     * @return Resolved TerrainStage
     */
    public static TerrainStage fromString(String str) {
        if (str == null || str.isBlank()) {
            return SURFACE;
        }
        return switch (str.trim().toLowerCase()) {
            case "biomes", "biome" -> BIOMES;
            case "noise", "stone", "density" -> NOISE;
            case "carvers", "carver", "caves" -> CARVERS;
            case "surface", "default" -> SURFACE;
            default -> SURFACE;
        };
    }
}
