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
    private volatile boolean synchronousVerification = false;
    private static final ThreadLocal<FastNbtWriter> NBT_WRITER_CACHE = ThreadLocal.withInitial(() -> new FastNbtWriter(128 * 1024));

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
     * Sets whether chunk verification runs synchronously under writeRegionBatchSync
     * or is deferred asynchronously to AsyncChunkIntegrityVerifier.
     *
     * @param synchronousVerification True to verify synchronously
     */
    public void setSynchronousVerification(boolean synchronousVerification) {
        this.synchronousVerification = synchronousVerification;
    }

    /**
     * Checks if synchronous verification is enabled.
     *
     * @return True if synchronous
     */
    public boolean isSynchronousVerification() {
        return synchronousVerification;
    }

    /**
     * Optional voxel verification target to check during pre-commit verification.
     */
    public record VoxelCheck(int localX, int worldY, int localZ, int expectedBlockId) {}

    /**
     * Payload descriptor representing a chunk ready for serializing and writing to disk.
     */
    public record ChunkWriteTask(
            int chunkX,
            int chunkZ,
            Map<Integer, VoxelSection> sections,
            Map<Long, byte[]> blockEntities,
            VoxelCheck voxelCheck
    ) {
        public ChunkWriteTask(int chunkX, int chunkZ, Map<Integer, VoxelSection> sections, Map<Long, byte[]> blockEntities) {
            this(chunkX, chunkZ, sections, blockEntities, null);
        }
    }

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
            SectorAllocator.Snapshot snapshot = writer.snapshotAllocator();
            Map<Integer, byte[]> rollbackPayloads = new HashMap<>();
            for (ChunkWriteTask task : chunks) {
                int localX = task.chunkX() & 31;
                int localZ = task.chunkZ() & 31;
                int localIndex = localX + localZ * 32;
                rollbackPayloads.putIfAbsent(localIndex, writer.readChunkRaw(localIndex));
            }

            List<McaRegionWriter.WriteMetrics> metricsList = new ArrayList<>(chunks.size());

            try {
                for (int i = 0; i < chunks.size(); i++) {
                    ChunkWriteTask task = chunks.get(i);
                    long startTime = System.nanoTime();
                    int localX = task.chunkX() & 31;
                    int localZ = task.chunkZ() & 31;

                    // 1. Serialize Chunk NBT: patch existing chunk non-destructively, or create ex-nihilo if new
                    FastNbtWriter nbtWriter = NBT_WRITER_CACHE.get();
                    nbtWriter.reset();
                    boolean patched = false;
                    if (writer.hasChunk(localX, localZ)) {
                        java.nio.ByteBuffer existingPayload = writer.readChunkPayload(localX, localZ);
                        if (existingPayload != null) {
                            try {
                                FastChunkNbtPatcher.patchChunk(
                                        existingPayload,
                                        task.chunkX(), task.chunkZ(),
                                        minSectionY, maxSectionY,
                                        registry,
                                        task.sections(),
                                        task.blockEntities(),
                                        nbtWriter
                                );
                                patched = true;
                            } catch (Exception e) {
                                LOGGER.log(System.Logger.Level.WARNING,
                                        "Failed to patch existing chunk NBT ({0}, {1}) in region r.{2}.{3}.mca, falling back to full chunk writer: {4}",
                                        task.chunkX(), task.chunkZ(), rx, rz, e.getMessage());
                                nbtWriter.reset();
                            }
                        }
                    }
                    if (!patched) {
                        FastChunkNbtWriter.writeChunk(nbtWriter, task.chunkX(), task.chunkZ(), minSectionY, maxSectionY, registry, task.sections(), task.blockEntities());
                    }

                    // 2. Write payload to sector, deferring header sync
                    McaRegionWriter.WriteMetrics metrics = writer.writeChunk(localX, localZ, nbtWriter.toReadBuffer(), false);
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

                // 4. Commit: sync 8KB header and flush
                writer.syncHeaderOnly();
                writer.flush(false);

                // 5. Verification: synchronous or deferred to AsyncChunkIntegrityVerifier
                if (synchronousVerification) {
                    for (int i = 0; i < chunks.size(); i++) {
                        ChunkWriteTask task = chunks.get(i);
                        int localX = task.chunkX() & 31;
                        int localZ = task.chunkZ() & 31;
                        java.nio.ByteBuffer verifyPayload = writer.readChunkPayload(localX, localZ);
                        if (verifyPayload == null) {
                            throw new IOException("Pre-commit verification failed: chunk (" + task.chunkX() + ", " + task.chunkZ() + ") cannot be read back");
                        }
                        if (!FastChunkVerifier.verifyChunkCoordinates(verifyPayload, task.chunkX(), task.chunkZ())) {
                            throw new IOException("Pre-commit verification failed: coordinates mismatch in chunk (" + task.chunkX() + ", " + task.chunkZ() + ")");
                        }
                        if (task.voxelCheck() != null) {
                            VoxelCheck vc = task.voxelCheck();
                            if (!FastChunkVerifier.verifyChunkVoxel(verifyPayload, task.chunkX(), task.chunkZ(),
                                    vc.localX(), vc.worldY(), vc.localZ(), vc.expectedBlockId(), registry)) {
                                throw new IOException("Pre-commit verification failed: voxel mismatch in chunk (" + task.chunkX() + ", " + task.chunkZ() + ")");
                            }
                        }
                        metricsList.set(i, metricsList.get(i).withVerified(true));
                    }
                } else {
                    for (int i = 0; i < chunks.size(); i++) {
                        ChunkWriteTask task = chunks.get(i);
                        metricsList.set(i, metricsList.get(i).withVerified(true));
                        AsyncChunkIntegrityVerifier.enqueue(this, task.chunkX(), task.chunkZ(), task.voxelCheck());
                    }
                }

                return metricsList;
            } catch (Throwable t) {
                LOGGER.log(System.Logger.Level.ERROR,
                        "Batch write failed for region r.{0}.{1}.mca, initiating automatic rollback: {2}",
                        rx, rz, t.getMessage());
                try {
                    writer.rollback(snapshot, rollbackPayloads);
                } catch (Throwable rbEx) {
                    LOGGER.log(System.Logger.Level.ERROR,
                            "Critical: Rollback failed for region r.{0}.{1}.mca: {2}", rx, rz, rbEx.getMessage());
                    t.addSuppressed(rbEx);
                }
                throw (t instanceof IOException ioe) ? ioe : new IOException("Region batch write failed and was rolled back", t);
            }
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
        int localIndex = localX + localZ * 32;
        long rKey = regionKey(rx, rz);

        ReentrantLock lock = regionLocks.computeIfAbsent(rKey, k -> new ReentrantLock());
        lock.lock();
        try {
            McaRegionWriter writer = getOrOpenWriter(rx, rz);
            SectorAllocator.Snapshot snapshot = writer.snapshotAllocator();
            byte[] oldRaw = writer.readChunkRaw(localIndex);

            try {
                // 1. Serialize Chunk NBT: patch existing chunk non-destructively, or create ex-nihilo if new
                FastNbtWriter nbtWriter = NBT_WRITER_CACHE.get();
                nbtWriter.reset();
                boolean patched = false;
                if (writer.hasChunk(localX, localZ)) {
                    java.nio.ByteBuffer existingPayload = writer.readChunkPayload(localX, localZ);
                    if (existingPayload != null) {
                        try {
                            FastChunkNbtPatcher.patchChunk(
                                    existingPayload,
                                    chunkX, chunkZ,
                                    minSectionY, maxSectionY,
                                    registry,
                                    sections,
                                    blockEntities,
                                    nbtWriter
                            );
                            patched = true;
                        } catch (Exception e) {
                            LOGGER.log(System.Logger.Level.WARNING,
                                    "Failed to patch existing chunk NBT ({0}, {1}) in region r.{2}.{3}.mca, falling back to full chunk writer: {4}",
                                    chunkX, chunkZ, rx, rz, e.getMessage());
                            nbtWriter.reset();
                        }
                    }
                }
                if (!patched) {
                    FastChunkNbtWriter.writeChunk(nbtWriter, chunkX, chunkZ, minSectionY, maxSectionY, registry, sections, blockEntities);
                }

                // 2. Compress and write payload to region file (defer header sync)
                McaRegionWriter.WriteMetrics metrics = writer.writeChunk(localX, localZ, nbtWriter.toReadBuffer(), false);

                // 3. Pre-commit coordinate verification
                java.nio.ByteBuffer verifyPayload = writer.readChunkPayload(localX, localZ);
                if (verifyPayload == null) {
                    throw new IOException("Pre-commit verification failed: chunk (" + chunkX + ", " + chunkZ + ") cannot be read back");
                }
                if (!FastChunkVerifier.verifyChunkCoordinates(verifyPayload, chunkX, chunkZ)) {
                    throw new IOException("Pre-commit verification failed: coordinates mismatch in chunk (" + chunkX + ", " + chunkZ + ")");
                }
                metrics = metrics.withVerified(true);

                // 4. Commit header
                writer.syncHeaderOnly();
                writer.flush(false);

                // 5. Notify listeners
                long duration = System.nanoTime() - startTime;
                for (IChunkWriteListener listener : listeners) {
                    try {
                        listener.onChunkWritten(chunkX, chunkZ, duration, metrics);
                    } catch (Throwable t) {
                        LOGGER.log(System.Logger.Level.WARNING, "Error in chunk write listener: {0}", t.getMessage());
                    }
                }

                return metrics;
            } catch (Throwable t) {
                LOGGER.log(System.Logger.Level.ERROR,
                        "Chunk write failed for ({0}, {1}) in region r.{2}.{3}.mca, rolling back: {4}",
                        chunkX, chunkZ, rx, rz, t.getMessage());
                try {
                    Map<Integer, byte[]> rollbackMap = new HashMap<>();
                    if (oldRaw != null) {
                        rollbackMap.put(localIndex, oldRaw);
                    }
                    writer.rollback(snapshot, rollbackMap);
                } catch (Throwable rbEx) {
                    LOGGER.log(System.Logger.Level.ERROR,
                            "Critical: Rollback failed for region r.{0}.{1}.mca: {2}", rx, rz, rbEx.getMessage());
                    t.addSuppressed(rbEx);
                }
                throw (t instanceof IOException ioe) ? ioe : new IOException("Chunk write failed and was rolled back", t);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Surgically verifies whether a single voxel physically written to disk matches the expected block ID,
     * reusing open region writers and zero-allocation decompression buffers.
     *
     * @param chunkX          World chunk X
     * @param chunkZ          World chunk Z
     * @param localX          Local voxel X [0..15]
     * @param worldY          World block Y
     * @param localZ          Local voxel Z [0..15]
     * @param expectedBlockId Expected Block ID in BlockIdRegistry
     * @return True if physical block matches expectedBlockId
     */
    public boolean verifyVoxel(int chunkX, int chunkZ, int localX, int worldY, int localZ, int expectedBlockId) {
        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        int localCx = chunkX & 31;
        int localCz = chunkZ & 31;
        long rKey = regionKey(rx, rz);

        ReentrantLock lock = regionLocks.computeIfAbsent(rKey, k -> new ReentrantLock());
        lock.lock();
        try {
            McaRegionWriter writer = openWriters.get(rKey);
            if (writer == null) {
                Path mcaFile = regionDirectory.resolve("r." + rx + "." + rz + ".mca");
                if (!java.nio.file.Files.isRegularFile(mcaFile)) {
                    return expectedBlockId == BlockIdRegistry.AIR_ID;
                }
                writer = getOrOpenWriter(rx, rz);
            }

            java.nio.ByteBuffer payload = writer.readChunkPayload(localCx, localCz);
            if (payload == null) {
                return expectedBlockId == BlockIdRegistry.AIR_ID;
            }

            return FastChunkVerifier.verifyChunkVoxel(payload, chunkX, chunkZ, localX, worldY, localZ, expectedBlockId, registry);
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Failed to verify voxel ({0}, {1}, {2}) in chunk ({3}, {4}): {5}",
                    localX, worldY, localZ, chunkX, chunkZ, e.getMessage());
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Synchronously verifies coordinates and optional voxel check for a single chunk on disk.
     * Thread-safe and acquires striped region lock.
     *
     * @param chunkX World chunk X
     * @param chunkZ World chunk Z
     * @param check  Optional VoxelCheck descriptor (or null)
     * @return True if chunk coordinates and voxel match strictly
     */
    public boolean verifyChunkSync(int chunkX, int chunkZ, VoxelCheck check) {
        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        int localX = chunkX & 31;
        int localZ = chunkZ & 31;
        long rKey = regionKey(rx, rz);
        ReentrantLock lock = regionLocks.computeIfAbsent(rKey, k -> new ReentrantLock());
        lock.lock();
        try {
            McaRegionWriter writer = getOrOpenWriter(rx, rz);
            java.nio.ByteBuffer verifyPayload = writer.readChunkPayload(localX, localZ);
            if (verifyPayload == null) {
                return false;
            }
            if (!FastChunkVerifier.verifyChunkCoordinates(verifyPayload, chunkX, chunkZ)) {
                return false;
            }
            if (check != null) {
                return FastChunkVerifier.verifyChunkVoxel(verifyPayload, chunkX, chunkZ,
                        check.localX(), check.worldY(), check.localZ(), check.expectedBlockId(), registry);
            }
            return true;
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Failed to verify chunk ({0}, {1}) in region r.{2}.{3}.mca: {4}",
                    chunkX, chunkZ, rx, rz, e.getMessage());
            return false;
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
