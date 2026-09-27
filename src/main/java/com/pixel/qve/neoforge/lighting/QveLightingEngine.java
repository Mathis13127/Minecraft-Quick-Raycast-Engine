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
            } else if (skyListener != null) {
                LightChunk targetChunk = getter.getChunkForLighting(targetX, targetZ);
                VoxelChunkColumn targetCol = (targetChunk instanceof VoxelLightChunkAdapter adapter) ? adapter.getColumn() : null;
                VoxelSection sec = (targetCol != null) ? targetCol.getSection(secY) : null;
                boolean hasSolid = (sec != null && !sec.isEmpty());

                if (!hasSolid) {
                    int bx = targetX << 4;
                    int by = secY << 4;
                    int bz = targetZ << 4;
                    int c0 = skyListener.getLightValue(new BlockPos(bx, by, bz));
                    int c1 = skyListener.getLightValue(new BlockPos(bx + 15, by, bz));
                    int c2 = skyListener.getLightValue(new BlockPos(bx, by, bz + 15));
                    int c3 = skyListener.getLightValue(new BlockPos(bx + 15, by, bz + 15));
                    int c4 = skyListener.getLightValue(new BlockPos(bx, by + 15, bz));
                    int c5 = skyListener.getLightValue(new BlockPos(bx + 15, by + 15, bz));
                    int c6 = skyListener.getLightValue(new BlockPos(bx, by + 15, bz + 15));
                    int c7 = skyListener.getLightValue(new BlockPos(bx + 15, by + 15, bz + 15));
                    int center = skyListener.getLightValue(new BlockPos(bx + 8, by + 8, bz + 8));

                    if (c0 >= 15 && c1 >= 15 && c2 >= 15 && c3 >= 15 && c4 >= 15 && c5 >= 15 && c6 >= 15 && c7 >= 15 && center >= 15) {
                        skyFullMask |= (1L << idx);
                    } else if (c0 <= 0 && c1 <= 0 && c2 <= 0 && c3 <= 0 && c4 <= 0 && c5 <= 0 && c6 <= 0 && c7 <= 0 && center <= 0) {
                        skyZeroMask |= (1L << idx);
                    } else {
                        rawSkyData[idx] = extractSectionLight(skyListener, targetX, secY, targetZ);
                    }
                } else {
                    rawSkyData[idx] = extractSectionLight(skyListener, targetX, secY, targetZ);
                }
            } else {
                skyFullMask |= (1L << idx);
            }

            if (hasBlockLight && blockListener != null) {
                DataLayer block = blockListener.getDataLayerData(sPos);
                byte[] blockData = (block != null) ? block.getData() : null;
                if (block != null) {
                    if (block.isEmpty() || block.isDefinitelyFilledWith(0) || DefaultVoxelChunkLighting.isUniform(blockData, (byte) 0x00)) {
                        blockZeroMask |= (1L << idx);
                    } else {
                        rawBlockData[idx] = blockData.clone();
                    }
                } else {
                    LightChunk targetChunk = getter.getChunkForLighting(targetX, targetZ);
                    VoxelChunkColumn targetCol = (targetChunk instanceof VoxelLightChunkAdapter adapter) ? adapter.getColumn() : null;
                    VoxelSection sec = (targetCol != null) ? targetCol.getSection(secY) : null;
                    if (sec != null && sec.hasLightEmitters()) {
                        rawBlockData[idx] = extractSectionLight(blockListener, targetX, secY, targetZ);
                    } else {
                        blockZeroMask |= (1L << idx);
                    }
                }
            }
        }

        return new DefaultVoxelChunkLighting(
                targetX, targetZ, minSecY, maxSecY,
                skyFullMask, skyZeroMask, blockZeroMask,
                rawSkyData, rawBlockData
        );
    }

    private static byte[] extractSectionLight(LayerLightEventListener listener, int chunkX, int sectionY, int chunkZ) {
        byte[] data = new byte[2048];
        int baseX = chunkX << 4;
        int baseY = sectionY << 4;
        int baseZ = chunkZ << 4;
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

        for (int ly = 0; ly < 16; ly++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    mpos.set(baseX + lx, baseY + ly, baseZ + lz);
                    int light = listener.getLightValue(mpos);
                    int index = (ly << 8) | (lz << 4) | lx;
                    int byteIndex = index >> 1;
                    if ((index & 1) == 0) {
                        data[byteIndex] |= (byte) (light & 0x0F);
                    } else {
                        data[byteIndex] |= (byte) ((light & 0x0F) << 4);
                    }
                }
            }
        }
        return data;
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
