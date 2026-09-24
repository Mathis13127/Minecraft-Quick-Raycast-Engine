package com.pixel.qve.state;

import com.pixel.qve.mca.FastNbtReader;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance, two-level hierarchical property registry and indexer for BlockStates.
 * <p>
 * Decomposes property names (Level 1: {@code keyId}) and property values (Level 2: {@code valId})
 * into compact 8-bit unsigned identifiers. Maps 16-bit {@code blockId} to compact pairs
 * {@code [keyId, valId]} enabling O(1), zero-allocation property lookups without String parsing.
 */
public final class PropertyIndexRegistry {

    /** Sentinel value indicating that a property or value is absent. */
    public static final byte NO_VALUE = -1;

    private static final int MAX_KEYS = 256;
    private static final int MAX_VALUES_PER_KEY = 256;
    private static final int INITIAL_BLOCK_CAPACITY = 1024;

    // Level 1: Property Keys ("facing", "half", "waterlogged", etc.)
    private final Map<String, Byte> keyNameToId = new ConcurrentHashMap<>();
    private final String[] idToKeyName = new String[MAX_KEYS];
    private volatile int keyCount = 0;

    // Level 1 L1 Hash Cache for zero-allocation byte buffer lookups
    private static final int L1_KEY_SIZE = 512;
    private static final int L1_KEY_MASK = L1_KEY_SIZE - 1;
    private final int[] l1KeyHashes = new int[L1_KEY_SIZE];
    private final byte[] l1KeyIds = new byte[L1_KEY_SIZE];
    private final long[] l1KeyOccupied = new long[L1_KEY_SIZE / 64];

    // Level 2: Property Values per Key ("north", "south", "true", "false", etc.)
    @SuppressWarnings("unchecked")
    private final Map<String, Byte>[] valNameToId = new Map[MAX_KEYS];
    private final String[][] idToValName = new String[MAX_KEYS][MAX_VALUES_PER_KEY];
    private final int[] valCounts = new int[MAX_KEYS];

    // Level 2 L1 Hash Cache: (keyId << 24) | valHash
    private static final int L1_VAL_SIZE = 1024;
    private static final int L1_VAL_MASK = L1_VAL_SIZE - 1;
    private final int[] l1ValHashes = new int[L1_VAL_SIZE];
    private final byte[] l1ValIds = new byte[L1_VAL_SIZE];
    private final long[] l1ValOccupied = new long[L1_VAL_SIZE / 64];

    // Block ID to property pairs [keyId_0, valId_0, keyId_1, valId_1, ...]
    private volatile byte[][] blockProperties = new byte[INITIAL_BLOCK_CAPACITY][];

    public PropertyIndexRegistry() {
        Arrays.fill(l1KeyIds, NO_VALUE);
        Arrays.fill(l1ValIds, NO_VALUE);
    }

    /**
     * Resolves or registers a property key name (e.g. "facing") returning its 8-bit index.
     *
     * @param keyName Property key name
     * @return Compact 8-bit key ID (0..255)
     */
    public synchronized byte getOrRegisterKey(String keyName) {
        Objects.requireNonNull(keyName, "Property keyName cannot be null");
        Byte existing = keyNameToId.get(keyName);
        if (existing != null) {
            return existing;
        }

        if (keyCount >= MAX_KEYS) {
            throw new IllegalStateException("Maximum property key capacity (" + MAX_KEYS + ") exceeded");
        }

        byte newId = (byte) keyCount++;
        idToKeyName[newId & 0xFF] = keyName;
        keyNameToId.put(keyName, newId);
        valNameToId[newId & 0xFF] = new ConcurrentHashMap<>();
        return newId;
    }

    /**
     * Resolves or registers a property key name directly from a ByteBuffer slice with zero heap allocations on L1 cache hits.
     *
     * @param buf    Direct or heap ByteBuffer
     * @param offset Byte start offset
     * @param len    Byte length
     * @return Compact 8-bit key ID
     */
    public byte getOrRegisterKey(ByteBuffer buf, int offset, int len) {
        int hash = FastNbtReader.hashBytes(buf, offset, len);
        int slot = (hash ^ (hash >>> 16)) & L1_KEY_MASK;
        int wordIdx = slot >>> 6;
        long bit = 1L << (slot & 63);

        if ((l1KeyOccupied[wordIdx] & bit) != 0L && l1KeyHashes[slot] == hash) {
            return l1KeyIds[slot];
        }

        String decoded = FastNbtReader.decodeStringDirect(buf, offset, len);
        byte id = getOrRegisterKey(decoded);

        synchronized (this) {
            l1KeyHashes[slot] = hash;
            l1KeyIds[slot] = id;
            l1KeyOccupied[wordIdx] |= bit;
        }

        return id;
    }

