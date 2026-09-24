package com.pixel.qve.neoforge.network;

import com.pixel.qve.neoforge.QuickVoxelEngineMod;
import com.pixel.qve.neoforge.client.hud.ClientHudHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server-to-Client packet carrying real-time raycast telemetry data
 * for the dedicated client-side HUD overlay.
 */
public record ClientboundRaycastHudPayload(
        boolean active,
        boolean hit,
        long elapsedNs,
        double distance,
        int blockX,
        int blockY,
        int blockZ,
        String face,
        int blockId,
        String blockName,
        String properties,
        double maxDistance
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientboundRaycastHudPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(QuickVoxelEngineMod.MOD_ID, "hud_telemetry"));

    public static final StreamCodec<FriendlyByteBuf, ClientboundRaycastHudPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBoolean(p.active());
                buf.writeBoolean(p.hit());
                buf.writeVarLong(p.elapsedNs());
                buf.writeDouble(p.distance());
                buf.writeVarInt(p.blockX());
                buf.writeVarInt(p.blockY());
                buf.writeVarInt(p.blockZ());
                buf.writeUtf(p.face());
                buf.writeVarInt(p.blockId());
                buf.writeUtf(p.blockName());
                buf.writeUtf(p.properties());
                buf.writeDouble(p.maxDistance());
            },
            buf -> new ClientboundRaycastHudPayload(
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readVarLong(),
                    buf.readDouble(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readUtf(),
                    buf.readVarInt(),
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readDouble()
            )
    );

    /**
     * Factory for an inactive HUD payload sent when tracking is disabled.
     *
     * @return Inactive payload
     */
    public static ClientboundRaycastHudPayload inactive() {
        return new ClientboundRaycastHudPayload(false, false, 0L, 0.0, 0, 0, 0, "NONE", 0, "", "", 0.0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Handles clientbound telemetry reception on the physical client thread.
     *
     * @param payload Received telemetry payload
     * @param context Network context
     */
    public static void handle(ClientboundRaycastHudPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist.isClient()) {
                ClientHudHandler.handle(payload);
            }
        });
    }
}
