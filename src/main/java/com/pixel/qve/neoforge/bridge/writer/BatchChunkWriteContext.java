package com.pixel.qve.neoforge.bridge.writer;

import com.pixel.qve.mca.writer.FastNbtWriter;
import com.pixel.qve.mca.writer.IChunkWriteContext;
import com.pixel.qve.neoforge.api.ChunkWriteBatch;
import com.pixel.qve.world.VoxelSection;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Lightweight, zero-decompression {@link IChunkWriteContext} adapter for batch chunk writing.
 * Directly references the queued {@link ChunkWriteBatch.ChunkEdits} without reading,
 * decompressing, or allocating section arrays from disk ahead of time.
 */
public class BatchChunkWriteContext implements IChunkWriteContext {

    private final int chunkX;
    private final int chunkZ;
    private final int minSectionY;
    private final int maxSectionY;
    private final ChunkWriteBatch.ChunkEdits edits;
    private final Map<Long, byte[]> blockEntities;

    public BatchChunkWriteContext(int chunkX, int chunkZ, int minSectionY, int maxSectionY,
                                  ChunkWriteBatch.ChunkEdits edits, Map<Long, byte[]> blockEntities) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minSectionY = minSectionY;
        this.maxSectionY = maxSectionY;
        this.edits = Objects.requireNonNull(edits, "edits cannot be null");
        this.blockEntities = (blockEntities != null) ? blockEntities : new HashMap<>();
    }

    @Override
    public int getChunkX() {
        return chunkX;
    }

    @Override
    public int getChunkZ() {
        return chunkZ;
    }

    @Override
    public int getMinSectionY() {
        return minSectionY;
    }

    @Override
    public int getMaxSectionY() {
        return maxSectionY;
    }

    @Override
    public void setBlock(int localX, int worldY, int localZ, int blockId) {
        edits.addMutation(localX & 15, worldY, localZ & 15, blockId, -1, null);
    }

    @Override
    public int getBlock(int localX, int worldY, int localZ) {
        int secY = worldY >> 4;
        VoxelSection sec = edits.getWholeSections().get(secY);
        if (sec != null) {
            return sec.getBlockId(localX & 15, worldY & 15, localZ & 15);
        }
        return 0;
    }

    @Override
    public VoxelSection getSection(int sectionY) {
        return edits.getWholeSections().get(sectionY);
    }

    @Override
    public void setSection(int sectionY, VoxelSection section) {
        if (sectionY < minSectionY || sectionY > maxSectionY) {
            throw new IllegalArgumentException(String.format(
                    "Section Y %d is outside chunk section bounds [%d..%d]",
                    sectionY, minSectionY, maxSectionY));
        }
        edits.setSection(sectionY, section);
    }

    @Override
    public void setBlockEntity(int localX, int worldY, int localZ, String blockEntityId, Map<String, Object> data) {
        FastNbtWriter writer = new FastNbtWriter(1024);
        writer.beginListCompound();
        writer.putString("id", blockEntityId != null ? blockEntityId : "");
        writer.putInt("x", (chunkX << 4) | (localX & 15));
        writer.putInt("y", worldY);
        writer.putInt("z", (chunkZ << 4) | (localZ & 15));
        writer.putByte("keepPacked", (byte) 0);

        if (data != null) {
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                ChunkWriteContext.writeArbitraryData(writer, entry.getKey(), entry.getValue());
            }
        }
        writer.endCompound();

        setBlockEntityRaw(localX, worldY, localZ, writer.toByteArray());
    }

    @Override
    public void setBlockEntityRaw(int localX, int worldY, int localZ, byte[] rawNbt) {
        long key = (((long) (worldY & 0xFFFF)) << 8) | (((long) (localZ & 0xF)) << 4) | ((long) (localX & 0xF));
        if (rawNbt != null) {
            blockEntities.put(key, rawNbt);
        } else {
            blockEntities.remove(key);
        }
    }

    @Override
    public Map<Long, byte[]> getBlockEntities() {
        return blockEntities;
    }

    @Override
    public int getModifiedSectionMask() {
        int mask = 0;
        for (int secY : edits.getWholeSections().keySet()) {
            if (secY >= minSectionY && secY <= maxSectionY) {
                mask |= (1 << (secY - minSectionY));
            }
        }
        if (edits.getMutationBuffer() != null) {
            mask |= edits.getMutationBuffer().getModifiedSectionMask(minSectionY, maxSectionY);
        } else {
            for (ChunkWriteBatch.BlockMutation m : edits.getMutations()) {
                int secY = m.worldY() >> 4;
                if (secY >= minSectionY && secY <= maxSectionY) {
                    mask |= (1 << (secY - minSectionY));
                }
            }
        }
        return mask;
    }

    @Override
    public boolean isNewChunk() {
        return false;
    }
}
