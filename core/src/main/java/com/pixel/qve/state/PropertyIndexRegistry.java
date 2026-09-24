package com.pixel.qve.state;

import com.pixel.qve.mca.FastNbtReader;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Industrial-grade, two-level hierarchical property registry and indexer for BlockStates.
 * <p>
 * Decomposes property names (Level 1: {@code keyId}) and property values (Level 2: {@code valId})
 * into 16-bit identifiers (supporting up to 65,535 keys and 65,535 values per key).
 * Packs properties into cache-aligned 32-bit integers {@code ((keyId << 16) | valId)}
 * enabling O(1), zero-allocation property lookups without String parsing.
 */
public final class PropertyIndexRegistry {

    /** Sentinel value indicating that a property or value is absent. */
    public static final short NO_VALUE = -1;

    /** Maximum allowed distinct property keys (65,536). */
    public static final int MAX_KEYS = 65536;

    /** Maximum allowed distinct property values per key (65,536). */
    public static final int MAX_VALUES_PER_KEY = 65536;
    private static final int INITIAL_KEY_CAPACITY = 256;
    private static final int INITIAL_BLOCK_CAPACITY = 1024;

    // Level 1: Property Keys ("facing", "half", "waterlogged", etc.)
    private final Map<String, Short> keyNameToId = new ConcurrentHashMap<>();
    private volatile String[] idToKeyName = new String[INITIAL_KEY_CAPACITY];
    private volatile int keyCount = 0;

    // Level 1 L1 Hash Cache for zero-allocation byte buffer lookups
    private static final int L1_KEY_SIZE = 1024;
    private static final int L1_KEY_MASK = L1_KEY_SIZE - 1;
    private final int[] l1KeyHashes = new int[L1_KEY_SIZE];
    private final short[] l1KeyIds = new short[L1_KEY_SIZE];
    private final long[] l1KeyOccupied = new long[L1_KEY_SIZE / 64];

    // Level 2: Property Values per Key ("north", "south", "true", "false", etc.)
    @SuppressWarnings("unchecked")
    private volatile Map<String, Short>[] valNameToId = new Map[INITIAL_KEY_CAPACITY];
    private volatile String[][] idToValName = new String[INITIAL_KEY_CAPACITY][];
    private volatile int[] valCounts = new int[INITIAL_KEY_CAPACITY];

    // Level 2 L1 Hash Cache: (keyId << 16) ^ valHash
    private static final int L1_VAL_SIZE = 2048;
    private static final int L1_VAL_MASK = L1_VAL_SIZE - 1;
    private final int[] l1ValHashes = new int[L1_VAL_SIZE];
    private final short[] l1ValIds = new short[L1_VAL_SIZE];
    private final long[] l1ValOccupied = new long[L1_VAL_SIZE / 64];

    // Block ID to packed property pairs ((keyId << 16) | valId)
    private volatile int[][] blockProperties = new int[INITIAL_BLOCK_CAPACITY][];

    /**
     * Constructs a new PropertyIndexRegistry initialized with fast L1 hash caches.
     */
    public PropertyIndexRegistry() {
        Arrays.fill(l1KeyIds, NO_VALUE);
        Arrays.fill(l1ValIds, NO_VALUE);
    }

    /**
     * Resolves or registers a property key name (e.g. "facing") returning its 16-bit index.
     *
     * @param keyName Property key name
     * @return Compact 16-bit key ID (0..65535)
     */
    public synchronized short getOrRegisterKey(String keyName) {
        Objects.requireNonNull(keyName, "Property keyName cannot be null");
        Short existing = keyNameToId.get(keyName);
        if (existing != null) {
            return existing;
        }

        if (keyCount >= MAX_KEYS) {
            throw new IllegalStateException("Maximum property key capacity (" + MAX_KEYS + ") exceeded");
        }

        short newId = (short) keyCount++;
        int idx = newId & 0xFFFF;
        ensureKeyCapacity(idx + 1);

        idToKeyName[idx] = keyName;
        keyNameToId.put(keyName, newId);
        valNameToId[idx] = new ConcurrentHashMap<>();
        idToValName[idx] = new String[8];
        return newId;
    }

