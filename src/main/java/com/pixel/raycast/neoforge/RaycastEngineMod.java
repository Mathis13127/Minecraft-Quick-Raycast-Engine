package com.pixel.raycast.neoforge;

import com.pixel.raycast.neoforge.bridge.MinecraftVoxelBridge;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the Raycast Engine NeoForge mod.
 * Handles level lifecycle events and cache cleanup.
 */
@Mod(RaycastEngineMod.MOD_ID)
public class RaycastEngineMod {
    public static final String MOD_ID = "raycastengine";
    public static final Logger LOGGER = LoggerFactory.getLogger("RaycastEngine");

    public RaycastEngineMod(IEventBus modEventBus) {
        LOGGER.info("[RaycastEngine] Initializing Ultra-Fast Raycast Engine for Minecraft 1.21.1...");
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        LevelAccessor level = event.getLevel();
        if (level instanceof net.minecraft.world.level.Level l) {
            MinecraftVoxelBridge.onLevelUnloaded(l);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        MinecraftVoxelBridge.reset();
        LOGGER.info("[RaycastEngine] Cleared all voxel grids and cache references on server stop.");
    }
}
