package com.pixel.qve.mca.writer;

import com.pixel.qve.mca.FastNbtReader;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Ultra-fast zero-allocation streaming binary NBT writer.
 * Writes standard Minecraft NBT compounds directly into a dynamically expanding direct or heap ByteBuffer.
 */
public final class FastNbtWriter {

    private ByteBuffer buffer;

    /**
     * Creates a FastNbtWriter with a default initial capacity (64 KB).
     */
    public FastNbtWriter() {
        this(64 * 1024);
    }

    /**
     * Creates a FastNbtWriter with a specified initial capacity.
     *
     * @param initialCapacity Initial buffer capacity in bytes
     */
    public FastNbtWriter(int initialCapacity) {
        this.buffer = ByteBuffer.allocate(Math.max(1024, initialCapacity));
    }

    /**
     * Resets the writer position to zero, reusing the existing allocated buffer.
     */
    public void reset() {
        buffer.clear();
    }

    /**
     * Ensures that the buffer has at least the required remaining capacity, expanding if needed.
     *
     * @param needed Minimum additional bytes required
     */
    public void ensureCapacity(int needed) {
        if (buffer.remaining() < needed) {
            int newCap = Math.max(buffer.capacity() * 2, buffer.position() + needed + 1024);
            ByteBuffer newBuf = ByteBuffer.allocate(newCap);
            buffer.flip();
            newBuf.put(buffer);
            this.buffer = newBuf;
        }
    }

    /**
     * Returns the underlying ByteBuffer ready for reading (flipped).
     *
     * @return ByteBuffer containing the serialized NBT stream
     */
    public ByteBuffer toReadBuffer() {
        ByteBuffer copy = buffer.duplicate();
        copy.flip();
        return copy;
    }

    /**
     * Returns the serialized bytes as a byte array.
     *
     * @return Byte array of serialized NBT
     */
    public byte[] toByteArray() {
        ByteBuffer read = toReadBuffer();
        byte[] bytes = new byte[read.remaining()];
        read.get(bytes);
        return bytes;
    }

    /**
     * Current write position in bytes.
     *
     * @return Write position
     */
    public int position() {
        return buffer.position();
    }

    // ==========================================
    // Root Tag
    // ==========================================

    /**
     * Starts the root TAG_Compound.
     *
     * @param name Root compound name (empty "" for standard Minecraft chunk root)
     */
    public FastNbtWriter beginRootCompound(String name) {
        ensureCapacity(1 + 2 + (name != null ? name.length() * 3 : 0));
        buffer.put(FastNbtReader.TAG_COMPOUND);
        putUtf8StringHeader(name != null ? name : "");
        return this;
    }

    // ==========================================
    // Compound Controls
    // ==========================================

    /**
     * Starts a named child TAG_Compound.
     *
     * @param name Field name
     */
    public FastNbtWriter beginCompound(String name) {
        putHeader(FastNbtReader.TAG_COMPOUND, name);
        return this;
    }

    /**
     * Starts a named child TAG_Compound using an ASCII byte array to avoid String UTF-8 conversion.
     *
     * @param asciiName Field name in US-ASCII bytes
     */
    public FastNbtWriter beginCompound(byte[] asciiName) {
        putHeader(FastNbtReader.TAG_COMPOUND, asciiName);
        return this;
    }

    /**
     * Starts an unnamed TAG_Compound element inside a TAG_List of compounds.
     * In NBT specification, elements inside a list carry no tag type or name header.
     */
    public FastNbtWriter beginListCompound() {
        return this;
    }

    /**
     * Writes an unnamed String element inside a TAG_List of strings.
     *
     * @param value String value
     */
    public FastNbtWriter putListString(String value) {
        putUtf8StringHeader(value != null ? value : "");
        return this;
    }

    /**
     * Ends the current TAG_Compound by writing a TAG_END (0) byte.
     */
    public FastNbtWriter endCompound() {
        ensureCapacity(1);
        buffer.put(FastNbtReader.TAG_END);
        return this;
    }

    // ==========================================
    // List Controls
    // ==========================================

    /**
     * Starts a named child TAG_List.
     *
     * @param name        Field name
     * @param elementType NBT element type tag ID (e.g. TAG_COMPOUND = 10)
     * @param count       Number of elements in the list
     */
    public FastNbtWriter beginList(String name, byte elementType, int count) {
        putHeader(FastNbtReader.TAG_LIST, name);
        ensureCapacity(5);
        buffer.put(elementType);
        buffer.putInt(count);
        return this;
    }

