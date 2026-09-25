package com.pixel.qve.neoforge.bridge.writer;

import com.pixel.qve.mca.writer.FastNbtWriter;
import com.pixel.qve.mca.writer.IChunkWriteContext;
import com.pixel.qve.world.VoxelSection;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concrete implementation of IChunkWriteContext for assembling and modifying chunk data.
 */
public class ChunkWriteContext implements IChunkWriteContext {

    private final int chunkX;
    private final int chunkZ;
    private final int minSectionY;
    private final int maxSectionY;
    private final boolean isNew;

    private final Map<Integer, VoxelSection> sections = new ConcurrentHashMap<>();
    private final Map<Long, byte[]> blockEntities = new ConcurrentHashMap<>();
    private int modifiedMask = 0;

    public ChunkWriteContext(int chunkX, int chunkZ, int minSectionY, int maxSectionY, boolean isNew) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minSectionY = minSectionY;
        this.maxSectionY = maxSectionY;
        this.isNew = isNew;
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
        int secY = worldY >> 4;
        if (secY < minSectionY || secY > maxSectionY) {
            return;
        }

        VoxelSection section = sections.computeIfAbsent(secY, k -> new VoxelSection());
        int localY = worldY & 15;
        section.setVoxel(localX & 15, localY, localZ & 15, blockId != 0, blockId);
        modifiedMask |= (1 << (secY - minSectionY));
    }

    @Override
    public int getBlock(int localX, int worldY, int localZ) {
        int secY = worldY >> 4;
        VoxelSection sec = sections.get(secY);
        if (sec == null) return 0;
        return sec.getBlockId(localX & 15, worldY & 15, localZ & 15);
    }

    @Override
    public VoxelSection getSection(int sectionY) {
        return sections.get(sectionY);
    }

    @Override
    public void setSection(int sectionY, VoxelSection section) {
        if (sectionY < minSectionY || sectionY > maxSectionY) {
            return;
        }
        if (section != null) {
            sections.put(sectionY, section);
        } else {
            sections.remove(sectionY);
        }
        modifiedMask |= (1 << (sectionY - minSectionY));
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
                writeArbitraryData(writer, entry.getKey(), entry.getValue());
            }
        }
        writer.endCompound();

        setBlockEntityRaw(localX, worldY, localZ, writer.toByteArray());
    }

    private void writeArbitraryData(FastNbtWriter writer, String key, Object val) {
        if (val instanceof Byte b) writer.putByte(key, b);
        else if (val instanceof Short s) writer.putShort(key, s);
        else if (val instanceof Integer i) writer.putInt(key, i);
        else if (val instanceof Long l) writer.putLong(key, l);
        else if (val instanceof Float f) writer.putFloat(key, f);
        else if (val instanceof Double d) writer.putDouble(key, d);
        else if (val instanceof String s) writer.putString(key, s);
        else if (val instanceof byte[] b) writer.putByteArray(key, b);
        else if (val instanceof int[] i) writer.putIntArray(key, i);
        else if (val instanceof long[] l) writer.putLongArray(key, l);
    }

    @Override
    public void setBlockEntityRaw(int localX, int worldY, int localZ, byte[] rawNbt) {
        long key = packKey(localX, worldY, localZ);
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
        return modifiedMask;
    }

    @Override
    public boolean isNewChunk() {
        return isNew;
    }

    public Map<Integer, VoxelSection> getSections() {
        return sections;
    }

    private static long packKey(int x, int y, int z) {
        return (((long) (y & 0xFFFF)) << 8) | ((z & 0xF) << 4) | (x & 0xF);
    }
}