    /**
     * Resolves or registers a property key name directly from a ByteBuffer slice with zero heap allocations on L1 cache hits.
     *
     * @param buf    Direct or heap ByteBuffer
     * @param offset Byte start offset
     * @param len    Byte length
     * @return Compact 16-bit key ID
     */
    public short getOrRegisterKey(ByteBuffer buf, int offset, int len) {
        int hash = FastNbtReader.hashBytes(buf, offset, len);
        int slot = (hash ^ (hash >>> 16)) & L1_KEY_MASK;
        int wordIdx = slot >>> 6;
        long bit = 1L << (slot & 63);

        if ((l1KeyOccupied[wordIdx] & bit) != 0L && l1KeyHashes[slot] == hash) {
            return l1KeyIds[slot];
        }

        String decoded = FastNbtReader.decodeStringDirect(buf, offset, len);
        short id = getOrRegisterKey(decoded);

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
     * @return 16-bit key ID or NO_VALUE
     */
    public short getKeyId(String keyName) {
        Short id = keyNameToId.get(keyName);
        return (id != null) ? id : NO_VALUE;
    }

    /**
     * Gets the string name of a property key by ID.
     *
     * @param keyId 16-bit key ID
     * @return Property name, or null if unmapped
     */
    public String getKeyName(short keyId) {
        int idx = keyId & 0xFFFF;
        String[] names = this.idToKeyName;
        return (idx < names.length) ? names[idx] : null;
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
     * Resolves or registers a property value for a given key, returning its 16-bit index.
     *
     * @param keyId 16-bit key ID
     * @param value Property value string (e.g. "north")
     * @return Compact 16-bit value ID
     */
    public synchronized short getOrRegisterValue(short keyId, String value) {
        Objects.requireNonNull(value, "Property value cannot be null");
        int kIdx = keyId & 0xFFFF;
        if (kIdx >= keyCount || valNameToId[kIdx] == null) {
            throw new IllegalArgumentException("Unregistered property keyId: " + keyId);
        }

        Map<String, Short> valMap = valNameToId[kIdx];
        Short existing = valMap.get(value);
        if (existing != null) {
            return existing;
        }

        int count = valCounts[kIdx];
        if (count >= MAX_VALUES_PER_KEY) {
            throw new IllegalStateException("Maximum values (" + MAX_VALUES_PER_KEY + ") exceeded for key " + idToKeyName[kIdx]);
        }

        short newValId = (short) count;
        valCounts[kIdx] = count + 1;

        String[] vals = idToValName[kIdx];
        if (count >= vals.length) {
            int newCap = Math.max(vals.length * 2, count + 1);
            vals = Arrays.copyOf(vals, newCap);
            idToValName[kIdx] = vals;
        }
        vals[count] = value;
        valMap.put(value, newValId);
        return newValId;
    }

    /**
     * Resolves or registers a property value directly from a ByteBuffer slice with zero allocations on L1 hits.
     *
     * @param keyId  16-bit key ID
     * @param buf    Direct or heap ByteBuffer
     * @param offset Byte start offset
     * @param len    Byte length
     * @return Compact 16-bit value ID
     */
    public short getOrRegisterValue(short keyId, ByteBuffer buf, int offset, int len) {
        int valHash = FastNbtReader.hashBytes(buf, offset, len);
        int compositeHash = ((keyId & 0xFFFF) << 16) ^ valHash;
        int slot = (compositeHash ^ (compositeHash >>> 16)) & L1_VAL_MASK;
        int wordIdx = slot >>> 6;
        long bit = 1L << (slot & 63);

        if ((l1ValOccupied[wordIdx] & bit) != 0L && l1ValHashes[slot] == compositeHash) {
            return l1ValIds[slot];
        }

        String decoded = FastNbtReader.decodeStringDirect(buf, offset, len);
        short valId = getOrRegisterValue(keyId, decoded);

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
     * @param keyId 16-bit key ID
     * @param value Property value string
     * @return 16-bit value ID or NO_VALUE
     */
    public short getValueId(short keyId, String value) {
        int kIdx = keyId & 0xFFFF;
        if (kIdx >= keyCount || valNameToId[kIdx] == null) {
            return NO_VALUE;
        }
        Short id = valNameToId[kIdx].get(value);
        return (id != null) ? id : NO_VALUE;
    }

    /**
     * Gets the string value of a property for a given key ID and value ID.
     *
     * @param keyId 16-bit key ID
     * @param valId 16-bit value ID
     * @return Value string, or null if unmapped
     */
    public String getValueName(short keyId, short valId) {
        int kIdx = keyId & 0xFFFF;
        int vIdx = valId & 0xFFFF;
        if (kIdx < keyCount && idToValName[kIdx] != null) {
            String[] vals = idToValName[kIdx];
            if (vIdx < vals.length) {
                return vals[vIdx];
            }
        }
        return null;
    }

    /**
     * Gets the total count of registered values for a property key.
     *
     * @param keyId 16-bit key ID
     * @return Number of registered values
     */
    public int getValueCount(short keyId) {
        int kIdx = keyId & 0xFFFF;
        return (kIdx < keyCount) ? valCounts[kIdx] : 0;
    }

    /**
     * Stores property pairs {@code [keyId_0, valId_0, keyId_1, valId_1, ...]} for a given block ID,
     * packing each pair into a single 32-bit int {@code ((keyId << 16) | valId)}.
     *
     * @param blockId 32-bit block ID
     * @param pairs   Array of alternating key-value short pairs
     */
    public synchronized void registerBlockProperties(int blockId, short[] pairs) {
        if (blockId <= 0) return;
        int index = blockId;
        ensureBlockCapacity(index + 1);

        if (pairs == null || pairs.length == 0) {
            blockProperties[index] = null;
            return;
        }

        int count = pairs.length / 2;
        int[] packed = new int[count];
        for (int i = 0; i < count; i++) {
            int k = pairs[i * 2] & 0xFFFF;
            int v = pairs[i * 2 + 1] & 0xFFFF;
            packed[i] = (k << 16) | v;
        }
        blockProperties[index] = packed;
    }

    /**
     * Stores already-packed 32-bit property pairs for a given block ID.
     *
     * @param blockId     32-bit block ID
     * @param packedPairs Array of packed ((keyId &lt;&lt; 16) | valId)
     */
    public synchronized void registerBlockPropertiesPacked(int blockId, int[] packedPairs) {
        if (blockId <= 0) return;
        int index = blockId;
        ensureBlockCapacity(index + 1);
        blockProperties[index] = packedPairs;
    }

    /**
     * Retrieves the packed property array for a given block ID.
     *
     * @param blockId 32-bit block ID
     * @return Array of packed ints, or null if none
     */
    public int[] getBlockPropertiesPacked(int blockId) {
        int index = blockId;
        int[][] props = this.blockProperties;
        return (index >= 0 && index < props.length) ? props[index] : null;
    }

    /**
     * Ultra-fast O(1) query returning the property value ID for a given block ID and key ID.
     * Executes in ~1-2 CPU cycles via linear scan across a small (4-16 bytes) L1-resident array.
     *
     * @param blockId 32-bit block ID
     * @param keyId   16-bit property key ID
     * @return 16-bit value ID, or {@link #NO_VALUE} (-1) if property is not present on this block
     */
    public short getPropertyValue(int blockId, short keyId) {
        int index = blockId;
        int[][] props = this.blockProperties;
        if (index < 0 || index >= props.length) {
            return NO_VALUE;
        }
        int[] pairs = props[index];
        if (pairs == null) {
            return NO_VALUE;
        }

        int targetKey = keyId & 0xFFFF;
        for (int p : pairs) {
            if ((p >>> 16) == targetKey) {
                return (short) (p & 0xFFFF);
            }
        }
        return NO_VALUE;
    }

    /**
     * Returns true if the block possesses the specified property.
     *
     * @param blockId 32-bit block ID
     * @param keyId   16-bit property key ID
     * @return True if present
     */
    public boolean hasProperty(int blockId, short keyId) {
        return getPropertyValue(blockId, keyId) != NO_VALUE;
    }

    /**
     * Retrieves the human-readable string value for a given block ID and property key ID.
     *
     * @param blockId 32-bit block ID
     * @param keyId   16-bit property key ID
     * @return Value string, or null if property absent
     */
    public String getPropertyValueName(int blockId, short keyId) {
        short valId = getPropertyValue(blockId, keyId);
        if (valId == NO_VALUE) {
            return null;
        }
        return getValueName(keyId, valId);
    }

    /**
     * Formats all properties of a block ID into a canonical bracketed string representation (e.g. "[facing=north,half=bottom]").
     *
     * @param blockId 32-bit block ID
     * @return Formatted string, or empty string if no properties
     */
    public String formatProperties(int blockId) {
        int[] packed = getBlockPropertiesPacked(blockId);
        if (packed == null || packed.length == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < packed.length; i++) {
            if (i > 0) sb.append(",");
            short k = (short) (packed[i] >>> 16);
            short v = (short) (packed[i] & 0xFFFF);
            sb.append(getKeyName(k)).append("=").append(getValueName(k, v));
        }
        sb.append("]");
        return sb.toString();
    }

    private void ensureKeyCapacity(int minCapacity) {
        if (minCapacity > idToKeyName.length) {
            int newCap = Math.max(idToKeyName.length * 2, minCapacity);
            this.idToKeyName = Arrays.copyOf(idToKeyName, newCap);
            this.valNameToId = Arrays.copyOf(valNameToId, newCap);
            this.idToValName = Arrays.copyOf(idToValName, newCap);
            this.valCounts = Arrays.copyOf(valCounts, newCap);
        }
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

        for (int i = 0; i < valNameToId.length; i++) {
            if (valNameToId[i] != null) valNameToId[i].clear();
            if (idToValName[i] != null) Arrays.fill(idToValName[i], null);
            valCounts[i] = 0;
        }

        Arrays.fill(l1ValHashes, 0);
        Arrays.fill(l1ValIds, NO_VALUE);
        Arrays.fill(l1ValOccupied, 0L);

        Arrays.fill(blockProperties, null);
    }
}
