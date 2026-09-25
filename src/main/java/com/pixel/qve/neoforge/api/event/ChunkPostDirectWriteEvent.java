package com.pixel.qve.neoforge.api.event;

import com.pixel.qve.neoforge.api.WriteResult;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.Event;

import java.util.Objects;

/**
 * NeoForge event fired immediately after a chunk is successfully written directly to an Anvil (.mca) file on disk.
 * Addons (such as NoFogGiven, Phalanx CIWS/radar, and mapping tools) can listen to this event to update LODs or radar tables.
 */
public class ChunkPostDirectWriteEvent extends Event {

    private final Level level;
    private final int chunkX;
    private final int chunkZ;
    private final int regionX;
    private final int regionZ;
    private final WriteResult result;
    private final int modifiedSectionMask;

    public ChunkPostDirectWriteEvent(Level level, int chunkX, int chunkZ, WriteResult result, int modifiedSectionMask) {
        this.level = Objects.requireNonNull(level, "Level cannot be null");
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.regionX = chunkX >> 5;
        this.regionZ = chunkZ >> 5;
        this.result = Objects.requireNonNull(result, "WriteResult cannot be null");
        this.modifiedSectionMask = modifiedSectionMask;
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

    public WriteResult getResult() {
        return result;
    }

    public int getModifiedSectionMask() {
        return modifiedSectionMask;
    }
}
