package com.pixel.raycast.core.voxel;

import com.pixel.raycast.core.shape.ShapeRegistry;

import java.util.Arrays;
import java.util.Objects;

/**
 * Ultra-high-performance bit unpacker for Minecraft Anvil (1.16+) packed block state long arrays.
 * Uses divisionless bitshift unrolling to expand packed palette entries into 1-cycle bitmasks in CPU registers.
 */
public final class BlockStatePaletteUnpacker {

    private BlockStatePaletteUnpacker() {}

    /**
     * Unpacks block states from palette IDs and optional data longs into a newly allocated {@link VoxelSection}.
     *
     * @param paletteIds Array of 16-bit block IDs corresponding to each palette index
     * @param data       Packed long array containing bit fields (can be null or empty for single-entry palettes)
     * @return Fully populated VoxelSection
     */
    public static VoxelSection unpack(short[] paletteIds, long[] data) {
        return unpackDirect(paletteIds, data, null);
    }

    /**
     * Unpacks block states into an existing {@link VoxelSection} to avoid heap allocations.
     *
     * @param paletteIds Array of 16-bit block IDs corresponding to each palette index
     * @param data       Packed long array containing bit fields (can be null or empty for single-entry palettes)
     * @param target     Target section to populate
     */
    public static void unpackInto(short[] paletteIds, long[] data, VoxelSection target) {
        unpackInto(paletteIds, data, target, null);
    }

    /**
     * Unpacks block states into an existing {@link VoxelSection} with optional full-cube validation.
     *
     * @param paletteIds    Array of 16-bit block IDs corresponding to each palette index
     * @param data          Packed long array containing bit fields
     * @param target        Target section to populate
     * @param shapeRegistry Optional ShapeRegistry for full-cube evaluation
     */
    public static void unpackInto(short[] paletteIds, long[] data, VoxelSection target, ShapeRegistry shapeRegistry) {
        Objects.requireNonNull(target, "Target VoxelSection cannot be null");
        target.clear();

        if (paletteIds == null || paletteIds.length == 0) {
            return;
        }

        // Single palette entry: section is completely homogeneous
        if (paletteIds.length == 1) {
            short blockId = paletteIds[0];
            if (blockId != BlockIdRegistry.AIR_ID) {
                Arrays.fill(target.getBitmask(), ~0L);
                Arrays.fill(target.getBlockIds(), blockId);
                target.recalculateSolidCount();
                boolean fullCube = (shapeRegistry == null) || shapeRegistry.getShape(blockId).isFullCube();
                target.setAllSolidAreFullCubes(fullCube);
            }
            return;
        }

        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Data longs array cannot be null or empty when palette has multiple entries (size=" + paletteIds.length + ")");
        }

