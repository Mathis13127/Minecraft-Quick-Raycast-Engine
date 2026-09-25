package com.pixel.qve.neoforge.api.event;

import com.pixel.qve.mca.writer.IChunkWriteContext;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

import java.util.Objects;

/**
 * NeoForge event fired immediately before a chunk is serialized and written directly to an Anvil (.mca) file on disk.
 * Cancellable: cancelling this event cleanly aborts the disk write operation.
 */
public class ChunkPreDirectWriteEvent extends Event implements ICancellableEvent {

    private final Level level;
    private final int chunkX;
    private final int chunkZ;
    private final int regionX;
    private final int regionZ;
    private final IChunkWriteContext context;

    public ChunkPreDirectWriteEvent(Level level, int chunkX, int chunkZ, IChunkWriteContext context) {
        this.level = Objects.requireNonNull(level, "Level cannot be null");
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.regionX = chunkX >> 5;
        this.regionZ = chunkZ >> 5;
        this.context = context;
    }

    public Level getLevel() {
        return level;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public int getRegionX() {
        return regionX;
    }

    public int getRegionZ() {
        return regionZ;
    }

    public IChunkWriteContext getContext() {
        return context;
    }
}
