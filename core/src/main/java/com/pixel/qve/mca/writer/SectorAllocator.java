package com.pixel.qve.mca.writer;

import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Objects;

/**
 * Manages 4096-byte Anvil (.mca) sector allocations and header sector tables.
 * Prevents fragmentation by supporting in-place overwriting when compressed size fits,
 * hole-filling across previously freed sectors, and EOF appending.
 */
public final class SectorAllocator {

    public static final int SECTOR_SIZE = 4096;
    public static final int HEADER_SECTORS = 2; // Sectors 0 and 1 (8192 bytes total)
    public static final int CHUNKS_PER_REGION = 1024;

    private final int[] locations = new int[CHUNKS_PER_REGION];
    private final int[] timestamps = new int[CHUNKS_PER_REGION];
    private final BitSet occupiedSectors = new BitSet();

    /**
     * Result of a sector allocation.
     *
     * @param sectorOffset 0-based sector offset from start of file (>= 2)
     * @param sectorCount  Number of contiguous 4096-byte sectors allocated
     * @param isRelocated  True if sector was allocated in a new position (or new chunk), false if in-place
     */
    public record AllocationResult(int sectorOffset, int sectorCount, boolean isRelocated) {}

    /**
     * Creates an empty SectorAllocator for a brand new region file.
     * Marks sectors 0 and 1 as occupied for headers.
     */
    public SectorAllocator() {
        occupiedSectors.set(0, HEADER_SECTORS);
    }

    /**
     * Loads an existing 8192-byte Anvil header.
     *
     * @param headerBuf ByteBuffer containing at least 8192 bytes
     */
    public void loadHeader(ByteBuffer headerBuf) {
        Objects.requireNonNull(headerBuf, "headerBuf cannot be null");
        if (headerBuf.remaining() < 8192) {
            throw new IllegalArgumentException("Header buffer must contain at least 8192 bytes, got " + headerBuf.remaining());
        }

        occupiedSectors.clear();
        occupiedSectors.set(0, HEADER_SECTORS);

        int pos = headerBuf.position();
        // Read 1024 locations
        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            int loc = headerBuf.getInt(pos + i * 4);
            locations[i] = loc;
            int offset = (loc >>> 8) & 0xFFFFFF;
            int count = loc & 0xFF;
            if (count > 0 && offset >= HEADER_SECTORS) {
                occupiedSectors.set(offset, offset + count);
            }
        }

