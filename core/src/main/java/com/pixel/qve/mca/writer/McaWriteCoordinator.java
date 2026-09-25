package com.pixel.qve.mca.writer;

import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.world.VoxelSection;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordinates multi-threaded asynchronous writes across Anvil (.mca) region files.
 * Uses striped per-region locks so that writes to different region files proceed in full parallel,
 * while writes to the same region file are serialized to prevent header and sector corruption.
 */
public final class McaWriteCoordinator implements Closeable {

    private static final System.Logger LOGGER = System.getLogger(McaWriteCoordinator.class.getName());

    private final Path regionDirectory;
    private final BlockIdRegistry registry;
    private final int minSectionY;
    private final int maxSectionY;

    private final Map<Long, ReentrantLock> regionLocks = new ConcurrentHashMap<>();
    private final Map<Long, McaRegionWriter> openWriters = new ConcurrentHashMap<>();
    private final List<IChunkWriteListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Constructs an McaWriteCoordinator for a region directory.
     *
     * @param regionDirectory Root directory containing .mca region files
     * @param registry        BlockIdRegistry for state resolution
     * @param minSectionY     Minimum vertical section Y
     * @param maxSectionY     Maximum vertical section Y
     */
    public McaWriteCoordinator(Path regionDirectory, BlockIdRegistry registry, int minSectionY, int maxSectionY) {
        this.regionDirectory = Objects.requireNonNull(regionDirectory, "regionDirectory cannot be null");
        this.registry = Objects.requireNonNull(registry, "BlockIdRegistry cannot be null");
        this.minSectionY = minSectionY;
        this.maxSectionY = maxSectionY;
    }

    /**
     * Gets the root directory containing Anvil region files.
     *
     * @return Path to region directory
     */
    public Path getRegionDirectory() {
        return regionDirectory;
    }

    /**
     * Checks if the Anvil region file for the given chunk coordinates exists on disk.
     *
     * @param chunkX World chunk X
     * @param chunkZ World chunk Z
     * @return True if r.X.Z.mca exists
     */
    public boolean hasRegionFile(int chunkX, int chunkZ) {
        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        Path mcaFile = regionDirectory.resolve("r." + rx + "." + rz + ".mca");
        return java.nio.file.Files.isRegularFile(mcaFile);
    }

