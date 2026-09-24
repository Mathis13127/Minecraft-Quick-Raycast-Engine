package com.pixel.qve.mca;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ultra-fast zero-allocation streaming NBT parser.
 * Reads binary NBT compounds directly from ByteBuffers, skipping unneeded sub-trees in 1 cycle.
 */
public final class FastNbtReader {

    /** NBT Tag End (0). */
    public static final byte TAG_END = 0;
    /** NBT Tag Byte (1). */
    public static final byte TAG_BYTE = 1;
    /** NBT Tag Short (2). */
    public static final byte TAG_SHORT = 2;
    /** NBT Tag Int (3). */
    public static final byte TAG_INT = 3;
    /** NBT Tag Long (4). */
    public static final byte TAG_LONG = 4;
    /** NBT Tag Float (5). */
    public static final byte TAG_FLOAT = 5;
    /** NBT Tag Double (6). */
    public static final byte TAG_DOUBLE = 6;
    /** NBT Tag Byte Array (7). */
    public static final byte TAG_BYTE_ARRAY = 7;
    /** NBT Tag String (8). */
    public static final byte TAG_STRING = 8;
    /** NBT Tag List (9). */
    public static final byte TAG_LIST = 9;
    /** NBT Tag Compound (10). */
    public static final byte TAG_COMPOUND = 10;
    /** NBT Tag Int Array (11). */
    public static final byte TAG_INT_ARRAY = 11;
    /** NBT Tag Long Array (12). */
    public static final byte TAG_LONG_ARRAY = 12;

    private FastNbtReader() {}

