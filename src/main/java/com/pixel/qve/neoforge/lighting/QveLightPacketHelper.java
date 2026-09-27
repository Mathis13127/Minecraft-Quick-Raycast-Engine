package com.pixel.qve.neoforge.lighting;

import com.pixel.qve.neoforge.api.lighting.VoxelChunkLighting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;

/**
 * Utility responsible for constructing and broadcasting authentic and diagnostic
 * {@link ClientboundLightUpdatePacket} instances to connected Minecraft clients.
 */
public final class QveLightPacketHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(QveLightPacketHelper.class);

    private QveLightPacketHelper() {}

    /**
     * Corruption modes supported by {@code /qve light corrupt}.
     */
    public enum CorruptMode {
        DARK,
        BRIGHT,
        RANDOM;

        /**
         * Resolves a string input to a {@link CorruptMode} with aliases.
         *
         * @param name Raw mode name from user command
         * @return Matching CorruptMode, or null if unknown
         */
        public static CorruptMode fromString(@Nullable String name) {
            if (name == null) {
                return null;
            }
            return switch (name.toLowerCase(Locale.ROOT).trim()) {
                case "dark", "black", "0", "zero" -> DARK;
                case "bright", "white", "15", "max", "full" -> BRIGHT;
                case "random", "rand", "noise", "glitch" -> RANDOM;
                default -> null;
            };
        }
    }

    /**
     * Constructs a genuine {@link ClientboundLightUpdatePacket} directly from computed {@link VoxelChunkLighting}.
     *
     * @param chunkPos       Chunk coordinates
     * @param heightAccessor World height accessor (e.g. ServerLevel or column)
     * @param lighting       Computed lighting data
     * @return Serialized clientbound light packet ready for network transmission
     */
    public static ClientboundLightUpdatePacket createUpdatePacket(
            ChunkPos chunkPos,
            LevelHeightAccessor heightAccessor,
            VoxelChunkLighting lighting
    ) {
        Objects.requireNonNull(chunkPos, "chunkPos cannot be null");
        Objects.requireNonNull(heightAccessor, "heightAccessor cannot be null");
        Objects.requireNonNull(lighting, "lighting cannot be null");

        int minSec = heightAccessor.getMinSection();
        int maxSec = heightAccessor.getMaxSection();

        VoxelLightEngineAdapter adapter = new VoxelLightEngineAdapter(heightAccessor, (layer, secY) -> {
            if (secY < minSec) {
                return new DataLayer(0);
            }
            if (secY >= maxSec) {
                return (layer == LightLayer.SKY) ? new DataLayer(15) : new DataLayer(0);
            }
            if (layer == LightLayer.SKY) {
                DataLayer sky = lighting.getSkyDataLayer(secY);
                return (sky != null) ? sky : new DataLayer(0);
            } else {
                DataLayer block = lighting.getBlockDataLayer(secY);
                return (block != null) ? block : new DataLayer(0);
            }
        });

        return new ClientboundLightUpdatePacket(chunkPos, adapter, null, null);
    }

    /**
     * Constructs a corrupted / glitched {@link ClientboundLightUpdatePacket} for diagnostic visual testing.
     *
     * @param chunkPos       Chunk coordinates
     * @param heightAccessor World height accessor
     * @param mode           Corruption mode (DARK, BRIGHT, or RANDOM)
     * @return Serialized corrupted clientbound light packet
     */
    public static ClientboundLightUpdatePacket createCorruptedPacket(
            ChunkPos chunkPos,
            LevelHeightAccessor heightAccessor,
            CorruptMode mode
    ) {
        Objects.requireNonNull(chunkPos, "chunkPos cannot be null");
        Objects.requireNonNull(heightAccessor, "heightAccessor cannot be null");
        Objects.requireNonNull(mode, "mode cannot be null");

        VoxelLightEngineAdapter adapter = new VoxelLightEngineAdapter(heightAccessor, (layer, secY) -> {
            return switch (mode) {
                case DARK -> new DataLayer(0);
                case BRIGHT -> new DataLayer(15);
                case RANDOM -> {
                    byte[] noise = new byte[DataLayer.SIZE];
                    ThreadLocalRandom.current().nextBytes(noise);
                    yield new DataLayer(noise);
                }
            };
        });

        return new ClientboundLightUpdatePacket(chunkPos, adapter, null, null);
    }

    /**
     * Broadcasts a light update packet to all players actively tracking the corresponding chunk.
     *
     * @param level  Target ServerLevel
     * @param packet Packet to send
     */
    public static void sendPacketToTrackingPlayers(ServerLevel level, ClientboundLightUpdatePacket packet) {
        Objects.requireNonNull(level, "level cannot be null");
        Objects.requireNonNull(packet, "packet cannot be null");

        ChunkPos chunkPos = new ChunkPos(packet.getX(), packet.getZ());
        List<ServerPlayer> players = level.getChunkSource().chunkMap.getPlayers(chunkPos, false);
        for (ServerPlayer player : players) {
            player.connection.send(packet);
        }
    }

    /**
     * Broadcasts a collection of light update packets to tracking players.
     *
     * @param level   Target ServerLevel
     * @param packets Collection of packets to send
     */
    public static void sendPacketsToTrackingPlayers(ServerLevel level, Collection<ClientboundLightUpdatePacket> packets) {
        Objects.requireNonNull(level, "level cannot be null");
        Objects.requireNonNull(packets, "packets cannot be null");

        for (ClientboundLightUpdatePacket packet : packets) {
            sendPacketToTrackingPlayers(level, packet);
        }
    }

    /**
     * Minimal, zero-allocation {@link LevelLightEngine} adapter bridging QVE voxel lighting data
     * into Minecraft's {@link net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData}.
     */
    public static class VoxelLightEngineAdapter extends LevelLightEngine {

        private final LevelHeightAccessor heightAccessor;
        private final BiFunction<LightLayer, Integer, DataLayer> dataProvider;

        public VoxelLightEngineAdapter(
                LevelHeightAccessor heightAccessor,
                BiFunction<LightLayer, Integer, DataLayer> dataProvider
        ) {
            super(createLightChunkGetter(heightAccessor), false, false);
            this.heightAccessor = Objects.requireNonNull(heightAccessor, "heightAccessor cannot be null");
            this.dataProvider = Objects.requireNonNull(dataProvider, "dataProvider cannot be null");
        }

        private static LightChunkGetter createLightChunkGetter(LevelHeightAccessor heightAccessor) {
            BlockGetter bg = (heightAccessor instanceof BlockGetter b) ? b : new BlockGetter() {
                @Override public int getHeight() { return heightAccessor.getHeight(); }
                @Override public int getMinBuildHeight() { return heightAccessor.getMinBuildHeight(); }
                @Override public int getMinSection() { return heightAccessor.getMinSection(); }
                @Override public int getMaxSection() { return heightAccessor.getMaxSection(); }
                @Override public int getSectionsCount() { return heightAccessor.getSectionsCount(); }
                @Nullable @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
                @Override public BlockState getBlockState(BlockPos pos) { return Blocks.AIR.defaultBlockState(); }
                @Override public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
            };

            return new LightChunkGetter() {
                @Nullable
                @Override
                public LightChunk getChunkForLighting(int chunkX, int chunkZ) {
                    return null;
                }

                @Override
                public BlockGetter getLevel() {
                    return bg;
                }
            };
        }

        @Override
        public int getLightSectionCount() {
            return heightAccessor.getSectionsCount() + 2;
        }

        @Override
        public int getMinLightSection() {
            return heightAccessor.getMinSection() - 1;
        }

        @Override
        public int getMaxLightSection() {
            return getMinLightSection() + getLightSectionCount();
        }

        @Override
        public LayerLightEventListener getLayerListener(LightLayer type) {
            return new LayerLightEventListener() {
                @Nullable
                @Override
                public DataLayer getDataLayerData(SectionPos sectionPos) {
                    return dataProvider.apply(type, sectionPos.y());
                }

                @Override public int getLightValue(BlockPos pos) { return 0; }
                @Override public void checkBlock(BlockPos pos) {}
                @Override public boolean hasLightWork() { return false; }
                @Override public int runLightUpdates() { return 0; }
                @Override public void updateSectionStatus(SectionPos pos, boolean isEmpty) {}
                @Override public void setLightEnabled(ChunkPos chunkPos, boolean lightEnabled) {}
                @Override public void propagateLightSources(ChunkPos chunkPos) {}
            };
        }
    }
}
