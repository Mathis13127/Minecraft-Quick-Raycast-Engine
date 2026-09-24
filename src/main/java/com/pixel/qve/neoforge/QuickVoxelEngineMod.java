package com.pixel.qve.neoforge;

import com.pixel.qve.neoforge.command.QveCommand;
import com.pixel.qve.neoforge.hud.RaycastHudTracker;


import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
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
@Mod(QuickVoxelEngineMod.MOD_ID)
public class QuickVoxelEngineMod {
    /** Unique mod identifier. */
    public static final String MOD_ID = "quickvoxelengine";
    /** Global SLF4J logger for the raycast engine. */
    public static final Logger LOGGER = LoggerFactory.getLogger("RaycastEngine");

    /**
     * Initializes the mod instance and registers listeners to NeoForge's event bus.
     *
     * @param modEventBus The NeoForge mod-lifecycle event bus
     */
    public QuickVoxelEngineMod(IEventBus modEventBus) {
        LOGGER.info("[QuickVoxelEngine] Initializing Quick Voxel Engine for Minecraft 1.21.1...");
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(com.pixel.qve.neoforge.hud.RaycastHudTracker.class);

        // Register network payloads
        modEventBus.addListener(com.pixel.qve.neoforge.network.QveNetwork::register);

        // Register client HUD and key mappings if on physical client
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            com.pixel.qve.neoforge.client.QveClientSetup.init(modEventBus);
        }

        // Hook asynchronous warmup on mod loading complete
        modEventBus.addListener(this::onLoadComplete);
    }

    private void onLoadComplete(net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent event) {
        com.pixel.qve.neoforge.warmup.VoxelWarmupEngine.startAsyncWarmup();
    }

    /**
     * Ensures warmup has started when server starts.
     *
     * @param event Server starting event
     */
    @SubscribeEvent
    public void onServerStarting(net.neoforged.neoforge.event.server.ServerStartingEvent event) {
        com.pixel.qve.neoforge.warmup.VoxelWarmupEngine.startAsyncWarmup();
    }

    /**
     * Cleans up dimension voxel grids when a world unloads.
     *
     * @param event Level unload event
     */
    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        LevelAccessor level = event.getLevel();
        if (level instanceof net.minecraft.world.level.Level l) {
            MinecraftVoxelBridge.onLevelUnloaded(l);
        }
    }

    /**
     * Registers developer and testing commands with Brigadier.
     *
     * @param event Register commands event
     */
    @SubscribeEvent
    public void onRegisterCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        com.pixel.qve.neoforge.command.QveCommand.register(event.getDispatcher());
        LOGGER.info("[RaycastEngine] Registered /raycast and /qre commands.");
    }

    /**
     * Cleans up all static state and caches on server shutdown.
     *
     * @param event Server stopping event
     */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        MinecraftVoxelBridge.reset();
        LOGGER.info("[RaycastEngine] Cleared all voxel grids and cache references on server stop.");
    }
}
