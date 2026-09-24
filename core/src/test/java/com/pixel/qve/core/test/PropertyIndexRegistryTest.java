package com.pixel.qve.core.test;

import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.state.BlockStateDictionary;
import com.pixel.qve.state.PropertyIndexRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class PropertyIndexRegistryTest {

    @Test
    @DisplayName("Verify Level 1 (Keys) and Level 2 (Values) registration and retrieval")
    void testPropertyKeyAndValueRegistration() {
        PropertyIndexRegistry registry = new PropertyIndexRegistry();

        byte facingKey = registry.getOrRegisterKey("facing");
        byte halfKey = registry.getOrRegisterKey("half");
        byte waterloggedKey = registry.getOrRegisterKey("waterlogged");

        assertEquals(0, facingKey);
        assertEquals(1, halfKey);
        assertEquals(2, waterloggedKey);
        assertEquals(3, registry.getKeyCount());

        assertEquals("facing", registry.getKeyName(facingKey));
        assertEquals("half", registry.getKeyName(halfKey));
        assertEquals("waterlogged", registry.getKeyName(waterloggedKey));

        // Values for facing
        byte north = registry.getOrRegisterValue(facingKey, "north");
        byte south = registry.getOrRegisterValue(facingKey, "south");
        byte east = registry.getOrRegisterValue(facingKey, "east");
        byte west = registry.getOrRegisterValue(facingKey, "west");

        assertEquals(0, north);
        assertEquals(1, south);
        assertEquals(2, east);
        assertEquals(3, west);
        assertEquals(4, registry.getValueCount(facingKey));

        assertEquals("north", registry.getValueName(facingKey, north));
        assertEquals("south", registry.getValueName(facingKey, south));

        // Values for waterlogged
        byte valFalse = registry.getOrRegisterValue(waterloggedKey, "false");
        byte valTrue = registry.getOrRegisterValue(waterloggedKey, "true");
        assertEquals(0, valFalse);
        assertEquals(1, valTrue);
        assertEquals(2, registry.getValueCount(waterloggedKey));

        // Re-registration returns existing ID
        assertEquals(north, registry.getOrRegisterValue(facingKey, "north"));
        assertEquals(facingKey, registry.getOrRegisterKey("facing"));
    }

    @Test
    @DisplayName("Verify zero-allocation ByteBuffer lookups with L1 cache hits")
    void testByteBufferLookups() {
        PropertyIndexRegistry registry = new PropertyIndexRegistry();
        byte[] keyBytes = "facing".getBytes(StandardCharsets.UTF_8);
        byte[] valBytes = "north".getBytes(StandardCharsets.UTF_8);

        ByteBuffer buf = ByteBuffer.allocateDirect(64);
        buf.put(keyBytes);
        buf.put(valBytes);

        // First pass: registers and populates L1
        byte keyId = registry.getOrRegisterKey(buf, 0, keyBytes.length);
        byte valId = registry.getOrRegisterValue(keyId, buf, keyBytes.length, valBytes.length);

        assertEquals("facing", registry.getKeyName(keyId));
        assertEquals("north", registry.getValueName(keyId, valId));

        // Second pass: L1 hit directly from ByteBuffer
        byte keyIdHit = registry.getOrRegisterKey(buf, 0, keyBytes.length);
        byte valIdHit = registry.getOrRegisterValue(keyId, buf, keyBytes.length, valBytes.length);

        assertEquals(keyId, keyIdHit);
        assertEquals(valId, valIdHit);
    }

    @Test
    @DisplayName("Verify per-block property queries in 1-2 CPU cycles via getPropertyValue")
    void testBlockPropertyQueries() {
        PropertyIndexRegistry registry = new PropertyIndexRegistry();
        byte facingKey = registry.getOrRegisterKey("facing");
        byte halfKey = registry.getOrRegisterKey("half");
        byte shapeKey = registry.getOrRegisterKey("shape");

        byte northVal = registry.getOrRegisterValue(facingKey, "north");
        byte topVal = registry.getOrRegisterValue(halfKey, "top");

        short blockId = 42;
        byte[] pairs = new byte[]{facingKey, northVal, halfKey, topVal};
        registry.registerBlockProperties(blockId, pairs);

        // Instant queries
        assertEquals(northVal, registry.getPropertyValue(blockId, facingKey));
        assertEquals(topVal, registry.getPropertyValue(blockId, halfKey));
        assertEquals(PropertyIndexRegistry.NO_VALUE, registry.getPropertyValue(blockId, shapeKey));

        assertTrue(registry.hasProperty(blockId, facingKey));
        assertTrue(registry.hasProperty(blockId, halfKey));
        assertFalse(registry.hasProperty(blockId, shapeKey));

        assertEquals("north", registry.getPropertyValueName(blockId, facingKey));
        assertEquals("top", registry.getPropertyValueName(blockId, halfKey));
        assertNull(registry.getPropertyValueName(blockId, shapeKey));

        assertEquals("[facing=north,half=top]", registry.formatProperties(blockId));
    }

    @Test
    @DisplayName("Verify automatic property decomposition from canonical BlockState strings in BlockStateDictionary")
    void testDictionaryPropertyDecomposition() {
        BlockIdRegistry blockRegistry = new BlockIdRegistry();
        BlockStateDictionary dictionary = blockRegistry.getStateDictionary();

        short stairsId = blockRegistry.getOrRegister("minecraft:oak_stairs[facing=south,half=top,waterlogged=false]");
        dictionary.registerState(12345L, stairsId, "minecraft:oak_stairs[facing=south,half=top,waterlogged=false]");

        assertEquals("south", dictionary.getPropertyValueName(stairsId, "facing"));
        assertEquals("top", dictionary.getPropertyValueName(stairsId, "half"));
        assertEquals("false", dictionary.getPropertyValueName(stairsId, "waterlogged"));

        assertTrue(dictionary.hasProperty(stairsId, "facing"));
        assertFalse(dictionary.hasProperty(stairsId, "non_existent_prop"));

        assertEquals("[facing=south,half=top,waterlogged=false]", dictionary.formatProperties(stairsId));

        // Solid stone without properties
        short stoneId = blockRegistry.getOrRegister("minecraft:stone");
        dictionary.registerState(54321L, stoneId, "minecraft:stone");

        assertFalse(dictionary.hasProperty(stoneId, "facing"));
        assertEquals(PropertyIndexRegistry.NO_VALUE, dictionary.getPropertyValue(stoneId, "facing"));
        assertEquals("", dictionary.formatProperties(stoneId));
    }
}
