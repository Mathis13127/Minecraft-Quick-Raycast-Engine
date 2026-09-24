package com.pixel.qve.raycast;

import com.pixel.qve.api.IVoxelWorld;
import com.pixel.qve.api.raycast.IRaycastService;
import com.pixel.qve.api.raycast.Ray3f;
import com.pixel.qve.api.raycast.RayHitResult;

import java.util.List;
import java.util.Objects;

/**
 * Ultra-high-throughput parallel raycast batch executor.
 * Automatically distributes rays in parallel chunks across worker threads with zero per-ray allocation.
 */
public final class VoxelRaycastBatch implements IRaycastService {

    /** Global singleton instance. */
    public static final VoxelRaycastBatch INSTANCE = new VoxelRaycastBatch();

    private VoxelRaycastBatch() {}

    @Override
    public boolean trace(Ray3f ray, IVoxelWorld world, RayHitResult result) {
        return VoxelDDA.trace(ray, world, result);
    }

    @Override
    public void traceBatch(Ray3f[] rays, RayHitResult[] results, IVoxelWorld world) {
        Objects.requireNonNull(rays, "Rays array cannot be null");
        Objects.requireNonNull(results, "Results array cannot be null");
        Objects.requireNonNull(world, "IVoxelWorld cannot be null");

        int len = Math.min(rays.length, results.length);
        RaycastThreadPool.parallelFor(0, len, i -> {
            Ray3f ray = rays[i];
            RayHitResult res = results[i];
            if (ray != null && res != null) {
                VoxelDDA.trace(ray, world, res);
            }
        });
    }

    @Override
    public void traceBatch(List<Ray3f> rays, List<RayHitResult> results, IVoxelWorld world) {
        Objects.requireNonNull(rays, "Rays list cannot be null");
        Objects.requireNonNull(results, "Results list cannot be null");
        Objects.requireNonNull(world, "IVoxelWorld cannot be null");

        int len = Math.min(rays.size(), results.size());
        RaycastThreadPool.parallelFor(0, len, i -> {
            Ray3f ray = rays.get(i);
            RayHitResult res = results.get(i);
            if (ray != null && res != null) {
                VoxelDDA.trace(ray, world, res);
            }
        });
    }
}
