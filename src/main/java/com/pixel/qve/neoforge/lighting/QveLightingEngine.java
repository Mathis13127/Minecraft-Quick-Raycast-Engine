package com.pixel.qve.neoforge.lighting;

import com.pixel.qve.neoforge.api.lighting.VoxelChunkLighting;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelGrid;
import com.pixel.qve.world.VoxelChunkColumn;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Core orchestrator driving Minecraft's official {@link LevelLightEngine} in a headless,
 * isolated 3x3 environment.
 */
public final class QveLightingEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(QveLightingEngine.class);

    private QveLightingEngine() {}

    /**
     * Executes deterministic block and sky light propagation on the target chunk column
     * surrounded by the 3x3 neighbor context gathered via Unified Mode.
     *
     * @param getter Configured UnifiedLightChunkGetter providing the 3x3 chunk window
     * @return Immutable VoxelChunkLighting container holding the computed DataLayers
     */
    public static VoxelChunkLighting computeLighting(UnifiedLightChunkGetter getter) {
        Objects.requireNonNull(getter, "getter cannot be null");

        int targetX = getter.getTargetX();
        int targetZ = getter.getTargetZ();
        int minSecY = getter.getMinSectionY();
        int maxSecY = getter.getMaxSectionY();

        LevelLightEngine lightEngine = new LevelLightEngine(getter, true, true);

        // 1. Enable lighting and mark all sections active across the 3x3 window
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = targetX + dx;
                int cz = targetZ + dz;
                ChunkPos cpos = new ChunkPos(cx, cz);
                lightEngine.setLightEnabled(cpos, true);

                // Include 1 extra section above and below to prevent boundary clamping artifacts
                for (int secY = minSecY - 1; secY <= maxSecY + 1; secY++) {
                    lightEngine.updateSectionStatus(SectionPos.of(cx, secY, cz), false);
                }
            }
        }

        // 2. Propagate sky and block light sources across the 3x3 neighborhood
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = targetX + dx;
                int cz = targetZ + dz;
                lightEngine.propagateLightSources(new ChunkPos(cx, cz));
            }
        }

        // 3. Run the official Mojang BFS flood-fill propagation until all queues are resolved
        int iterations = 0;
        while (lightEngine.hasLightWork()) {
            lightEngine.runLightUpdates();
            iterations++;
            if (iterations > 100_000) {
                LOGGER.warn("[QveLightingEngine] Light updates exceeded 100,000 iterations for chunk ({}, {})", targetX, targetZ);
                break;
            }
        }

        // 4. Extract the resulting DataLayers for the target chunk
        int sectionCount = maxSecY - minSecY;
        DataLayer[] skyLayers = new DataLayer[sectionCount];
        DataLayer[] blockLayers = new DataLayer[sectionCount];

        LayerLightEventListener skyListener = lightEngine.getLayerListener(LightLayer.SKY);
        LayerLightEventListener blockListener = lightEngine.getLayerListener(LightLayer.BLOCK);

        for (int secY = minSecY; secY < maxSecY; secY++) {
            int idx = secY - minSecY;
            SectionPos sPos = SectionPos.of(targetX, secY, targetZ);

            DataLayer sky = skyListener.getDataLayerData(sPos);
            DataLayer block = blockListener.getDataLayerData(sPos);

            skyLayers[idx] = (sky != null) ? sky.copy() : null;
            blockLayers[idx] = (block != null) ? block.copy() : null;
        }

        return new DefaultVoxelChunkLighting(targetX, targetZ, minSecY, maxSecY, skyLayers, blockLayers);
    }

    /**
     * Helper method to compute lighting directly from Level and target VoxelChunkColumn.
     *
     * @param level        Minecraft Level
     * @param grid         MinecraftVoxelGrid spatial accessor
     * @param targetColumn Target chunk column
     * @return Computed VoxelChunkLighting
     */
    public static VoxelChunkLighting computeLighting(Level level, MinecraftVoxelGrid grid, VoxelChunkColumn targetColumn) {
        UnifiedLightChunkGetter getter = new UnifiedLightChunkGetter(level, grid, targetColumn);
        return computeLighting(getter);
    }
}
