package com.pixel.qve.neoforge.api;

/**
 * Operational mode defining how voxel modifications are routed and executed.
 */
public enum VoxelWriteMode {

    /**
     * Automatically routes writes depending on whether the target chunk is loaded in live RAM:
     * <ul>
     *     <li><b>Loaded in RAM:</b> Synchronously mutates the block state on the server thread via {@code LevelChunk}.</li>
     *     <li><b>Unloaded on Disk:</b> Streams direct Anvil (.mca) sector updates to disk without loading into RAM.</li>
     * </ul>
     */
    UNIFIED("Unified", "Auto-routes between live RAM and offline MCA disk"),

    /**
     * Strictly writes directly to the Anvil (.mca) region file on disk.
     * Guaranteed mutual exclusion: strictly fails if the target chunk is currently loaded in RAM.
     */
    STRICT_DIRECT("Strict Direct Disk", "Directly writes to offline MCA disk, rejects loaded chunks");

    private final String displayName;
    private final String description;

    VoxelWriteMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    /**
     * Human-readable display name.
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Human-readable description of this mode's behavior.
     */
    public String getDescription() {
        return description;
    }
}
