package com.pixel.qve.mca.writer;

import java.util.Objects;

/**
 * Immutable configuration options governing the execution and disk allocation behavior of voxel write operations.
 */
public record WriteOptions(
        ExecutionPolicy executionPolicy,
        CreationPolicy creationPolicy
) {

    /**
     * Determines whether writes route transparently (RAM or Disk) or enforce strict offline disk writes.
     */
    public enum ExecutionPolicy {
        /**
         * Transparent routing: modifies live RAM if chunk is resident, or writes directly to Anvil MCA file if offline.
         */
        UNIFIED,

        /**
         * Strict direct disk write: strictly fails fast if any targeted chunk is currently loaded in active RAM.
         */
        STRICT_DIRECT
    }

    /**
     * Determines whether missing region files or chunks are created ex-nihilo on disk or rejected.
     */
    public enum CreationPolicy {
        /**
         * Automatically creates missing .mca region files (with blank 8KB header) and blank chunks on disk.
         */
        CREATE_IF_MISSING,

        /**
         * Strictly fails fast if the targeted chunk or region file does not already exist on disk.
         */
        FAIL_IF_MISSING
    }

    /** Default write options: Unified routing with automatic ex-nihilo region and chunk creation. */
    public static final WriteOptions DEFAULT = new WriteOptions(ExecutionPolicy.UNIFIED, CreationPolicy.CREATE_IF_MISSING);

    /** Strict direct disk writing with automatic ex-nihilo creation. */
    public static final WriteOptions STRICT = new WriteOptions(ExecutionPolicy.STRICT_DIRECT, CreationPolicy.CREATE_IF_MISSING);

    /** Unified routing modifying only existing generated chunks on disk or active RAM. */
    public static final WriteOptions EXISTING_ONLY = new WriteOptions(ExecutionPolicy.UNIFIED, CreationPolicy.FAIL_IF_MISSING);

    /** Strict direct disk writing modifying only existing generated chunks on disk. */
    public static final WriteOptions STRICT_EXISTING_ONLY = new WriteOptions(ExecutionPolicy.STRICT_DIRECT, CreationPolicy.FAIL_IF_MISSING);

    public WriteOptions {
        Objects.requireNonNull(executionPolicy, "executionPolicy cannot be null");
        Objects.requireNonNull(creationPolicy, "creationPolicy cannot be null");
    }

    /**
     * Returns true if strict offline direct writing is required.
     */
    public boolean isStrict() {
        return executionPolicy == ExecutionPolicy.STRICT_DIRECT;
    }

    /**
     * Returns true if transparent unified routing is active.
     */
    public boolean isUnified() {
        return executionPolicy == ExecutionPolicy.UNIFIED;
    }

    /**
     * Returns true if missing region files and chunks can be automatically created ex-nihilo on disk.
     */
    public boolean canCreateIfMissing() {
        return creationPolicy == CreationPolicy.CREATE_IF_MISSING;
    }

    /**
     * Returns true if missing region files or chunks must trigger an immediate fail-fast error.
     */
    public boolean shouldFailIfMissing() {
        return creationPolicy == CreationPolicy.FAIL_IF_MISSING;
    }

    /**
     * Creates a new WriteOptions with the specified ExecutionPolicy.
     */
    public WriteOptions withExecutionPolicy(ExecutionPolicy newExecutionPolicy) {
        return new WriteOptions(newExecutionPolicy, this.creationPolicy);
    }

    /**
     * Creates a new WriteOptions with the specified CreationPolicy.
     */
    public WriteOptions withCreationPolicy(CreationPolicy newCreationPolicy) {
        return new WriteOptions(this.executionPolicy, newCreationPolicy);
    }
}
