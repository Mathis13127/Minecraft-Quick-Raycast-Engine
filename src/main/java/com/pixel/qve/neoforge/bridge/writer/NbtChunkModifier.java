package com.pixel.qve.neoforge.bridge.writer;

import com.pixel.qve.mca.writer.FastChunkNbtPatcher;
import com.pixel.qve.mca.writer.FastChunkNbtWriter;
import com.pixel.qve.mca.writer.FastNbtWriter;
import com.pixel.qve.mca.writer.PrimitiveMutationBuffer;
import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.world.VoxelSection;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Ultra-fast zero-garbage NBT chunk modifier preparing offline / unloaded chunks
 * directly for Mojang's native {@link net.minecraft.world.level.chunk.storage.IOWorker}.
 * <p>
 * Combines QVE's high-speed vector bit-packer ({@link FastChunkNbtPatcher} and {@link FastChunkNbtWriter})
 * with Minecraft's native {@link CompoundTag} format without instantiating heavy LevelChunk objects.
 * </p>
 */
public final class NbtChunkModifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(NbtChunkModifier.class);

    private static final ThreadLocal<ByteArrayOutputStream> BAOS_HOLDER =
            ThreadLocal.withInitial(() -> new ByteArrayOutputStream(32768));

    private NbtChunkModifier() {}

    /**
     * Modifies an existing chunk's CompoundTag or constructs a new one from scratch.
     *
     * @param existingTag   Existing chunk CompoundTag loaded from IOWorker, or null if creating ex-nihilo
     * @param chunkX        World chunk X coordinate
     * @param chunkZ        World chunk Z coordinate
     * @param minSectionY   Minimum vertical section Y (e.g. -4 for Overworld)
     * @param maxSectionY   Maximum vertical section Y (e.g. 19 for Overworld)
     * @param registry      BlockIdRegistry for resolving block IDs to state palettes
     * @param wholeSections Whole section mutations map (section Y -> VoxelSection)
     * @param mutations     Sparse mutations / bounding box buffer (or null)
     * @param blockEntities Map of packed coordinate to raw serialized block entity NBT (or null)
     * @return Fully patched, valid CompoundTag ready for {@code IOWorker.store()}
     * @throws IOException If binary serialization fails
     */
    public static CompoundTag modifyChunkTag(CompoundTag existingTag,
                                            int chunkX, int chunkZ,
                                            int minSectionY, int maxSectionY,
                                            BlockIdRegistry registry,
                                            Map<Integer, VoxelSection> wholeSections,
                                            PrimitiveMutationBuffer mutations,
                                            Map<Long, byte[]> blockEntities) throws IOException {
        Objects.requireNonNull(registry, "BlockIdRegistry cannot be null");

        if (existingTag != null) {
            // Path 1: Patch existing chunk NBT non-destructively
            ByteArrayOutputStream baos = BAOS_HOLDER.get();
            baos.reset();
            NbtIo.write(existingTag, new DataOutputStream(baos));

            ByteBuffer inputBuf = ByteBuffer.wrap(baos.toByteArray());
            FastNbtWriter targetWriter = new FastNbtWriter(baos.size() + 8192);

            FastChunkNbtPatcher.patchChunk(
                    inputBuf,
                    chunkX,
                    chunkZ,
                    minSectionY,
                    maxSectionY,
                    registry,
                    wholeSections,
                    mutations,
                    blockEntities,
                    targetWriter
            );

            byte[] patchedBytes = targetWriter.toByteArray();
            return NbtIo.read(new DataInputStream(new ByteArrayInputStream(patchedBytes)));
        } else {
            // Path 2: Construct new chunk NBT ex-nihilo (Status: minecraft:full)
            Map<Integer, VoxelSection> effectiveSections = (wholeSections != null)
                    ? new HashMap<>(wholeSections)
                    : new HashMap<>();

            // Overlay sparse mutations onto effective sections
            if (mutations != null && !mutations.isEmpty()) {
                for (int i = 0, sz = mutations.size(); i < sz; i++) {
                    int bMinSy = Math.max(minSectionY, mutations.minY(i) >> 4);
                    int bMaxSy = Math.min(maxSectionY, mutations.maxY(i) >> 4);
                    for (int sy = bMinSy; sy <= bMaxSy; sy++) {
                        int bMinX = mutations.minX(i);
                        int bMaxX = mutations.maxX(i);
                        int bMinZ = mutations.minZ(i);
                        int bMaxZ = mutations.maxZ(i);
                        int bMinY = Math.max(sy << 4, mutations.minY(i));
                        int bMaxY = Math.min((sy << 4) + 15, mutations.maxY(i));
                        int targetId = mutations.targetBlockId(i);
                        int filterId = mutations.filterBlockId(i);

                        VoxelSection sec = effectiveSections.computeIfAbsent(sy, k -> new VoxelSection());
                        for (int y = bMinY; y <= bMaxY; y++) {
                            int ly = y & 15;
                            for (int z = bMinZ; z <= bMaxZ; z++) {
                                for (int x = bMinX; x <= bMaxX; x++) {
                                    if (filterId < 0 || sec.getBlockId(x, ly, z) == filterId) {
                                        sec.setVoxel(x, ly, z, targetId != BlockIdRegistry.AIR_ID, targetId);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            FastNbtWriter targetWriter = new FastNbtWriter(16384);
            FastChunkNbtWriter.writeChunk(
                    targetWriter,
                    chunkX,
                    chunkZ,
                    minSectionY,
                    maxSectionY,
                    registry,
                    effectiveSections,
                    blockEntities
            );

            byte[] bytes = targetWriter.toByteArray();
            return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)));
        }
    }
}
