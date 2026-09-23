package com.pixel.raycast.core.mca;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Ultra-fast zero-allocation streaming NBT parser.
 * Reads binary NBT compounds directly from ByteBuffers, skipping unneeded sub-trees in 1 cycle.
 */
public final class FastNbtReader {

    public static final byte TAG_END = 0;
    public static final byte TAG_BYTE = 1;
    public static final byte TAG_SHORT = 2;
    public static final byte TAG_INT = 3;
    public static final byte TAG_LONG = 4;
    public static final byte TAG_FLOAT = 5;
    public static final byte TAG_DOUBLE = 6;
    public static final byte TAG_BYTE_ARRAY = 7;
    public static final byte TAG_STRING = 8;
    public static final byte TAG_LIST = 9;
    public static final byte TAG_COMPOUND = 10;
    public static final byte TAG_INT_ARRAY = 11;
    public static final byte TAG_LONG_ARRAY = 12;

    private FastNbtReader() {}

    /**
     * Checks if the slice of bytes in the buffer matches the expected byte array.
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

    /**
     * Reads a UTF-8 string from the current buffer position.
     */
    public static String readString(ByteBuffer buf) {
        int len = buf.getShort() & 0xFFFF;
        if (len == 0) {
            return "";
        }
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Reads an array of 64-bit longs from the current buffer position.
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
