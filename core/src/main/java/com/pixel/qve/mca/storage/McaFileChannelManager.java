package com.pixel.qve.mca.storage;

import com.pixel.qve.mca.McaRegionReader;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized cross-platform manager for Anvil (.mca) region file lifecycles and I/O channels.
 * Coordinates between OS memory-mapped readers and direct disk writers to prevent Windows file locking
 * conflicts ({@code FileSystemException}) and cache inconsistencies.
 */
public final class McaFileChannelManager implements Closeable {

    private static final System.Logger LOGGER = System.getLogger(McaFileChannelManager.class.getName());
    private static final McaFileChannelManager GLOBAL = new McaFileChannelManager();

    /**
     * Retrieves the global McaFileChannelManager instance.
     *
     * @return Global instance
     */
    public static McaFileChannelManager getGlobal() {
        return GLOBAL;
    }

    private final Map<Long, Set<McaRegionReader>> openReaders = new ConcurrentHashMap<>();
    private final Map<Long, Object> regionLocks = new ConcurrentHashMap<>();

    private McaFileChannelManager() {}

    /**
     * Generates a 64-bit packed region key from region coordinates.
     *
     * @param rx Region X
     * @param rz Region Z
     * @return Packed long key
     */
    public static long regionKey(int rx, int rz) {
        return (((long) rx) << 32) | (rz & 0xFFFFFFFFL);
    }

    /**
     * Gets or creates a lock object for coordinating I/O access to a region file.
     *
     * @param rx Region X
     * @param rz Region Z
     * @return Lock object
     */
    public Object getRegionLock(int rx, int rz) {
        return regionLocks.computeIfAbsent(regionKey(rx, rz), k -> new Object());
    }

    /**
     * Registers an open McaRegionReader for coordination.
     *
     * @param rx     Region X
     * @param rz     Region Z
     * @param reader Open reader instance
     */
    public void registerReader(int rx, int rz, McaRegionReader reader) {
        if (reader == null) return;
        long key = regionKey(rx, rz);
        openReaders.computeIfAbsent(key, k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(reader);
    }

    /**
     * Unregisters an McaRegionReader upon closing.
     *
     * @param rx     Region X
     * @param rz     Region Z
     * @param reader Closed reader instance
     */
    public void unregisterReader(int rx, int rz, McaRegionReader reader) {
        if (reader == null) return;
        long key = regionKey(rx, rz);
        Set<McaRegionReader> set = openReaders.get(key);
        if (set != null) {
            set.remove(reader);
            if (set.isEmpty()) {
                openReaders.remove(key, set);
            }
        }
    }

    /**
     * Signals that an Anvil region file on disk has been modified or resized by a writer.
     * Triggers active readers to refresh headers and expand/remap memory-mapped views.
     *
     * @param rx Region X
     * @param rz Region Z
     */
    public void notifyRegionModified(int rx, int rz) {
        long key = regionKey(rx, rz);
        Set<McaRegionReader> set = openReaders.get(key);
        if (set != null && !set.isEmpty()) {
            for (McaRegionReader reader : set) {
                try {
                    reader.refreshHeaderIfPossible(true);
                } catch (Throwable t) {
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Failed to refresh reader on region ({0}, {1}): {2}", rx, rz, t.getMessage());
                }
            }
        }
    }

    @Override
    public void close() {
        openReaders.clear();
        regionLocks.clear();
    }
}
