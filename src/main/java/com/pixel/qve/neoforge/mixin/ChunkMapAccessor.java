package com.pixel.qve.neoforge.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Mixin accessor to expose in-memory chunk tracking maps from {@link ChunkMap}.
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {

    @Accessor("updatingChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> qve$getUpdatingChunkMap();

    @Accessor("visibleChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> qve$getVisibleChunkMap();

    @Accessor("pendingUnloads")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> qve$getPendingUnloads();

    @Invoker("saveChunkIfNeeded")
    boolean qve$saveChunkIfNeeded(ChunkHolder holder);
}
