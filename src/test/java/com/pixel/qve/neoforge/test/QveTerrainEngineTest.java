package com.pixel.qve.neoforge.test;

import com.pixel.qve.neoforge.terrain.DistantTerrainSampler;
import com.pixel.qve.neoforge.terrain.TerrainGenerationResult;
import com.pixel.qve.neoforge.terrain.TerrainStage;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the core data models, stage transitions, and telemetry records of the QVE terrain subsystem.
 */
public class QveTerrainEngineTest {

    @Test
    @DisplayName("Verify TerrainStage parsing and status mappings")
    void testTerrainStageParsing() {
        assertEquals(TerrainStage.BIOMES, TerrainStage.fromString("biomes"));
        assertEquals(TerrainStage.BIOMES, TerrainStage.fromString("BIOME"));
        assertEquals(TerrainStage.NOISE, TerrainStage.fromString("noise"));
        assertEquals(TerrainStage.NOISE, TerrainStage.fromString("stone"));
        assertEquals(TerrainStage.NOISE, TerrainStage.fromString("density"));
        assertEquals(TerrainStage.SURFACE, TerrainStage.fromString("surface"));
        assertEquals(TerrainStage.SURFACE, TerrainStage.fromString("DEFAULT"));
        assertEquals(TerrainStage.CARVERS, TerrainStage.fromString("carvers"));
        assertEquals(TerrainStage.CARVERS, TerrainStage.fromString("caves"));

        // Null and unknown fallback to SURFACE
        assertEquals(TerrainStage.SURFACE, TerrainStage.fromString(null));
        assertEquals(TerrainStage.SURFACE, TerrainStage.fromString(""));
        assertEquals(TerrainStage.SURFACE, TerrainStage.fromString("unknown_xyz"));

        // ChunkStatus mappings
        assertEquals(ChunkStatus.BIOMES, TerrainStage.BIOMES.getChunkStatus());
        assertEquals(ChunkStatus.NOISE, TerrainStage.NOISE.getChunkStatus());
        assertEquals(ChunkStatus.SURFACE, TerrainStage.SURFACE.getChunkStatus());
        assertEquals(ChunkStatus.CARVERS, TerrainStage.CARVERS.getChunkStatus());
    }

    @Test
    @DisplayName("Verify TerrainGenerationResult success and failure contracts")
    void testTerrainGenerationResultContracts() {
        TerrainGenerationResult success = TerrainGenerationResult.success(10, 20, TerrainStage.SURFACE, 15_500_000L, 2, 4096);
        assertTrue(success.success());
        assertEquals(10, success.chunkX());
        assertEquals(20, success.chunkZ());
        assertEquals(TerrainStage.SURFACE, success.stage());
        assertEquals(15.5, success.durationMs(), 0.001);
        assertEquals(2, success.sectorOffset());
        assertEquals(4096, success.compressedBytes());
        assertNull(success.errorMessage());

        TerrainGenerationResult failure = TerrainGenerationResult.failure(5, -5, TerrainStage.NOISE, "Disk write error");
        assertFalse(failure.success());
        assertEquals(5, failure.chunkX());
        assertEquals(-5, failure.chunkZ());
        assertEquals(TerrainStage.NOISE, failure.stage());
        assertEquals("Disk write error", failure.errorMessage());
        assertEquals(0.0, failure.durationMs());
    }

    @Test
    @DisplayName("Verify DistantTerrainSampler input validation")
    void testDistantTerrainSamplerValidation() {
        assertThrows(NullPointerException.class, () -> DistantTerrainSampler.sampleSurfaceHeight(null, 100, 200));
        assertThrows(NullPointerException.class, () -> DistantTerrainSampler.sampleOceanFloorHeight(null, 100, 200));
    }
}
