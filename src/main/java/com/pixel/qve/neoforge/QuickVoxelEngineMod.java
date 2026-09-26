package com.pixel.qve.neoforge;

import com.pixel.qve.neoforge.command.QveCommand;
import com.pixel.qve.neoforge.hud.RaycastHudTracker;


import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
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

        // Register server configuration
        net.neoforged.fml.ModLoadingContext.get().getActiveContainer().registerConfig(
                net.neoforged.fml.config.ModConfig.Type.SERVER,
                com.pixel.qve.neoforge.config.QveConfig.SPEC
        );
        modEventBus.addListener((net.neoforged.fml.event.config.ModConfigEvent e) -> com.pixel.qve.neoforge.config.QveConfig.applyConfig());

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
     * Checks and resumes any uncompleted voxel write batches preserved from the previous session.
     *
     * @param event Server started event
     */
    @SubscribeEvent
    public void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        com.pixel.qve.neoforge.api.VoxelWriteAPI.checkAndResumeRecovery(event.getServer());
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
        com.pixel.qve.neoforge.command.QveCommand.register(event.getDispatcher(), event.getBuildContext());
        LOGGER.info("[RaycastEngine] Registered /raycast and /qve commands.");
    }

    /**
     * Intercepts chunk loads to seamlessly apply any pending deferred mutations
     * that were queued while this chunk was offline in a hybrid region.
     *
     * @param event Chunk load event
     */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!event.getLevel().isClientSide() && event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk lc) {
            int cx = lc.getPos().x;
            int cz = lc.getPos().z;
            if (com.pixel.qve.neoforge.bridge.writer.DeferredChunkQueue.hasEdits(lc.getLevel(), cx, cz)) {
                com.pixel.qve.neoforge.api.ChunkWriteBatch.ChunkEdits edits =
                        com.pixel.qve.neoforge.bridge.writer.DeferredChunkQueue.pollEdits(lc.getLevel(), cx, cz);
                if (edits != null) {
                    com.pixel.qve.neoforge.bridge.writer.DeferredChunkQueue.applyToChunk(lc, edits);
                }
            }
        }
    }

    /**
     * Cleans up all static state, flushes deferred chunk edits to disk, and cleanly terminates writers on server shutdown.
     *
     * @param event Server stopped event
     */
    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        com.pixel.qve.neoforge.bridge.writer.DeferredChunkQueue.flushAllToDisk(event.getServer());
        com.pixel.qve.neoforge.api.VoxelWriteAPI.onServerStopping(event.getServer());
        MinecraftVoxelBridge.reset();
        LOGGER.info("[RaycastEngine] Flushed deferred chunks and cleanly stopped writers on server stop.");
    }
}
