package com.pixel.qve.neoforge.lighting;

import com.pixel.qve.neoforge.api.lighting.VoxelChunkLighting;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelGrid;
import com.pixel.qve.world.VoxelChunkColumn;
import com.pixel.qve.world.VoxelSection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunk;
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
        int sectionCount = maxSecY - minSecY;

        boolean hasBlockLight = getter.hasAnyLightEmitters();
        LevelLightEngine lightEngine = new LevelLightEngine(getter, hasBlockLight, true);

        // 1. Enable lighting and mark all sections active across the 3x3 window
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = targetX + dx;
                int cz = targetZ + dz;
                ChunkPos cpos = new ChunkPos(cx, cz);
                lightEngine.setLightEnabled(cpos, true);

                LightChunk chunk = getter.getChunkForLighting(cx, cz);
                VoxelChunkColumn col = (chunk instanceof VoxelLightChunkAdapter adapter) ? adapter.getColumn() : null;

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
        long skyFullMask = 0L;
        long skyZeroMask = 0L;
        long blockZeroMask = 0L;
        byte[][] rawSkyData = new byte[sectionCount][];
        byte[][] rawBlockData = new byte[sectionCount][];

        LayerLightEventListener skyListener = lightEngine.getLayerListener(LightLayer.SKY);
        LayerLightEventListener blockListener = hasBlockLight ? lightEngine.getLayerListener(LightLayer.BLOCK) : null;

        if (!hasBlockLight) {
            blockZeroMask = (sectionCount >= 64) ? -1L : ((1L << sectionCount) - 1);
        }

        for (int secY = minSecY; secY < maxSecY; secY++) {
            int idx = secY - minSecY;
            SectionPos sPos = SectionPos.of(targetX, secY, targetZ);

            DataLayer sky = (skyListener != null) ? skyListener.getDataLayerData(sPos) : null;
            byte[] skyData = (sky != null) ? sky.getData() : null;
            if (sky != null) {
                if (sky.isDefinitelyFilledWith(15) || DefaultVoxelChunkLighting.isUniform(skyData, (byte) 0xFF)) {
                    skyFullMask |= (1L << idx);
                } else if (sky.isEmpty() || sky.isDefinitelyFilledWith(0) || DefaultVoxelChunkLighting.isUniform(skyData, (byte) 0x00)) {
                    skyZeroMask |= (1L << idx);
                } else {
                    rawSkyData[idx] = skyData.clone();
                }
            } else {
                int centerLight = (skyListener != null)
                        ? skyListener.getLightValue(new BlockPos((targetX << 4) + 8, (secY << 4) + 8, (targetZ << 4) + 8))
                        : 15;
                if (centerLight >= 15) {
                    skyFullMask |= (1L << idx);
                } else {
                    skyZeroMask |= (1L << idx);
                }
            }

            if (hasBlockLight && blockListener != null) {
                DataLayer block = blockListener.getDataLayerData(sPos);
                byte[] blockData = (block != null) ? block.getData() : null;
                if (block == null || block.isEmpty() || block.isDefinitelyFilledWith(0) || DefaultVoxelChunkLighting.isUniform(blockData, (byte) 0x00)) {
                    blockZeroMask |= (1L << idx);
                } else {
                    rawBlockData[idx] = blockData.clone();
                }
            }
        }

        return new DefaultVoxelChunkLighting(
                targetX, targetZ, minSecY, maxSecY,
                skyFullMask, skyZeroMask, blockZeroMask,
                rawSkyData, rawBlockData
        );
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