    /**
     * Checks if the slice of bytes in the buffer matches the expected byte array.
     *
     * @param buf      Byte buffer to inspect
     * @param offset   Starting byte offset
     * @param length   Byte length to compare
     * @param expected Target byte sequence
     * @return True if contents match exactly
     */
    public static boolean matches(ByteBuffer buf, int offset, int length, byte[] expected) {
        if (length != expected.length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (buf.get(offset + i) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static final String[] COMMON_PALETTE = {
        "minecraft:air",
        "minecraft:cave_air",
        "minecraft:void_air",
        "minecraft:stone",
        "minecraft:dirt",
        "minecraft:grass_block",
        "minecraft:bedrock",
        "minecraft:deepslate",
        "minecraft:water",
        "minecraft:lava",
        "minecraft:sand",
        "minecraft:sandstone",
        "minecraft:gravel",
        "minecraft:oak_log",
        "minecraft:oak_leaves",
        "minecraft:spruce_log",
        "minecraft:spruce_leaves",
        "minecraft:birch_log",
        "minecraft:birch_leaves",
        "minecraft:short_grass",
        "minecraft:tall_grass",
        "minecraft:snow",
        "minecraft:snow_block",
        "minecraft:ice"
    };

    private static final int COMMON_TABLE_SIZE = 64;
    private static final int COMMON_TABLE_MASK = COMMON_TABLE_SIZE - 1;
    private static final int[] COMMON_HASHES = new int[COMMON_TABLE_SIZE];
    private static final byte[][] COMMON_HASH_BYTES = new byte[COMMON_TABLE_SIZE][];
    private static final String[] COMMON_HASH_STRINGS = new String[COMMON_TABLE_SIZE];

    static {
        for (int i = 0; i < COMMON_PALETTE.length; i++) {
            byte[] bytes = COMMON_PALETTE[i].getBytes(StandardCharsets.UTF_8);

            int h = 0x811c9dc5;
            for (byte b : bytes) {
                h ^= (b & 0xFF);
                h *= 0x01000193;
            }

            int slot = h & COMMON_TABLE_MASK;
            while (COMMON_HASH_STRINGS[slot] != null) {
                slot = (slot + 1) & COMMON_TABLE_MASK;
            }
            COMMON_HASHES[slot] = h;
            COMMON_HASH_BYTES[slot] = bytes;
            COMMON_HASH_STRINGS[slot] = COMMON_PALETTE[i];
        }
    }

    private static final int STRING_CACHE_SIZE = 1024;
    private static final int STRING_CACHE_MASK = STRING_CACHE_SIZE - 1;
    private static final String[] STRING_CACHE = new String[STRING_CACHE_SIZE];
    private static final int[] HASH_CACHE = new int[STRING_CACHE_SIZE];

    /**
     * Computes a 32-bit FNV-1a hash directly on a slice of a ByteBuffer with zero heap allocations.
     *
     * @param buf    Byte buffer to read from
     * @param offset Starting byte offset
     * @param length Number of bytes to hash
     * @return 32-bit integer hash
     */
    public static int hashBytes(ByteBuffer buf, int offset, int length) {
        int h = 0x811c9dc5;
        for (int i = 0; i < length; i++) {
            h ^= (buf.get(offset + i) & 0xFF);
            h *= 0x01000193;
        }
        return h;
    }

    /**
     * Reads a UTF-8 string from the current buffer position.
     * Uses zero-allocation matching for common Minecraft palette block names and an L1 direct byte-hash cache.
     *
     * @param buf Byte buffer positioned at string length prefix
     * @return Decoded String
     */
    public static String readString(ByteBuffer buf) {
        int len = buf.getShort() & 0xFFFF;
        if (len == 0) {
            return "";
        }
        int pos = buf.position();
        String result = decodeStringDirect(buf, pos, len);
        buf.position(pos + len);
        return result;
    }

    /**
     * Decodes or resolves a UTF-8 string from an arbitrary ByteBuffer slice with zero allocations on cache hits.
     *
     * @param buf    Byte buffer
     * @param offset Starting offset of string payload
     * @param length Length of string payload
     * @return Decoded String
     */
    public static String decodeStringDirect(ByteBuffer buf, int offset, int length) {
        if (length == 0) {
            return "";
        }

        int hash = hashBytes(buf, offset, length);

        // 1. Instant O(1) hash check for common vanilla palette names
        int commonSlot = hash & COMMON_TABLE_MASK;
        while (COMMON_HASH_STRINGS[commonSlot] != null) {
            if (COMMON_HASHES[commonSlot] == hash && matches(buf, offset, length, COMMON_HASH_BYTES[commonSlot])) {
                return COMMON_HASH_STRINGS[commonSlot];
            }
            commonSlot = (commonSlot + 1) & COMMON_TABLE_MASK;
        }

        // 2. Direct byte-hash L1 cache for modded / arbitrary block names
        int slot = hash & STRING_CACHE_MASK;
        String cached = STRING_CACHE[slot];
        if (cached != null && HASH_CACHE[slot] == hash && cached.length() == length) {
            boolean match = true;
            for (int i = 0; i < length; i++) {
                if ((char) (buf.get(offset + i) & 0xFF) != cached.charAt(i)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return cached;
            }
        }

        // 3. Fallback: decode string and cache it in L1 slot
        byte[] bytes = new byte[length];
        int oldPos = buf.position();
        buf.position(offset);
        buf.get(bytes);
        buf.position(oldPos);

        String decoded = new String(bytes, StandardCharsets.UTF_8).intern();
        HASH_CACHE[slot] = hash;
        STRING_CACHE[slot] = decoded;
        return decoded;
    }

    /**
     * Reads an array of 64-bit longs from the current buffer position.
     *
     * @param buf Byte buffer positioned at long array length prefix
     * @return Array of longs
     */
    public static long[] readLongArray(ByteBuffer buf) {
        int count = buf.getInt();
        if (count < 0) {
            throw new IllegalStateException("Negative long array count: " + count);
        }
        long[] array = new long[count];
        for (int i = 0; i < count; i++) {
            array[i] = buf.getLong();
        }
        return array;
    }

    /**
     * Skips the entire payload of an arbitrary NBT tag without allocating objects.
     *
     * @param buf     Byte buffer positioned immediately before tag payload
     * @param tagType Tag identifier byte (TAG_BYTE .. TAG_LONG_ARRAY)
     */
    public static void skipTagPayload(ByteBuffer buf, byte tagType) {
        switch (tagType) {
            case TAG_BYTE -> buf.position(buf.position() + 1);
            case TAG_SHORT -> buf.position(buf.position() + 2);
            case TAG_INT -> buf.position(buf.position() + 4);
            case TAG_LONG -> buf.position(buf.position() + 8);
            case TAG_FLOAT -> buf.position(buf.position() + 4);
            case TAG_DOUBLE -> buf.position(buf.position() + 8);
            case TAG_BYTE_ARRAY -> {
                int len = buf.getInt();
                buf.position(buf.position() + len);
            }
            case TAG_STRING -> {
                int len = buf.getShort() & 0xFFFF;
                buf.position(buf.position() + len);
            }
            case TAG_LIST -> {
                byte elemType = buf.get();
                int count = buf.getInt();
                for (int i = 0; i < count; i++) {
                    skipTagPayload(buf, elemType);
                }
            }
            case TAG_COMPOUND -> {
                while (true) {
                    byte childType = buf.get();
                    if (childType == TAG_END) break;
                    int nameLen = buf.getShort() & 0xFFFF;
                    buf.position(buf.position() + nameLen);
                    skipTagPayload(buf, childType);
                }
            }
            case TAG_INT_ARRAY -> {
                int len = buf.getInt();
                buf.position(buf.position() + len * 4);
            }
            case TAG_LONG_ARRAY -> {
                int len = buf.getInt();
                buf.position(buf.position() + len * 8);
            }
            default -> throw new IllegalArgumentException("Unknown NBT tag type: " + tagType);
        }
    }

    /**
     * Parses an NBT TAG_Compound into a standard Map representation.
     *
     * @param buf Byte buffer positioned at the start of a compound's payload (after name)
     * @return Map of tag names to values
     */
    public static Map<String, Object> parseCompound(ByteBuffer buf) {
        Map<String, Object> map = new LinkedHashMap<>();
        while (buf.hasRemaining()) {
            byte childType = buf.get();
            if (childType == TAG_END) break;

            int nameLen = buf.getShort() & 0xFFFF;
            int namePos = buf.position();
            buf.position(namePos + nameLen);
            String name = decodeStringDirect(buf, namePos, nameLen);

            Object value = parseTagValue(buf, childType);
            map.put(name, value);
        }
        return map;
    }

    /**
     * Parses the payload of an arbitrary NBT tag into a corresponding Java object.
     *
     * @param buf     Byte buffer positioned at tag payload
     * @param tagType NBT tag type
     * @return Java object representation
     */
    public static Object parseTagValue(ByteBuffer buf, byte tagType) {
        return switch (tagType) {
            case TAG_BYTE -> buf.get();
            case TAG_SHORT -> buf.getShort();
            case TAG_INT -> buf.getInt();
            case TAG_LONG -> buf.getLong();
            case TAG_FLOAT -> buf.getFloat();
            case TAG_DOUBLE -> buf.getDouble();
            case TAG_BYTE_ARRAY -> {
                int len = buf.getInt();
                byte[] arr = new byte[len];
                buf.get(arr);
                yield arr;
            }
            case TAG_STRING -> {
                int len = buf.getShort() & 0xFFFF;
                int pos = buf.position();
                buf.position(pos + len);
                yield decodeStringDirect(buf, pos, len);
            }
            case TAG_LIST -> {
                byte elemType = buf.get();
                int count = buf.getInt();
                List<Object> list = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    list.add(parseTagValue(buf, elemType));
                }
                yield list;
            }
            case TAG_COMPOUND -> parseCompound(buf);
            case TAG_INT_ARRAY -> {
                int len = buf.getInt();
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) arr[i] = buf.getInt();
                yield arr;
            }
            case TAG_LONG_ARRAY -> readLongArray(buf);
            default -> throw new IllegalArgumentException("Unknown NBT tag type: " + tagType);
        };
    }
}
