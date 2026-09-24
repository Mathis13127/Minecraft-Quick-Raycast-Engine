package com.pixel.qve.world.cache;

import com.pixel.qve.api.IVoxelWorld;
import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.state.ShapeRegistry;

/**
 * Compatibility alias for {@link UnifiedVoxelCache}.
 */
public class VoxelCache extends UnifiedVoxelCache {

    public VoxelCache(BlockIdRegistry blockIdRegistry) {
        super(blockIdRegistry);
    }

    public VoxelCache(BlockIdRegistry blockIdRegistry, ShapeRegistry shapeRegistry) {
        super(blockIdRegistry, shapeRegistry);
    }

    public VoxelCache(BlockIdRegistry blockIdRegistry, ShapeRegistry shapeRegistry, IVoxelWorld diskFallback) {
        super(blockIdRegistry, shapeRegistry, diskFallback);
    }

    public VoxelCache(BlockIdRegistry blockIdRegistry, ShapeRegistry shapeRegistry, IVoxelWorld diskFallback, int minSectionY, int maxSectionY) {
        super(blockIdRegistry, shapeRegistry, diskFallback, minSectionY, maxSectionY);
    }
}
