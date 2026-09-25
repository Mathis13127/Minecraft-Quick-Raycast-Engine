package com.pixel.qve.core.test;

import com.pixel.qve.mca.FastNbtReader;
import com.pixel.qve.mca.writer.FastNbtWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class FastNbtWriterTest {

    @Test
    @DisplayName("Verify FastNbtWriter serialization round-trip with FastNbtReader")
    void testBasicNbtRoundTrip() {
        FastNbtWriter writer = new FastNbtWriter();
        writer.beginRootCompound("")
                .putByte("testByte", (byte) 42)
                .putShort("testShort", (short) 1337)
                .putInt("testInt", 123456)
                .putLong("testLong", 9876543210123L)
                .putFloat("testFloat", 3.14159f)
                .putDouble("testDouble", 2.718281828459)
                .putString("testString", "QuickVoxelEngine")
                .putByteArray("testBytes", new byte[]{1, 2, 3, 4})
                .putIntArray("testInts", new int[]{10, 20, 30})
                .putLongArray("testLongs", new long[]{100L, 200L, 300L})
                .beginCompound("nestedCompound")
                    .putString("author", "Antigravity")
                    .putInt("version", 1)
                .endCompound()
                .beginList("items", FastNbtReader.TAG_COMPOUND, 2)
                    .beginListCompound()
                        .putString("id", "minecraft:diamond")
                        .putByte("count", (byte) 64)
                    .endCompound()
                    .beginListCompound()
                        .putString("id", "minecraft:iron_ingot")
                        .putByte("count", (byte) 32)
                    .endCompound()
                .endCompound();

        ByteBuffer readBuf = writer.toReadBuffer();
        Map<String, Object> parsed = FastNbtReader.parseRootCompound(readBuf);

        assertNotNull(parsed);
        assertEquals((byte) 42, parsed.get("testByte"));
        assertEquals((short) 1337, parsed.get("testShort"));
        assertEquals(123456, parsed.get("testInt"));
        assertEquals(9876543210123L, parsed.get("testLong"));
        assertEquals(3.14159f, (Float) parsed.get("testFloat"), 1e-4f);
        assertEquals(2.718281828459, (Double) parsed.get("testDouble"), 1e-6);
        assertEquals("QuickVoxelEngine", parsed.get("testString"));

        assertArrayEquals(new byte[]{1, 2, 3, 4}, (byte[]) parsed.get("testBytes"));
        assertArrayEquals(new int[]{10, 20, 30}, (int[]) parsed.get("testInts"));
        assertArrayEquals(new long[]{100L, 200L, 300L}, (long[]) parsed.get("testLongs"));

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) parsed.get("nestedCompound");
        assertNotNull(nested);
        assertEquals("Antigravity", nested.get("author"));
        assertEquals(1, nested.get("version"));

        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) parsed.get("items");
        assertNotNull(items);
        assertEquals(2, items.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> item1 = (Map<String, Object>) items.get(0);
        assertEquals("minecraft:diamond", item1.get("id"));
        assertEquals((byte) 64, item1.get("count"));

        @SuppressWarnings("unchecked")
        Map<String, Object> item2 = (Map<String, Object>) items.get(1);
        assertEquals("minecraft:iron_ingot", item2.get("id"));
        assertEquals((byte) 32, item2.get("count"));
    }
}
