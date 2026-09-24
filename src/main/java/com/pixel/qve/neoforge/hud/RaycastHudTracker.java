package com.pixel.qve.neoforge.hud;

import com.mojang.brigadier.context.CommandContext;
import com.pixel.qve.api.raycast.RayHitResult;
import com.pixel.qve.raycast.RaycastThreadPool;
import com.pixel.qve.neoforge.api.VoxelRaycastAPI;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.neoforge.network.ClientboundRaycastHudPayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-time HUD overlay tracker for players.
 * Continuously raycasts along the player's crosshair asynchronously up to 99,999+ blocks,
 * transmitting high-frequency telemetry directly to the dedicated client-side HUD overlay card.
 */
public final class RaycastHudTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(RaycastHudTracker.class);

    /** Default reach distance requested for real-time tracking (99,999 blocks). */
    public static final double DEFAULT_MAX_DISTANCE = 99_999.0;

    private static final Map<UUID, HudSession> ACTIVE_SESSIONS = new ConcurrentHashMap<>();

    private RaycastHudTracker() {}

    /**
     * Represents an active tracking session for a player.
     */
    public static final class HudSession {
        private volatile double maxDistance;
        private final AtomicBoolean inFlight = new AtomicBoolean(false);

        /**
         * Constructs a new HUD tracking session.
         *
         * @param maxDistance Maximum reach distance in blocks
         */
        public HudSession(double maxDistance) {
            this.maxDistance = maxDistance;
        }

        /**
         * Gets the maximum reach distance.
         *
         * @return Max reach distance in blocks
         */
        public double getMaxDistance() {
            return maxDistance;
        }

        /**
         * Sets the maximum reach distance.
         *
         * @param maxDistance Max reach distance in blocks
         */
        public void setMaxDistance(double maxDistance) {
            this.maxDistance = maxDistance;
        }

        /**
         * Returns the concurrency guard flag.
         *
         * @return AtomicBoolean in-flight flag
         */
        public AtomicBoolean getInFlight() {
            return inFlight;
        }
    }

    /**
     * Checks if a player currently has HUD target tracking enabled.
     *
     * @param playerUuid UUID of the player
     * @return True if tracking is active, false otherwise
     */
    public static boolean isTracking(UUID playerUuid) {
        return ACTIVE_SESSIONS.containsKey(playerUuid);
    }

    /**
     * Clears all active tracking sessions.
     */
    public static void clearAll() {
        ACTIVE_SESSIONS.clear();
    }

    /**
     * Command handler for toggling HUD tracking with the default distance (99,999 blocks).
     *
     * @param ctx Command context
     * @return 1 on success, 0 on failure
     */
    public static int executeToggleDefault(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§cThis command can only be executed by a player."));
            return 0;
        }

        UUID uuid = player.getUUID();
        if (ACTIVE_SESSIONS.containsKey(uuid)) {
            return disableHud(player, source);
        } else {
            return enableHud(player, source, DEFAULT_MAX_DISTANCE);
        }
    }

    /**
     * Command handler for explicitly enabling or updating HUD tracking with a custom distance.
     *
     * @param ctx      Command context
     * @param distance Maximum reach distance in blocks
     * @return 1 on success, 0 on failure
     */
    public static int executeToggleDistance(CommandContext<CommandSourceStack> ctx, double distance) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§cThis command can only be executed by a player."));
            return 0;
        }

        UUID uuid = player.getUUID();
        HudSession session = ACTIVE_SESSIONS.get(uuid);
        if (session != null) {
            session.setMaxDistance(distance);
            source.sendSuccess(() -> Component.literal(String.format(
                    "§6[QVE] §aHUD Target Tracking reach updated to §f%,.1fm§7.", distance
            )), false);
            return 1;
        } else {
            return enableHud(player, source, distance);
        }
    }

    /**
     * Command handler for explicitly turning ON HUD tracking.
     *
     * @param ctx      Command context
     * @param distance Maximum reach distance in blocks
     * @return 1 on success, 0 on failure
     */
    public static int executeOn(CommandContext<CommandSourceStack> ctx, double distance) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§cThis command can only be executed by a player."));
            return 0;
        }
        return enableHud(player, source, distance);
    }

    /**
     * Command handler for explicitly turning OFF HUD tracking.
     *
     * @param ctx Command context
     * @return 1 on success, 0 on failure
     */
    public static int executeOff(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§cThis command can only be executed by a player."));
            return 0;
        }
        return disableHud(player, source);
    }

    private static int enableHud(ServerPlayer player, CommandSourceStack source, double distance) {
        UUID uuid = player.getUUID();
        ACTIVE_SESSIONS.put(uuid, new HudSession(distance));
        source.sendSuccess(() -> Component.literal(String.format(
                "§6[QVE] §aHUD Telemetry Overlay ENABLED §7(Max reach: §f%,.1fm§7).",
                distance
        )), false);
        return 1;
    }

    private static int disableHud(ServerPlayer player, CommandSourceStack source) {
        UUID uuid = player.getUUID();
        ACTIVE_SESSIONS.remove(uuid);
        PacketDistributor.sendToPlayer(player, ClientboundRaycastHudPayload.inactive());
        player.displayClientMessage(Component.empty(), true);
        source.sendSuccess(() -> Component.literal("§6[QVE] §cHUD Telemetry Overlay DISABLED."), false);
        return 1;
    }

    /**
     * Ticks active HUD tracking sessions every player tick on the server.
     * Offloads the actual 3D raycast to the RaycastThreadPool to prevent blocking the tick loop.
     *
     * @param event Player tick event
     */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (player.hasDisconnected()) {
            ACTIVE_SESSIONS.remove(player.getUUID());
            return;
        }

        UUID uuid = player.getUUID();
        HudSession session = ACTIVE_SESSIONS.get(uuid);
        if (session == null) {
            return;
        }

        // Try to acquire the flight lock. If a raycast is already in progress, skip this tick.
        if (!session.inFlight.compareAndSet(false, true)) {
            return;
        }

        ServerLevel level = player.serverLevel();
        Vec3 start = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();
        double maxDist = session.maxDistance;
        MinecraftServer server = player.getServer();

        RaycastThreadPool.submit(() -> {
            try {
                Vec3 end = start.add(look.scale(maxDist));
                RayHitResult hit = new RayHitResult();
                long t0 = System.nanoTime();
                VoxelRaycastAPI.raycast(level, start, end, hit);
                long elapsedNs = System.nanoTime() - t0;

                int blockId = 0;
                String blockName = "";
                String props = "";
                String faceName = "NONE";

                if (hit.isHit()) {
                    blockId = hit.getBlockId();
                    String rawName = MinecraftVoxelBridge.getBlockRegistry().getName(blockId);
                    blockName = cleanBlockName(rawName);
                    props = MinecraftVoxelBridge.getBlockRegistry().getStateDictionary().formatProperties(blockId);
                    if (hit.getFace() != null) {
                        faceName = hit.getFace().name();
                    }
                }

                // Construct network telemetry payload for dedicated client HUD card
                ClientboundRaycastHudPayload payload = new ClientboundRaycastHudPayload(
                        true,
                        hit.isHit(),
                        elapsedNs,
                        hit.getDistance(),
                        hit.getBlockX(),
                        hit.getBlockY(),
                        hit.getBlockZ(),
                        faceName,
                        blockId,
                        blockName,
                        props,
                        maxDist
                );

                if (server != null) {
                    server.execute(() -> {
                        if (ACTIVE_SESSIONS.containsKey(uuid) && !player.hasDisconnected()) {
                            PacketDistributor.sendToPlayer(player, payload);
                        }
                    });
                }
            } catch (Exception e) {
                LOGGER.error("Error calculating HUD raycast for player {}: {}", player.getScoreboardName(), e.getMessage(), e);
            } finally {
                session.inFlight.set(false);
            }
        });
    }

    /**
     * Cleans the raw block name string by removing embedded blockstate brackets or "Block{...}" wrapper.
     *
     * @param rawName Raw block name string
     * @return Clean canonical registry name (e.g. "minecraft:spruce_leaves")
     */
    public static String cleanBlockName(String rawName) {
        if (rawName == null || rawName.isEmpty()) {
            return "unknown";
        }
        String name = rawName;
        int bracketIdx = name.indexOf('[');
        if (bracketIdx != -1) {
            name = name.substring(0, bracketIdx);
        }
        if (name.startsWith("Block{") && name.endsWith("}")) {
            name = name.substring(6, name.length() - 1);
        }
        return name;
    }

    /**
     * Cleans up sessions when players disconnect from the server.
     *
     * @param event Player logged out event
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        ACTIVE_SESSIONS.remove(event.getEntity().getUUID());
    }

    /**
     * Clears all tracking sessions on server shutdown.
     *
     * @param event Server stopping event
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        clearAll();
    }
}