        // Read 1024 timestamps
        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            timestamps[i] = headerBuf.getInt(pos + 4096 + i * 4);
        }
    }

    /**
     * Calculates the number of 4096-byte sectors required for a chunk payload of the given byte length.
     * Includes the 5-byte Anvil prefix (4 bytes length + 1 byte compression type).
     *
     * @param payloadBytes Length of compressed chunk NBT payload
     * @return Number of sectors required
     */
    public static int calculateNeededSectors(int payloadBytes) {
        int totalBytes = 5 + payloadBytes;
        return (totalBytes + SECTOR_SIZE - 1) / SECTOR_SIZE;
    }

    /**
     * Allocates sectors for the given local chunk index (0..1023).
     * Reuses the existing location if the needed sectors fit within the currently allocated sectors.
     * Otherwise searches for the first available hole or appends at EOF.
     *
     * @param localIndex    Local chunk index (localX + localZ * 32)
     * @param neededSectors Number of sectors required (1..255)
     * @return AllocationResult with sectorOffset and sectorCount
     */
    public AllocationResult allocate(int localIndex, int neededSectors) {
        if (localIndex < 0 || localIndex >= CHUNKS_PER_REGION) {
            throw new IndexOutOfBoundsException("localIndex must be in 0..1023, got: " + localIndex);
        }
        if (neededSectors <= 0 || neededSectors >= 256) {
            throw new IllegalArgumentException("neededSectors must be in 1..255, got: " + neededSectors);
        }

        int oldLoc = locations[localIndex];
        int oldOffset = (oldLoc >>> 8) & 0xFFFFFF;
        int oldCount = oldLoc & 0xFF;

        // Case 1: In-place overwrite if it fits in existing sector allocation
        if (oldCount > 0 && neededSectors <= oldCount) {
            if (neededSectors < oldCount) {
                // Free the excess tail sectors
                occupiedSectors.clear(oldOffset + neededSectors, oldOffset + oldCount);
            }
            locations[localIndex] = (oldOffset << 8) | neededSectors;
            timestamps[localIndex] = (int) (System.currentTimeMillis() / 1000L);
            return new AllocationResult(oldOffset, neededSectors, false);
        }

        // Case 2: Must reallocate or allocate new
        // Free old sectors first
        if (oldCount > 0 && oldOffset >= HEADER_SECTORS) {
            occupiedSectors.clear(oldOffset, oldOffset + oldCount);
        }

        // Search for a contiguous free hole of size neededSectors starting from sector 2
        int targetOffset = findContiguousHole(neededSectors);
        if (targetOffset < HEADER_SECTORS) {
            // Append at EOF
            targetOffset = Math.max(HEADER_SECTORS, occupiedSectors.length());
        }

        occupiedSectors.set(targetOffset, targetOffset + neededSectors);
        locations[localIndex] = (targetOffset << 8) | neededSectors;
        timestamps[localIndex] = (int) (System.currentTimeMillis() / 1000L);

        return new AllocationResult(targetOffset, neededSectors, true);
    }

    private int findContiguousHole(int count) {
        int runStart = HEADER_SECTORS;
        int runLength = 0;
        int limit = occupiedSectors.length();

        for (int i = HEADER_SECTORS; i < limit; i++) {
            if (!occupiedSectors.get(i)) {
                if (runLength == 0) {
                    runStart = i;
                }
                runLength++;
                if (runLength >= count) {
                    return runStart;
                }
            } else {
                runLength = 0;
            }
        }
        return -1;
    }

    /**
     * Serializes the current 8192-byte header into the provided ByteBuffer.
     *
     * @param target Buffer with at least 8192 remaining bytes
     */
    public void writeHeader(ByteBuffer target) {
        Objects.requireNonNull(target, "target buffer cannot be null");
        if (target.remaining() < 8192) {
            throw new IllegalArgumentException("Target buffer requires at least 8192 remaining bytes, has " + target.remaining());
        }

        int start = target.position();
        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            target.putInt(locations[i]);
        }
        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            target.putInt(timestamps[i]);
        }
    }

    /**
     * Gets the location entry for a local chunk index.
     *
     * @param localIndex Local index [0..1023]
     * @return Raw location value: (offset &lt;&lt; 8) | count
     */
    public int getLocation(int localIndex) {
        return locations[localIndex];
    }

    /**
     * Checks if a local chunk index has allocated sectors in this region.
     *
     * @param localIndex Local index [0..1023]
     * @return True if sector count &gt; 0
     */
    public boolean hasChunk(int localIndex) {
        return (locations[localIndex] & 0xFF) > 0;
    }

    /**
     * Current maximum occupied sector index + 1 (representing file size in sectors).
     *
     * @return File size in 4096-byte sectors
     */
    public int getFileSizeInSectors() {
        return Math.max(HEADER_SECTORS, occupiedSectors.length());
    }

    /**
     * Immutable snapshot of the sector allocation state for transaction staging and rollback.
     */
    public record Snapshot(int[] locations, int[] timestamps, BitSet occupiedSectors) {}

    /**
     * Creates an atomic snapshot of current sector allocations and timestamps.
     *
     * @return Snapshot instance
     */
    public Snapshot createSnapshot() {
        return new Snapshot(locations.clone(), timestamps.clone(), (BitSet) occupiedSectors.clone());
    }

    /**
     * Restores sector allocations and timestamps from a previously created snapshot.
     *
     * @param snapshot Snapshot to restore
     */
    public void restoreSnapshot(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        System.arraycopy(snapshot.locations(), 0, this.locations, 0, CHUNKS_PER_REGION);
        System.arraycopy(snapshot.timestamps(), 0, this.timestamps, 0, CHUNKS_PER_REGION);
        this.occupiedSectors.clear();
        this.occupiedSectors.or(snapshot.occupiedSectors());
    }
}
