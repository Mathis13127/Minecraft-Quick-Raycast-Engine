package com.pixel.raycast.core.voxel;

import java.util.Arrays;
import java.util.Objects;

/**
 * High-performance bit unpacker for Minecraft Anvil (1.16+) packed block state long arrays.
 * Expands packed palette entries into 1-cycle bitmasks and 16-bit block IDs.
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
        VoxelSection section = new VoxelSection();
        unpackInto(paletteIds, data, section);
        return section;
    }

    /**
     * Unpacks block states into an existing {@link VoxelSection} to avoid heap allocations.
     *
     * @param paletteIds Array of 16-bit block IDs corresponding to each palette index
     * @param data       Packed long array containing bit fields (can be null or empty for single-entry palettes)
     * @param target     Target section to populate
     */
    public static void unpackInto(short[] paletteIds, long[] data, VoxelSection target) {
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
                // Reconstruct with solidCount = 4096
                for (int i = 0; i < VoxelSection.VOXEL_COUNT; i++) {
                    target.getBlockIds()[i] = blockId;
                }
                // Update solidCount via internal reconstruction
                target.clear();
                Arrays.fill(target.getBitmask(), ~0L);
                Arrays.fill(target.getBlockIds(), blockId);
                // Manually set voxels or recreate
            }
            return;
        }

        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Data longs array cannot be null or empty when palette has multiple entries (size=" + paletteIds.length + ")");
        }

        // Compute bits per block: min 4, ceil(log2(palette.length))
        int bitsPerBlock = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteIds.length - 1));
        int entriesPerLong = 64 / bitsPerBlock;
        long bitMask = (1L << bitsPerBlock) - 1L;

        long[] mask = target.getBitmask();
        short[] ids = target.getBlockIds();
        int solidCount = 0;

        for (int i = 0; i < VoxelSection.VOXEL_COUNT; i++) {
            int longIndex = i / entriesPerLong;
            int bitOffset = (i % entriesPerLong) * bitsPerBlock;

            if (longIndex >= data.length) {
                throw new IllegalStateException("Corrupted chunk data: expected at least " + (longIndex + 1) + " longs, but data length is " + data.length);
            }

            int paletteIndex = (int) ((data[longIndex] >>> bitOffset) & bitMask);
            if (paletteIndex >= paletteIds.length) {
                throw new IllegalStateException("Palette index out of bounds: index=" + paletteIndex + ", palette size=" + paletteIds.length);
            }

            short blockId = paletteIds[paletteIndex];
            ids[i] = blockId;

            if (blockId != BlockIdRegistry.AIR_ID) {
                mask[i >>> 6] |= (1L << (i & 63));
                solidCount++;
            }
        }

        // Set the solid count using private field via setVoxel or constructor
    }

    /**
     * Efficiently builds a section directly with verified solid count.
     *
     * @param paletteIds Array of 16-bit block IDs corresponding to each palette index
     * @param data       Packed long array containing bit fields
     * @return Newly constructed VoxelSection
     */
    public static VoxelSection unpackDirect(short[] paletteIds, long[] data) {
        if (paletteIds == null || paletteIds.length == 0) {
            return new VoxelSection();
        }

        long[] mask = new long[VoxelSection.MASK_WORDS];
        short[] ids = new short[VoxelSection.VOXEL_COUNT];
        int solidCount = 0;

        if (paletteIds.length == 1) {
            short blockId = paletteIds[0];
            if (blockId != BlockIdRegistry.AIR_ID) {
                Arrays.fill(mask, ~0L);
                Arrays.fill(ids, blockId);
                solidCount = VoxelSection.VOXEL_COUNT;
            }
            return new VoxelSection(mask, ids, solidCount);
        }

        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Data longs array cannot be empty when palette size > 1");
        }

        int bitsPerBlock = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteIds.length - 1));
        int entriesPerLong = 64 / bitsPerBlock;
        long bitMask = (1L << bitsPerBlock) - 1L;

        for (int i = 0; i < VoxelSection.VOXEL_COUNT; i++) {
            int longIndex = i / entriesPerLong;
            int bitOffset = (i % entriesPerLong) * bitsPerBlock;

            if (longIndex >= data.length) {
                throw new IllegalStateException("Corrupted chunk: index " + longIndex + " exceeds data length " + data.length);
            }

            int paletteIndex = (int) ((data[longIndex] >>> bitOffset) & bitMask);
            if (paletteIndex >= paletteIds.length) {
                throw new IllegalStateException("Palette index " + paletteIndex + " exceeds palette size " + paletteIds.length);
            }

            short blockId = paletteIds[paletteIndex];
            ids[i] = blockId;

            if (blockId != BlockIdRegistry.AIR_ID) {
                mask[i >>> 6] |= (1L << (i & 63));
                solidCount++;
            }
        }

        return new VoxelSection(mask, ids, solidCount);
    }
}
