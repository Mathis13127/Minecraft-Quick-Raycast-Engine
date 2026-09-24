package com.pixel.qve.world.cache;

import com.pixel.qve.api.IVoxelWorld;
import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.state.ShapeRegistry;

/**
 * Compatibility alias for {@link UnifiedVoxelCache}.
 */
public class VoxelCache extends UnifiedVoxelCache {

    /**
     * Constructs a VoxelCache with a block registry.
     *
     * @param blockIdRegistry Block ID registry
     */
    public VoxelCache(BlockIdRegistry blockIdRegistry) {
        super(blockIdRegistry);
    }

    /**
     * Constructs a VoxelCache with block and shape registries.
     *
     * @param blockIdRegistry Block ID registry
     * @param shapeRegistry   Custom shape registry
     */
    public VoxelCache(BlockIdRegistry blockIdRegistry, ShapeRegistry shapeRegistry) {
        super(blockIdRegistry, shapeRegistry);
    }

    /**
     * Constructs a VoxelCache with disk fallback.
     *
     * @param blockIdRegistry Block ID registry
     * @param shapeRegistry   Custom shape registry
     * @param diskFallback    Disk storage fallback provider
     */
    public VoxelCache(BlockIdRegistry blockIdRegistry, ShapeRegistry shapeRegistry, IVoxelWorld diskFallback) {
        super(blockIdRegistry, shapeRegistry, diskFallback);
    }

    /**
     * Constructs a VoxelCache with explicit vertical boundaries.
     *
     * @param blockIdRegistry Block ID registry
     * @param shapeRegistry   Custom shape registry
     * @param diskFallback    Disk storage fallback provider
     * @param minSectionY     Minimum vertical section coordinate
     * @param maxSectionY     Maximum vertical section coordinate
     */
    public VoxelCache(BlockIdRegistry blockIdRegistry, ShapeRegistry shapeRegistry, IVoxelWorld diskFallback, int minSectionY, int maxSectionY) {
        super(blockIdRegistry, shapeRegistry, diskFallback, minSectionY, maxSectionY);
    }
}
