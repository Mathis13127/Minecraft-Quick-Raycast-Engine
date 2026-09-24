package com.pixel.raycast.core.mca;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

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

    private static final byte[][] COMMON_BYTES;
    static {
        COMMON_BYTES = new byte[COMMON_PALETTE.length][];
        for (int i = 0; i < COMMON_PALETTE.length; i++) {
            COMMON_BYTES[i] = COMMON_PALETTE[i].getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Reads a UTF-8 string from the current buffer position.
     * Uses zero-allocation matching for common Minecraft palette block names.
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
        for (int i = 0; i < COMMON_BYTES.length; i++) {
            byte[] common = COMMON_BYTES[i];
            if (common.length == len && matches(buf, pos, len, common)) {
                buf.position(pos + len);
                return COMMON_PALETTE[i];
            }
        }
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
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
}
