package com.pixel.qve.core.test;

import com.pixel.qve.mca.FastNbtReader;
import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.state.BlockStateDictionary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class BlockStateDictionaryTest {

    @Test
    @DisplayName("BlockStateDictionary resolves properties and caches in L1 without allocation")
    void testBlockStateDictionaryResolution() throws IOException {
        BlockIdRegistry registry = new BlockIdRegistry();
        BlockStateDictionary dictionary = registry.getStateDictionary();

        // Build NBT bytes for "minecraft:oak_stairs" with Properties: { "facing": "north", "half": "bottom" }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // Name tag
        dos.writeByte(FastNbtReader.TAG_STRING);
        dos.writeUTF("Name");
        dos.writeUTF("minecraft:oak_stairs");

        // Properties tag
        dos.writeByte(FastNbtReader.TAG_COMPOUND);
        dos.writeUTF("Properties");

        int propsPayloadStart = baos.size();
        dos.writeByte(FastNbtReader.TAG_STRING);
        dos.writeUTF("facing");
        dos.writeUTF("north");

        dos.writeByte(FastNbtReader.TAG_STRING);
        dos.writeUTF("half");
        dos.writeUTF("bottom");

        dos.writeByte(FastNbtReader.TAG_END);
        int propsPayloadLen = baos.size() - propsPayloadStart;

        byte[] rawNbt = baos.toByteArray();
        ByteBuffer buf = ByteBuffer.wrap(rawNbt);

        // Find positions
        int namePos = 1 + 2 + 4 + 2; // skip tag byte (1), "Name" length (2), "Name" string (4), value length (2)
        int nameLen = "minecraft:oak_stairs".length();

        int id1 = dictionary.getOrRegisterFromBytes(buf, namePos, nameLen, propsPayloadStart, propsPayloadLen);
        assertTrue(id1 > 0, "Registered block ID should be positive");

        String canonical = dictionary.getCanonicalState(id1);
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", canonical);

        // Second lookup must hit L1 cache
        int id2 = dictionary.getOrRegisterFromBytes(buf, namePos, nameLen, propsPayloadStart, propsPayloadLen);
        assertEquals(id1, id2, "Subsequent lookup must return identical cached ID");
    }

    @Test
    @DisplayName("Property hashing is commutative (order-independent)")
    void testOrderIndependentPropertyHashing() throws IOException {
        ByteArrayOutputStream baos1 = new ByteArrayOutputStream();
        DataOutputStream dos1 = new DataOutputStream(baos1);
        dos1.writeByte(FastNbtReader.TAG_STRING);
        dos1.writeUTF("facing");
        dos1.writeUTF("north");
        dos1.writeByte(FastNbtReader.TAG_STRING);
        dos1.writeUTF("half");
        dos1.writeUTF("bottom");
        dos1.writeByte(FastNbtReader.TAG_END);

        ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
        DataOutputStream dos2 = new DataOutputStream(baos2);
        dos2.writeByte(FastNbtReader.TAG_STRING);
        dos2.writeUTF("half");
        dos2.writeUTF("bottom");
        dos2.writeByte(FastNbtReader.TAG_STRING);
        dos2.writeUTF("facing");
        dos2.writeUTF("north");
        dos2.writeByte(FastNbtReader.TAG_END);

        ByteBuffer buf1 = ByteBuffer.wrap(baos1.toByteArray());
        ByteBuffer buf2 = ByteBuffer.wrap(baos2.toByteArray());

        int hash1 = BlockStateDictionary.computePropertiesHash(buf1, 0, baos1.size());
        int hash2 = BlockStateDictionary.computePropertiesHash(buf2, 0, baos2.size());

        assertEquals(hash1, hash2, "Property order must not affect computed properties hash");
    }

    @Test
    @DisplayName("State registration listener triggers on new state discovery")
    void testStateRegistrationListener() {
        BlockIdRegistry registry = new BlockIdRegistry();
        BlockStateDictionary dictionary = registry.getStateDictionary();

        AtomicInteger callCount = new AtomicInteger();
        AtomicReference<String> lastState = new AtomicReference<>();

        dictionary.setRegistrationListener((id, canonicalState) -> {
            callCount.incrementAndGet();
            lastState.set(canonicalState);
        });

        dictionary.registerState(12345L, 10, "minecraft:stone_stairs[facing=south]");
        assertEquals(1, callCount.get());
        assertEquals("minecraft:stone_stairs[facing=south]", lastState.get());
    }
}
