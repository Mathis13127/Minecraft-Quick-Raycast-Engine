package com.pixel.qve.neoforge.mixin;

import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Mixin accessor to expose the underlying {@link RegionFileStorage} from {@link IOWorker}.
 */
@Mixin(IOWorker.class)
public interface IOWorkerAccessor {

    @Accessor("storage")
    RegionFileStorage qve$getStorage();
}
