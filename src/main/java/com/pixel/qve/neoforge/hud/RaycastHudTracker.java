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
 * Continuously raycasts along the player's crosshair asynchronously up to 99,999+ blocks.
 * Supports both a dedicated high-fidelity client HUD card and a stabilized, non-jittering action bar fallback.
 */
public final class RaycastHudTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(RaycastHudTracker.class);

    /** Default reach distance requested for real-time tracking (99,999 blocks). */
    public static final double DEFAULT_MAX_DISTANCE = 99_999.0;

    /**
     * Display mode for the HUD telemetry.
     */
    public enum HudDisplayMode {
        CARD,
        ACTIONBAR,
        BOTH
    }

    private static final Map<UUID, HudSession> ACTIVE_SESSIONS = new ConcurrentHashMap<>();

    private RaycastHudTracker() {}

    /**
     * Represents an active tracking session for a player.
     */
    public static final class HudSession {
        private volatile double maxDistance;
        private volatile HudDisplayMode mode;
        private final AtomicBoolean inFlight = new AtomicBoolean(false);

        /**
         * Constructs a new HUD tracking session with default BOTH display mode.
         *
         * @param maxDistance Maximum reach distance in blocks
         */
        public HudSession(double maxDistance) {
            this(maxDistance, HudDisplayMode.BOTH);
        }

        /**
         * Constructs a new HUD tracking session with explicit display mode.
         *
         * @param maxDistance Maximum reach distance in blocks
         * @param mode        Display mode
         */
        public HudSession(double maxDistance, HudDisplayMode mode) {
            this.maxDistance = maxDistance;
            this.mode = mode != null ? mode : HudDisplayMode.BOTH;
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
         * Gets the display mode.
         *
         * @return Display mode
         */
        public HudDisplayMode getMode() {
            return mode;
        }

        /**
         * Sets the display mode.
         *
         * @param mode Display mode
         */
        public void setMode(HudDisplayMode mode) {
            this.mode = mode != null ? mode : HudDisplayMode.BOTH;
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
            return enableHud(player, source, DEFAULT_MAX_DISTANCE, HudDisplayMode.BOTH);
        }
    }

    /**
     * Command handler for changing the display mode of an active session.
     *
     * @param ctx  Command context
     * @param mode Target display mode
     * @return 1 on success, 0 on failure
     */
    public static int executeSetMode(CommandContext<CommandSourceStack> ctx, HudDisplayMode mode) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§cThis command can only be executed by a player."));
            return 0;
        }

        UUID uuid = player.getUUID();
        HudSession session = ACTIVE_SESSIONS.get(uuid);
        if (session != null) {
            session.setMode(mode);
            if (mode == HudDisplayMode.CARD) {
                player.displayClientMessage(Component.empty(), true);
            } else if (mode == HudDisplayMode.ACTIONBAR) {
                PacketDistributor.sendToPlayer(player, ClientboundRaycastHudPayload.inactive());
            }
            source.sendSuccess(() -> Component.literal(String.format(
                    "§6[QVE] §aHUD display mode updated to §e%s§7.", mode.name()
            )), false);
            return 1;
        } else {
            return enableHud(player, source, DEFAULT_MAX_DISTANCE, mode);
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
            return enableHud(player, source, distance, HudDisplayMode.BOTH);
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
        return enableHud(player, source, distance, HudDisplayMode.BOTH);
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

    private static int enableHud(ServerPlayer player, CommandSourceStack source, double distance, HudDisplayMode mode) {
        UUID uuid = player.getUUID();
        ACTIVE_SESSIONS.put(uuid, new HudSession(distance, mode));
        source.sendSuccess(() -> Component.literal(String.format(
                "§6[QVE] §aHUD Target Tracking ENABLED §7(Reach: §f%,.1fm§7, Mode: §e%s§7).",
                distance, mode.name()
        )), false);
        return 1;
    }

    private static int disableHud(ServerPlayer player, CommandSourceStack source) {
        UUID uuid = player.getUUID();
        ACTIVE_SESSIONS.remove(uuid);
        PacketDistributor.sendToPlayer(player, ClientboundRaycastHudPayload.inactive());
        player.displayClientMessage(Component.empty(), true);
        source.sendSuccess(() -> Component.literal("§6[QVE] §cHUD Target Tracking DISABLED."), false);
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

                String latencyStr;
                if (elapsedNs < 1_000) {
                    latencyStr = String.format("§a%d ns", elapsedNs);
                } else if (elapsedNs < 1_000_000) {
                    latencyStr = String.format("§a%.1f µs", elapsedNs / 1000.0);
                } else {
                    latencyStr = String.format("§e%.2f ms", elapsedNs / 1_000_000.0);
                }

                int blockId = 0;
                String blockName = "";
                String props = "";
                String faceName = "NONE";

                if (hit.isHit()) {
                    blockId = hit.getBlockId();
                    blockName = MinecraftVoxelBridge.getBlockRegistry().getName(blockId);
                    if (blockName == null || blockName.isEmpty()) {
                        blockName = "unknown";
                    }
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

                // Build clean, stabilized action bar message (no newlines, essential telemetry firmly anchored)
                String abMessage;
                if (hit.isHit()) {
                    String shortName = blockName.startsWith("minecraft:") ? blockName.substring(10) : blockName;
                    abMessage = String.format(
                            "§6[QVE] §a%s §8│ §e%.1fm §8│ §f[%d, %d, %d] §8│ §b%s §8(#%d)",
                            latencyStr,
                            hit.getDistance(),
                            hit.getBlockX(), hit.getBlockY(), hit.getBlockZ(),
                            shortName,
                            blockId
                    );
                } else {
                    abMessage = String.format(
                            "§6[QVE] §a%s §8│ §7Miss/Air §8(§7>%,.0fm§8)",
                            latencyStr,
                            maxDist
                    );
                }
                Component actionBarComponent = Component.literal(abMessage);

                if (server != null) {
                    server.execute(() -> {
                        if (ACTIVE_SESSIONS.containsKey(uuid) && !player.hasDisconnected()) {
                            HudDisplayMode mode = session.getMode();

                            // Dispatch payload for dedicated client HUD card
                            if (mode == HudDisplayMode.CARD || mode == HudDisplayMode.BOTH) {
                                PacketDistributor.sendToPlayer(player, payload);
                            }

                            // Dispatch stabilized action bar overlay
                            if (mode == HudDisplayMode.ACTIONBAR || mode == HudDisplayMode.BOTH) {
                                player.displayClientMessage(actionBarComponent, true);
                            }
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
