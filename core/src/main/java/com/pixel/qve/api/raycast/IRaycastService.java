package com.pixel.qve.api.raycast;

import com.pixel.qve.api.IVoxelWorld;

import java.util.List;

/**
 * Public service interface for executing single and parallel batch 3D DDA raycasts
 * through live or offline voxel worlds at multi-million ray per second throughput.
 */
public interface IRaycastService {

    /**
     * Executes a single 3D DDA raycast against the voxel world.
     *
     * @param ray    Ray definition containing origin, direction, and max distance
     * @param world  Target voxel world
     * @param result Mutable result populated with impact data
     * @return True if a solid block was struck, false on miss
     */
    boolean trace(Ray3f ray, IVoxelWorld world, RayHitResult result);

    /**
     * Traces an array of rays in parallel across worker threads.
     *
     * @param rays    Array of rays to trace
     * @param results Array of mutable result containers populated with impact data
     * @param world   Target voxel world
     */
    void traceBatch(Ray3f[] rays, RayHitResult[] results, IVoxelWorld world);

    /**
     * Traces a list of rays in parallel across worker threads.
     *
     * @param rays    List of rays to trace
     * @param results List of mutable result containers populated with impact data
     * @param world   Target voxel world
     */
    void traceBatch(List<Ray3f> rays, List<RayHitResult> results, IVoxelWorld world);
}