    /**
     * Starts a named child TAG_List using an ASCII byte array.
     *
     * @param asciiName   Field name in US-ASCII bytes
     * @param elementType NBT element type tag ID
     * @param count       Number of elements in the list
     */
    public FastNbtWriter beginList(byte[] asciiName, byte elementType, int count) {
        putHeader(FastNbtReader.TAG_LIST, asciiName);
        ensureCapacity(5);
        buffer.put(elementType);
        buffer.putInt(count);
        return this;
    }

    // ==========================================
    // Primitive Named Fields
    // ==========================================

    public FastNbtWriter putByte(String name, byte value) {
        putHeader(FastNbtReader.TAG_BYTE, name);
        ensureCapacity(1);
        buffer.put(value);
        return this;
    }

    public FastNbtWriter putByte(byte[] asciiName, byte value) {
        putHeader(FastNbtReader.TAG_BYTE, asciiName);
        ensureCapacity(1);
        buffer.put(value);
        return this;
    }

    public FastNbtWriter putShort(String name, short value) {
        putHeader(FastNbtReader.TAG_SHORT, name);
        ensureCapacity(2);
        buffer.putShort(value);
        return this;
    }

    public FastNbtWriter putShort(byte[] asciiName, short value) {
        putHeader(FastNbtReader.TAG_SHORT, asciiName);
        ensureCapacity(2);
        buffer.putShort(value);
        return this;
    }

    public FastNbtWriter putInt(String name, int value) {
        putHeader(FastNbtReader.TAG_INT, name);
        ensureCapacity(4);
        buffer.putInt(value);
        return this;
    }

    public FastNbtWriter putInt(byte[] asciiName, int value) {
        putHeader(FastNbtReader.TAG_INT, asciiName);
        ensureCapacity(4);
        buffer.putInt(value);
        return this;
    }

    public FastNbtWriter putLong(String name, long value) {
        putHeader(FastNbtReader.TAG_LONG, name);
        ensureCapacity(8);
        buffer.putLong(value);
        return this;
    }

    public FastNbtWriter putLong(byte[] asciiName, long value) {
        putHeader(FastNbtReader.TAG_LONG, asciiName);
        ensureCapacity(8);
        buffer.putLong(value);
        return this;
    }

    public FastNbtWriter putFloat(String name, float value) {
        putHeader(FastNbtReader.TAG_FLOAT, name);
        ensureCapacity(4);
        buffer.putFloat(value);
        return this;
    }

    public FastNbtWriter putDouble(String name, double value) {
        putHeader(FastNbtReader.TAG_DOUBLE, name);
        ensureCapacity(8);
        buffer.putDouble(value);
        return this;
    }

    public FastNbtWriter putString(String name, String value) {
        putHeader(FastNbtReader.TAG_STRING, name);
        putUtf8StringHeader(value != null ? value : "");
        return this;
    }

    public FastNbtWriter putString(byte[] asciiName, String value) {
        putHeader(FastNbtReader.TAG_STRING, asciiName);
        putUtf8StringHeader(value != null ? value : "");
        return this;
    }

    public FastNbtWriter putString(byte[] asciiName, byte[] asciiValue) {
        putHeader(FastNbtReader.TAG_STRING, asciiName);
        ensureCapacity(2 + (asciiValue != null ? asciiValue.length : 0));
        if (asciiValue == null || asciiValue.length == 0) {
            buffer.putShort((short) 0);
        } else {
            buffer.putShort((short) asciiValue.length);
            buffer.put(asciiValue);
        }
        return this;
    }

    // ==========================================
    // Array Named Fields
    // ==========================================

    public FastNbtWriter putByteArray(String name, byte[] array) {
        putHeader(FastNbtReader.TAG_BYTE_ARRAY, name);
        int len = (array != null) ? array.length : 0;
        ensureCapacity(4 + len);
        buffer.putInt(len);
        if (len > 0) {
            buffer.put(array);
        }
        return this;
    }

    public FastNbtWriter putByteArray(byte[] asciiName, byte[] array) {
        putHeader(FastNbtReader.TAG_BYTE_ARRAY, asciiName);
        int len = (array != null) ? array.length : 0;
        ensureCapacity(4 + len);
        buffer.putInt(len);
        if (len > 0) {
            buffer.put(array);
        }
        return this;
    }

