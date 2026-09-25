package com.pixel.qve.mca.writer;

import com.pixel.qve.world.VoxelSection;

import java.util.Map;

/**
 * Context interface for editing or populating chunk data during a direct MCA write operation.
 * Supports sparse block setting, whole-section replacement, and custom BlockEntity NBT injection.
 */
public interface IChunkWriteContext {

    /**
     * Gets the chunk X coordinate.
     *
     * @return World chunk X
     */
    int getChunkX();

    /**
     * Gets the chunk Z coordinate.
     *
     * @return World chunk Z
     */
    int getChunkZ();

    /**
     * Gets the region X coordinate (chunkX &gt;&gt; 5).
     *
     * @return Region X
     */
    default int getRegionX() {
        return getChunkX() >> 5;
    }

    /**
     * Gets the region Z coordinate (chunkZ &gt;&gt; 5).
     *
     * @return Region Z
     */
    default int getRegionZ() {
        return getChunkZ() >> 5;
    }

    /**
     * Minimum vertical section index (e.g. -4 for 1.21.1 Overworld, 0 for Nether/End).
     *
     * @return Min section Y
     */
    int getMinSectionY();

    /**
     * Maximum vertical section index (e.g. 19 for 1.21.1 Overworld, 15 for Nether/End).
     *
     * @return Max section Y
     */
    int getMaxSectionY();

    /**
     * Sets a block at local chunk coordinates (0..15, worldY, 0..15).
     *
     * @param localX  Local X in chunk [0..15]
     * @param worldY  World build height Y
     * @param localZ  Local Z in chunk [0..15]
     * @param blockId 32-bit compact block ID
     */
    void setBlock(int localX, int worldY, int localZ, int blockId);

    /**
     * Retrieves the block ID at local chunk coordinates.
     *
     * @param localX Local X in chunk [0..15]
     * @param worldY World build height Y
     * @param localZ Local Z in chunk [0..15]
     * @return 32-bit compact block ID
     */
    int getBlock(int localX, int worldY, int localZ);

    /**
     * Retrieves the VoxelSection for the given section Y.
     *
     * @param sectionY Section Y index
     * @return VoxelSection, or null if unpopulated
     */
    VoxelSection getSection(int sectionY);

    /**
     * Replaces or sets a whole VoxelSection at the given section index.
     *
     * @param sectionY Vertical section index
     * @param section  The VoxelSection (16x16x16)
     */
    void setSection(int sectionY, VoxelSection section);

    /**
     * Injects or updates a BlockEntity at local chunk coordinates using parsed key-value data.
     *
     * @param localX        Local X in chunk [0..15]
     * @param worldY        World build height Y
     * @param localZ        Local Z in chunk [0..15]
     * @param blockEntityId Block entity type ID (e.g. "minecraft:chest")
     * @param data          Map of NBT properties
     */
    void setBlockEntity(int localX, int worldY, int localZ, String blockEntityId, Map<String, Object> data);

    /**
     * Injects or updates a BlockEntity at local chunk coordinates using raw pre-serialized NBT TAG_Compound bytes.
     *
     * @param localX Local X in chunk [0..15]
     * @param worldY World build height Y
     * @param localZ Local Z in chunk [0..15]
     * @param rawNbt Raw NBT TAG_Compound bytes
     */
    void setBlockEntityRaw(int localX, int worldY, int localZ, byte[] rawNbt);

    /**
     * Retrieves all injected BlockEntities mapped by packed local coordinate key.
     *
     * @return Map of packed coordinate ((y &amp; 0xFFFFL) &lt;&lt; 8 | (z &amp; 0xF) &lt;&lt; 4 | (x &amp; 0xF)) to raw NBT bytes
     */
    Map<Long, byte[]> getBlockEntities();

    /**
     * Bitmask of vertical sections modified during this context session.
     * Bit (sectionY - minSectionY) is set to 1 if modified.
     *
     * @return Modified section bitmask
     */
    int getModifiedSectionMask();

    /**
     * Indicates whether this chunk is brand new (created ex-nihilo) or modifying an existing chunk.
     *
     * @return True if chunk was created fresh
     */
    boolean isNewChunk();
}