    /**
     * Retrieves the existing key ID for a property name, or {@link #NO_VALUE} if unmapped.
     *
     * @param keyName Property key name
     * @return 8-bit key ID or NO_VALUE
     */
    public byte getKeyId(String keyName) {
        Byte id = keyNameToId.get(keyName);
        return (id != null) ? id : NO_VALUE;
    }

    /**
     * Gets the string name of a property key by ID.
     *
     * @param keyId 8-bit key ID
     * @return Property name, or null if unmapped
     */
    public String getKeyName(byte keyId) {
        int idx = keyId & 0xFF;
        return (idx < MAX_KEYS) ? idToKeyName[idx] : null;
    }

    /**
     * Gets the total count of registered property keys.
     *
     * @return Number of registered keys
     */
    public int getKeyCount() {
        return keyCount;
    }

    /**
     * Resolves or registers a property value for a given key, returning its 8-bit index.
     *
     * @param keyId 8-bit key ID
     * @param value Property value string (e.g. "north")
     * @return Compact 8-bit value ID
     */
    public synchronized byte getOrRegisterValue(byte keyId, String value) {
        Objects.requireNonNull(value, "Property value cannot be null");
        int kIdx = keyId & 0xFF;
        if (kIdx >= keyCount || valNameToId[kIdx] == null) {
            throw new IllegalArgumentException("Unregistered property keyId: " + keyId);
        }

        Map<String, Byte> valMap = valNameToId[kIdx];
        Byte existing = valMap.get(value);
        if (existing != null) {
            return existing;
        }

        int count = valCounts[kIdx];
        if (count >= MAX_VALUES_PER_KEY) {
            throw new IllegalStateException("Maximum values (" + MAX_VALUES_PER_KEY + ") exceeded for key " + idToKeyName[kIdx]);
        }

        byte newValId = (byte) count;
        valCounts[kIdx] = count + 1;
        idToValName[kIdx][newValId & 0xFF] = value;
        valMap.put(value, newValId);
        return newValId;
    }

    /**
     * Resolves or registers a property value directly from a ByteBuffer slice with zero allocations on L1 hits.
     *
     * @param keyId  8-bit key ID
     * @param buf    Direct or heap ByteBuffer
     * @param offset Byte start offset
     * @param len    Byte length
     * @return Compact 8-bit value ID
     */
    public byte getOrRegisterValue(byte keyId, ByteBuffer buf, int offset, int len) {
        int valHash = FastNbtReader.hashBytes(buf, offset, len);
        int compositeHash = ((keyId & 0xFF) << 24) ^ valHash;
        int slot = (compositeHash ^ (compositeHash >>> 16)) & L1_VAL_MASK;
        int wordIdx = slot >>> 6;
        long bit = 1L << (slot & 63);

        if ((l1ValOccupied[wordIdx] & bit) != 0L && l1ValHashes[slot] == compositeHash) {
            return l1ValIds[slot];
        }

        String decoded = FastNbtReader.decodeStringDirect(buf, offset, len);
        byte valId = getOrRegisterValue(keyId, decoded);

        synchronized (this) {
            l1ValHashes[slot] = compositeHash;
            l1ValIds[slot] = valId;
            l1ValOccupied[wordIdx] |= bit;
        }

        return valId;
    }

    /**
     * Retrieves the existing value ID for a given key and value, or {@link #NO_VALUE} if unmapped.
     *
     * @param keyId 8-bit key ID
     * @param value Property value string
     * @return 8-bit value ID or NO_VALUE
     */
    public byte getValueId(byte keyId, String value) {
        int kIdx = keyId & 0xFF;
        if (kIdx >= keyCount || valNameToId[kIdx] == null) {
            return NO_VALUE;
        }
        Byte id = valNameToId[kIdx].get(value);
        return (id != null) ? id : NO_VALUE;
    }

    /**
     * Gets the string value of a property for a given key ID and value ID.
     *
     * @param keyId 8-bit key ID
     * @param valId 8-bit value ID
     * @return Value string, or null if unmapped
     */
    public String getValueName(byte keyId, byte valId) {
        int kIdx = keyId & 0xFF;
        int vIdx = valId & 0xFF;
        if (kIdx < MAX_KEYS && vIdx < MAX_VALUES_PER_KEY) {
            return idToValName[kIdx][vIdx];
        }
        return null;
    }