        int bitsPerBlock = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteIds.length - 1));
        int entriesPerLong = 64 / bitsPerBlock;
        long bitMask = (1L << bitsPerBlock) - 1L;

        long[] mask = target.getBitmask();
        short[] ids = target.getBlockIds();
        int solidCount = 0;

        int voxelIdx = 0;
        for (int l = 0; l < data.length && voxelIdx < VoxelSection.VOXEL_COUNT; l++) {
            long word = data[l];
            int countInWord = Math.min(entriesPerLong, VoxelSection.VOXEL_COUNT - voxelIdx);
            for (int e = 0; e < countInWord; e++) {
                int paletteIndex = (int) (word & bitMask);
                word >>>= bitsPerBlock;

                if (paletteIndex >= paletteIds.length) {
                    throw new IllegalStateException("Palette index out of bounds: index=" + paletteIndex + ", palette size=" + paletteIds.length);
                }

                short blockId = paletteIds[paletteIndex];
                ids[voxelIdx] = blockId;

                if (blockId != BlockIdRegistry.AIR_ID) {
                    mask[voxelIdx >>> 6] |= (1L << (voxelIdx & 63));
                    solidCount++;
                }
                voxelIdx++;
            }
        }

        if (voxelIdx < VoxelSection.VOXEL_COUNT) {
            throw new IllegalStateException("Corrupted chunk data: expected " + VoxelSection.VOXEL_COUNT + " voxels, but only unpacked " + voxelIdx);
        }

        target.recalculateSolidCount();
        target.setAllSolidAreFullCubes(checkAllFullCubes(paletteIds, shapeRegistry));
    }

    /**
     * Efficiently builds a section directly with verified solid count using divisionless unpacking.
     *
     * @param paletteIds Array of 16-bit block IDs corresponding to each palette index
     * @param data       Packed long array containing bit fields
     * @return Newly constructed VoxelSection
     */
    public static VoxelSection unpackDirect(short[] paletteIds, long[] data) {
        return unpackDirect(paletteIds, data, null);
    }

    /**
     * Efficiently builds a section directly with full-cube fast-path analysis and compact homogeneous allocation.
     *
     * @param paletteIds    Array of 16-bit block IDs corresponding to each palette index
     * @param data          Packed long array containing bit fields
     * @param shapeRegistry Optional ShapeRegistry for full-cube classification
     * @return Newly constructed VoxelSection
     */
    public static VoxelSection unpackDirect(short[] paletteIds, long[] data, ShapeRegistry shapeRegistry) {
        if (paletteIds == null || paletteIds.length == 0) {
            return VoxelSection.EMPTY;
        }

        if (paletteIds.length == 1) {
            short blockId = paletteIds[0];
            if (blockId == BlockIdRegistry.AIR_ID) {
                return VoxelSection.EMPTY;
            }
            boolean fullCube = (shapeRegistry != null) && shapeRegistry.getShape(blockId).isFullCube();
            return VoxelSection.createHomogeneous(blockId, fullCube);
        }

        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Data longs array cannot be empty when palette size > 1");
        }

        long[] mask = new long[VoxelSection.MASK_WORDS];
        short[] ids = new short[VoxelSection.VOXEL_COUNT];
        int solidCount = 0;

        int bitsPerBlock = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteIds.length - 1));
        int entriesPerLong = 64 / bitsPerBlock;
        long bitMask = (1L << bitsPerBlock) - 1L;

        int voxelIdx = 0;
        for (int l = 0; l < data.length && voxelIdx < VoxelSection.VOXEL_COUNT; l++) {
            long word = data[l];
            int countInWord = Math.min(entriesPerLong, VoxelSection.VOXEL_COUNT - voxelIdx);
            for (int e = 0; e < countInWord; e++) {
                int paletteIndex = (int) (word & bitMask);
                word >>>= bitsPerBlock;

                if (paletteIndex >= paletteIds.length) {
                    throw new IllegalStateException("Palette index " + paletteIndex + " exceeds palette size " + paletteIds.length);
                }

                short blockId = paletteIds[paletteIndex];
                ids[voxelIdx] = blockId;

                if (blockId != BlockIdRegistry.AIR_ID) {
                    mask[voxelIdx >>> 6] |= (1L << (voxelIdx & 63));
                    solidCount++;
                }
                voxelIdx++;
            }
        }

        if (voxelIdx < VoxelSection.VOXEL_COUNT) {
            throw new IllegalStateException("Corrupted chunk data: expected " + VoxelSection.VOXEL_COUNT + " voxels, but only unpacked " + voxelIdx);
        }

        boolean fullCubes = checkAllFullCubes(paletteIds, shapeRegistry);
        return new VoxelSection(mask, ids, solidCount, fullCubes);
    }

    private static boolean checkAllFullCubes(short[] paletteIds, ShapeRegistry shapeRegistry) {
        if (shapeRegistry == null) {
            return false;
        }
        for (short pid : paletteIds) {
            if (pid != BlockIdRegistry.AIR_ID && !shapeRegistry.getShape(pid).isFullCube()) {
                return false;
            }
        }
        return true;
    }
}
