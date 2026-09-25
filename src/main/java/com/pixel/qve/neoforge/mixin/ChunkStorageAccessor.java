package com.pixel.qve.neoforge.mixin;

import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Mixin accessor to expose the underlying {@link IOWorker} from {@link ChunkStorage}.
 */
@Mixin(ChunkStorage.class)
public interface ChunkStorageAccessor {

    @Accessor("worker")
    IOWorker qve$getWorker();
}