    /**
     * Gets the total count of registered values for a property key.
     *
     * @param keyId 8-bit key ID
     * @return Number of registered values
     */
    public int getValueCount(byte keyId) {
        int kIdx = keyId & 0xFF;
        return (kIdx < MAX_KEYS) ? valCounts[kIdx] : 0;
    }

    /**
     * Stores the compact property pairs {@code [keyId, valId, keyId, valId, ...]} for a given block ID.
     *
     * @param blockId 16-bit block ID
     * @param pairs   Compact array of key-value ID pairs
     */
    public synchronized void registerBlockProperties(short blockId, byte[] pairs) {
        int index = blockId & 0xFFFF;
        ensureBlockCapacity(index + 1);
        blockProperties[index] = pairs;
    }

    /**
     * Retrieves the raw compact property pairs for a given block ID.
     *
     * @param blockId 16-bit block ID
     * @return Array of [keyId, valId] pairs, or null if none
     */
    public byte[] getBlockProperties(short blockId) {
        int index = blockId & 0xFFFF;
        byte[][] props = this.blockProperties;
        return (index < props.length) ? props[index] : null;
    }

    /**
     * Ultra-fast O(1) query returning the property value ID for a given block ID and key ID.
     * Executes in ~1-2 CPU cycles via linear scan across a small (2-8 bytes) L1-resident array.
     *
     * @param blockId 16-bit block ID
     * @param keyId   8-bit property key ID
     * @return 8-bit value ID, or {@link #NO_VALUE} (-1) if property is not present on this block
     */
    public byte getPropertyValue(short blockId, byte keyId) {
        int index = blockId & 0xFFFF;
        byte[][] props = this.blockProperties;
        if (index >= props.length) {
            return NO_VALUE;
        }
        byte[] pairs = props[index];
        if (pairs == null) {
            return NO_VALUE;
        }

        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i] == keyId) {
                return pairs[i + 1];
            }
        }
        return NO_VALUE;
    }

    /**
     * Returns true if the block possesses the specified property.
     *
     * @param blockId 16-bit block ID
     * @param keyId   8-bit property key ID
     * @return True if present
     */
    public boolean hasProperty(short blockId, byte keyId) {
        return getPropertyValue(blockId, keyId) != NO_VALUE;
    }

    /**
     * Retrieves the human-readable string value for a given block ID and property key ID.
     *
     * @param blockId 16-bit block ID
     * @param keyId   8-bit property key ID
     * @return Value string, or null if property absent
     */
    public String getPropertyValueName(short blockId, byte keyId) {
        byte valId = getPropertyValue(blockId, keyId);
        if (valId == NO_VALUE) {
            return null;
        }
        return getValueName(keyId, valId);
    }

    /**
     * Formats all properties of a block ID into a canonical bracketed string representation (e.g. "[facing=north,half=bottom]").
     *
     * @param blockId 16-bit block ID
     * @return Formatted string, or empty string if no properties
     */
    public String formatProperties(short blockId) {
        byte[] pairs = getBlockProperties(blockId);
        if (pairs == null || pairs.length == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) sb.append(",");
            byte k = pairs[i];
            byte v = pairs[i + 1];
            sb.append(getKeyName(k)).append("=").append(getValueName(k, v));
        }
        sb.append("]");
        return sb.toString();
    }

    private void ensureBlockCapacity(int minCapacity) {
        if (minCapacity > blockProperties.length) {
            int newCap = Math.max(blockProperties.length * 2, minCapacity);
            this.blockProperties = Arrays.copyOf(blockProperties, newCap);
        }
    }

    /**
     * Clears all registered keys, values, and block properties.
     */
    public synchronized void clear() {
        keyNameToId.clear();
        Arrays.fill(idToKeyName, null);
        keyCount = 0;
        Arrays.fill(l1KeyHashes, 0);
        Arrays.fill(l1KeyIds, NO_VALUE);
        Arrays.fill(l1KeyOccupied, 0L);

        for (int i = 0; i < MAX_KEYS; i++) {
            if (valNameToId[i] != null) valNameToId[i].clear();
            Arrays.fill(idToValName[i], null);
            valCounts[i] = 0;
        }

        Arrays.fill(l1ValHashes, 0);
        Arrays.fill(l1ValIds, NO_VALUE);
        Arrays.fill(l1ValOccupied, 0L);

        Arrays.fill(blockProperties, null);
    }
}