    /**
     * Checks if the given chunk exists on disk (has allocated sectors in the region header).
     *
     * @param chunkX World chunk X
     * @param chunkZ World chunk Z
     * @return True if chunk exists in the region file
     */
    public boolean hasChunk(int chunkX, int chunkZ) {
        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        int localX = chunkX & 31;
        int localZ = chunkZ & 31;
        long rKey = regionKey(rx, rz);

        McaRegionWriter existing = openWriters.get(rKey);
        if (existing != null) {
            return existing.hasChunk(localX, localZ);
        }

        Path mcaFile = regionDirectory.resolve("r." + rx + "." + rz + ".mca");
        if (!java.nio.file.Files.isRegularFile(mcaFile)) {
            return false;
        }
        try (com.pixel.qve.mca.McaRegionReader reader = new com.pixel.qve.mca.McaRegionReader(mcaFile, registry)) {
            return reader.hasChunk(localX, localZ);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Registers a lifecycle listener for chunk write operations.
     *
     * @param listener Listener instance
     */
    public void addListener(IChunkWriteListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Payload descriptor representing a chunk ready for serializing and writing to disk.
     */
    public record ChunkWriteTask(
            int chunkX,
            int chunkZ,
            Map<Integer, VoxelSection> sections,
            Map<Long, byte[]> blockEntities
    ) {}

    /**
     * Submits an asynchronous batch of chunk writes for a single region.
     * All chunks are written sequentially under a single region lock, and the 8KB header is synced once at the end.
     *
     * @param rx     Region X
     * @param rz     Region Z
     * @param chunks List of chunk write tasks belonging to this region
     * @return CompletableFuture completing with list of WriteMetrics for each chunk
     */
    public CompletableFuture<List<McaRegionWriter.WriteMetrics>> writeRegionBatchAsync(int rx, int rz, List<ChunkWriteTask> chunks) {
        return VoxelDiskWriterThreadPool.submit(() -> writeRegionBatchSync(rx, rz, chunks));
    }

    /**
     * Synchronously writes a batch of chunks for a single region, syncing the 8KB header once at completion.
     *
     * @param rx     Region X
     * @param rz     Region Z
     * @param chunks List of chunk write tasks belonging to this region
     * @return List of WriteMetrics for each chunk in the order submitted
     * @throws IOException If write fails
     */
    public List<McaRegionWriter.WriteMetrics> writeRegionBatchSync(int rx, int rz, List<ChunkWriteTask> chunks) throws IOException {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        long rKey = regionKey(rx, rz);
        ReentrantLock lock = regionLocks.computeIfAbsent(rKey, k -> new ReentrantLock());
        lock.lock();
        try {
            McaRegionWriter writer = getOrOpenWriter(rx, rz);
            List<McaRegionWriter.WriteMetrics> metricsList = new ArrayList<>(chunks.size());

            for (int i = 0; i < chunks.size(); i++) {
                ChunkWriteTask task = chunks.get(i);
                long startTime = System.nanoTime();
                int localX = task.chunkX() & 31;
                int localZ = task.chunkZ() & 31;

                // 1. Serialize Chunk NBT
                FastNbtWriter nbtWriter = new FastNbtWriter(128 * 1024);
                FastChunkNbtWriter.writeChunk(nbtWriter, task.chunkX(), task.chunkZ(), minSectionY, maxSectionY, registry, task.sections(), task.blockEntities());
                byte[] uncompressed = nbtWriter.toByteArray();

                // 2. Write payload, syncing header only on the last chunk
                boolean isLast = (i == chunks.size() - 1);
                McaRegionWriter.WriteMetrics metrics = writer.writeChunk(localX, localZ, uncompressed, isLast);
                metricsList.add(metrics);

                // 3. Notify listeners
                long duration = System.nanoTime() - startTime;
                for (IChunkWriteListener listener : listeners) {
                    try {
                        listener.onChunkWritten(task.chunkX(), task.chunkZ(), duration, metrics);
                    } catch (Throwable t) {
                        LOGGER.log(System.Logger.Level.WARNING, "Error in chunk write listener: {0}", t.getMessage());
                    }
                }
            }

            return metricsList;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Submits an asynchronous chunk write operation.
     *
     * @param chunkX        World chunk X
     * @param chunkZ        World chunk Z
     * @param sections      Map of section Y to VoxelSection
     * @param blockEntities Map of packed coordinate to raw BlockEntity NBT compounds
     * @return CompletableFuture completing with WriteMetrics
     */
    public CompletableFuture<McaRegionWriter.WriteMetrics> writeChunkAsync(int chunkX, int chunkZ,
                                                                          Map<Integer, VoxelSection> sections,
                                                                          Map<Long, byte[]> blockEntities) {
        return VoxelDiskWriterThreadPool.submit(() -> writeChunkSync(chunkX, chunkZ, sections, blockEntities));
    }

    /**
     * Synchronously writes a chunk directly to its Anvil region file, protected by striped region locks.
     *
     * @param chunkX        World chunk X
     * @param chunkZ        World chunk Z
     * @param sections      Map of section Y to VoxelSection
     * @param blockEntities Map of packed coordinate to raw BlockEntity NBT compounds
     * @return WriteMetrics
     * @throws IOException If disk write fails
     */
    public McaRegionWriter.WriteMetrics writeChunkSync(int chunkX, int chunkZ,
                                                       Map<Integer, VoxelSection> sections,
                                                       Map<Long, byte[]> blockEntities) throws IOException {
        long startTime = System.nanoTime();

        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        int localX = chunkX & 31;
        int localZ = chunkZ & 31;
        long rKey = regionKey(rx, rz);

        ReentrantLock lock = regionLocks.computeIfAbsent(rKey, k -> new ReentrantLock());
        lock.lock();
        try {
            McaRegionWriter writer = getOrOpenWriter(rx, rz);

            // 1. Serialize Chunk NBT
            FastNbtWriter nbtWriter = new FastNbtWriter(128 * 1024);
            FastChunkNbtWriter.writeChunk(nbtWriter, chunkX, chunkZ, minSectionY, maxSectionY, registry, sections, blockEntities);
            byte[] uncompressed = nbtWriter.toByteArray();

            // 2. Compress and write payload to region file
            McaRegionWriter.WriteMetrics metrics = writer.writeChunk(localX, localZ, uncompressed);

            // 3. Notify listeners
            long duration = System.nanoTime() - startTime;
            for (IChunkWriteListener listener : listeners) {
                try {
                    listener.onChunkWritten(chunkX, chunkZ, duration, metrics);
                } catch (Throwable t) {
                    LOGGER.log(System.Logger.Level.WARNING, "Error in chunk write listener: {0}", t.getMessage());
                }
            }

            return metrics;
        } finally {
            lock.unlock();
        }
    }

    private McaRegionWriter getOrOpenWriter(int rx, int rz) throws IOException {
        long rKey = regionKey(rx, rz);
        McaRegionWriter existing = openWriters.get(rKey);
        if (existing != null) {
            return existing;
        }

        Path mcaFile = regionDirectory.resolve("r." + rx + "." + rz + ".mca");
        McaRegionWriter writer = new McaRegionWriter(mcaFile);
        openWriters.put(rKey, writer);
        return writer;
    }

    private static long regionKey(int rx, int rz) {
        return (((long) rx) << 32) | (rz & 0xFFFFFFFFL);
    }

    @Override
    public synchronized void close() {
        for (McaRegionWriter writer : openWriters.values()) {
            try {
                writer.close();
            } catch (IOException e) {
                LOGGER.log(System.Logger.Level.WARNING, "Error closing McaRegionWriter for {0}: {1}",
                        writer.getFilePath(), e.getMessage());
            }
        }
        openWriters.clear();
        regionLocks.clear();
    }
}