    public FastNbtWriter putIntArray(String name, int[] array) {
        putHeader(FastNbtReader.TAG_INT_ARRAY, name);
        int len = (array != null) ? array.length : 0;
        ensureCapacity(4 + len * 4);
        buffer.putInt(len);
        for (int i = 0; i < len; i++) {
            buffer.putInt(array[i]);
        }
        return this;
    }

    public FastNbtWriter putLongArray(String name, long[] array) {
        putHeader(FastNbtReader.TAG_LONG_ARRAY, name);
        int len = (array != null) ? array.length : 0;
        ensureCapacity(4 + len * 8);
        buffer.putInt(len);
        for (int i = 0; i < len; i++) {
            buffer.putLong(array[i]);
        }
        return this;
    }

    public FastNbtWriter putLongArray(byte[] asciiName, long[] array) {
        putHeader(FastNbtReader.TAG_LONG_ARRAY, asciiName);
        int len = (array != null) ? array.length : 0;
        ensureCapacity(4 + len * 8);
        buffer.putInt(len);
        for (int i = 0; i < len; i++) {
            buffer.putLong(array[i]);
        }
        return this;
    }

    /**
     * Starts a named TAG_Long_Array field by writing its tag header and length prefix.
     * Elements can then be written sequentially via {@link #putRawLong(long)} without allocating a long[] array.
     *
     * @param asciiName Field name in US-ASCII bytes
     * @param count     Total number of longs to be written
     */
    public FastNbtWriter beginLongArray(byte[] asciiName, int count) {
        putHeader(FastNbtReader.TAG_LONG_ARRAY, asciiName);
        ensureCapacity(4 + count * 8);
        buffer.putInt(count);
        return this;
    }

    /**
     * Appends a raw 64-bit long directly to the buffer without field header.
     * Used in conjunction with {@link #beginLongArray(byte[], int)}.
     *
     * @param value 64-bit long value
     */
    public FastNbtWriter putRawLong(long value) {
        buffer.putLong(value);
        return this;
    }

    // ==========================================
    // Raw Binary Insertion
    // ==========================================

    /**
     * Appends raw bytes directly to the buffer.
     * Useful for injecting pre-serialized CompoundTags (e.g. BlockEntity NBT payloads).
     *
     * @param bytes Raw byte array to append
     */
    public FastNbtWriter putRawBytes(byte[] bytes) {
        if (bytes != null && bytes.length > 0) {
            ensureCapacity(bytes.length);
            buffer.put(bytes);
        }
        return this;
    }

    /**
     * Appends a sub-range of raw bytes directly to the buffer.
     *
     * @param bytes  Raw byte array
     * @param offset Starting byte offset
     * @param length Number of bytes to copy
     */
    public FastNbtWriter putRawBytes(byte[] bytes, int offset, int length) {
        if (bytes != null && length > 0) {
            ensureCapacity(length);
            buffer.put(bytes, offset, length);
        }
        return this;
    }

    /**
     * Appends a slice of a ByteBuffer directly to the buffer.
     *
     * @param src Source buffer
     */
    public FastNbtWriter putRawBytes(ByteBuffer src) {
        if (src != null && src.hasRemaining()) {
            ensureCapacity(src.remaining());
            buffer.put(src.slice());
        }
        return this;
    }

    // ==========================================
    // Internal Helpers
    // ==========================================

    private void putHeader(byte tagType, String name) {
        byte[] utf8 = (name != null) ? name.getBytes(StandardCharsets.UTF_8) : new byte[0];
        ensureCapacity(1 + 2 + utf8.length);
        buffer.put(tagType);
        buffer.putShort((short) utf8.length);
        if (utf8.length > 0) {
            buffer.put(utf8);
        }
    }

    private void putHeader(byte tagType, byte[] asciiName) {
        int len = (asciiName != null) ? asciiName.length : 0;
        ensureCapacity(1 + 2 + len);
        buffer.put(tagType);
        buffer.putShort((short) len);
        if (len > 0) {
            buffer.put(asciiName);
        }
    }

    private void putUtf8StringHeader(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        ensureCapacity(2 + bytes.length);
        buffer.putShort((short) bytes.length);
        if (bytes.length > 0) {
            buffer.put(bytes);
        }
    }
}
