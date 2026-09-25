package com.pixel.qve.neoforge.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Mixin accessor to expose the region file cache from {@link RegionFileStorage}.
 */
@Mixin(RegionFileStorage.class)
public interface RegionFileStorageAccessor {

    @Accessor("regionCache")
    Long2ObjectLinkedOpenHashMap<RegionFile> qve$getRegionCache();
}
