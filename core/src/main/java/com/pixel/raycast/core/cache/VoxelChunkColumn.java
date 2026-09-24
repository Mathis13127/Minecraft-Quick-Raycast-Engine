package com.pixel.raycast.core.cache;

import com.pixel.raycast.core.voxel.Heightmap2D;
import com.pixel.raycast.core.voxel.VoxelSection;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Thread-safe chunk column managing a vertical stack of VoxelSections and a 2D heightmap.
 * Uses lock-free atomic references for high-concurrency 3D DDA raycasts.
 */
public final class VoxelChunkColumn {

    private final int chunkX;
    private final int chunkZ;
    private final int minSectionY;
    private final int maxSectionY;
    private final int sectionCount;
    private final AtomicReferenceArray<VoxelSection> sections;
    private final Heightmap2D heightmap;

    /**
     * Constructs a chunk column for the given coordinates and section bounds.
     *
     * @param chunkX      Chunk column X coordinate
     * @param chunkZ      Chunk column Z coordinate
     * @param minSectionY Minimum section Y coordinate
     * @param maxSectionY Maximum section Y coordinate
     */
    public VoxelChunkColumn(int chunkX, int chunkZ, int minSectionY, int maxSectionY) {
        if (maxSectionY <= minSectionY) {
            throw new IllegalArgumentException("maxSectionY (" + maxSectionY + ") must be greater than minSectionY (" + minSectionY + ")");
        }
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minSectionY = minSectionY;
        this.maxSectionY = maxSectionY;
        this.sectionCount = maxSectionY - minSectionY;
        this.sections = new AtomicReferenceArray<>(sectionCount);
        this.heightmap = new Heightmap2D();
    }

    /**
     * Gets the chunk X coordinate.
     *
     * @return Chunk X
     */
    public int getChunkX() {
        return chunkX;
    }

    /**
     * Gets the chunk Z coordinate.
     *
     * @return Chunk Z
     */
    public int getChunkZ() {
        return chunkZ;
    }

    /**
     * Gets the minimum vertical section Y coordinate.
     *
     * @return Minimum section Y
     */
    public int getMinSectionY() {
        return minSectionY;
    }

    /**
     * Gets the maximum vertical section Y coordinate.
     *
     * @return Maximum section Y
     */
    public int getMaxSectionY() {
        return maxSectionY;
    }

    /**
     * Gets the total vertical section capacity.
     *
     * @return Total section count
     */
    public int getSectionCount() {
        return sectionCount;
    }

    /**
     * Gets the 2D heightmap for this chunk column.
     *
     * @return Heightmap2D instance
     */
    public Heightmap2D getHeightmap() {
        return heightmap;
    }

    /**
     * Retrieves the VoxelSection at the given section Y coordinate in a lock-free manner.
     *
     * @param sectionY Vertical section index
     * @return The VoxelSection, or null if unallocated/empty
     */
    public VoxelSection getSection(int sectionY) {
        int index = sectionY - minSectionY;
        if (index >= 0 && index < sectionCount) {
            return sections.get(index);
        }
        return null;
    }

    /**
     * Atomically assigns a VoxelSection at the given section Y coordinate and updates the column heightmap.
     *
     * @param sectionY Vertical section index
     * @param section  VoxelSection to assign
     */
    public void setSection(int sectionY, VoxelSection section) {
        int index = sectionY - minSectionY;
        if (index >= 0 && index < sectionCount) {
            sections.set(index, section);
            if (section != null && !section.isEmpty()) {
                heightmap.updateFromSection(sectionY, section);
            }
        }
    }

    /**
     * Updates a single voxel in this column, allocating the section if necessary and updating the heightmap.
     *
     * @param localX Coordinate in [0..15]
     * @param worldY Absolute block altitude
     * @param localZ Coordinate in [0..15]
     * @param solid  True if the voxel is solid matter
     * @param blockId 16-bit block type identifier
     */
    public void setVoxel(int localX, int worldY, int localZ, boolean solid, short blockId) {
        int sectionY = worldY >> 4;
        int localY = worldY & 15;
        int index = sectionY - minSectionY;

        if (index < 0 || index >= sectionCount) {
            return;
        }

        VoxelSection section = sections.get(index);
        if (section == null) {
            if (!solid) {
                return;
            }
            section = new VoxelSection();
            if (!sections.compareAndSet(index, null, section)) {
                section = sections.get(index);
            }
        }

        section.setVoxel(localX, localY, localZ, solid, blockId);
        onVoxelChanged(localX, worldY, localZ, solid);
    }

    /**
     * Handles heightmap adjustments when a single voxel state changes.
     *
     * @param localX Column local X [0..15]
     * @param worldY Block world Y
     * @param localZ Column local Z [0..15]
     * @param solid  True if solid
     */
    public void onVoxelChanged(int localX, int worldY, int localZ, boolean solid) {
        if (solid) {
            heightmap.updateMax(localX, localZ, (short) worldY);
        } else {
            short oldHeight = heightmap.getHeight(localX, localZ);
            if (worldY >= oldHeight) {
                recomputeHeight(localX, localZ);
            }
        }
    }

    /**
     * Scans downwards from the highest section to recompute the maximum solid altitude for a column.
     *
     * @param localX Column local X [0..15]
     * @param localZ Column local Z [0..15]
     */
    public void recomputeHeight(int localX, int localZ) {
        short foundY = Heightmap2D.VOID_Y;
        for (int i = sectionCount - 1; i >= 0; i--) {
            VoxelSection sec = sections.get(i);
            if (sec != null && !sec.isEmpty()) {
                int sy = i + minSectionY;
                int baseY = sy << 4;
                for (int y = 15; y >= 0; y--) {
                    if (sec.isSolid(localX, y, localZ)) {
                        foundY = (short) (baseY + y);
                        break;
                    }
                }
                if (foundY != Heightmap2D.VOID_Y) {
                    break;
                }
            }
        }
        heightmap.setHeight(localX, localZ, foundY);
    }

    /**
     * Counts how many non-null sections are currently cached in this column.
     *
     * @return Number of allocated VoxelSections
     */
    public int getCachedSectionCount() {
        int count = 0;
        for (int i = 0; i < sectionCount; i++) {
            if (sections.get(i) != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * Checks if this entire column contains zero solid blocks (pure air/void).
     *
     * @return True if all vertical sections are empty or unallocated
     */
    public boolean isEmpty() {
        for (int i = 0; i < sectionCount; i++) {
            VoxelSection sec = sections.get(i);
            if (sec != null && !sec.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
