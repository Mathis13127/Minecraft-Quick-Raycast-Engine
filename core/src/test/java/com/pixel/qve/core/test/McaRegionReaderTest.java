package com.pixel.qve.core.test;

import com.pixel.qve.mca.McaRegionReader;
import com.pixel.qve.state.BlockIdRegistry;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class McaRegionReaderTest {

    @Test
    void testParseRegionCoordinates() throws IOException {
        BlockIdRegistry registry = new BlockIdRegistry();
        File file00 = new File("src/test/resources/region/r.0.0.mca");
        if (!file00.exists()) file00 = new File("core/src/test/resources/region/r.0.0.mca");
        if (!file00.exists()) file00 = new File("raycast-core/src/test/resources/region/r.0.0.mca");

        try (McaRegionReader reader = new McaRegionReader(file00.toPath(), registry)) {
            assertEquals(0, reader.getRegionX());
            assertEquals(0, reader.getRegionZ());
            assertTrue(reader.hasChunk(0, 0));
        }

        File file20 = new File("src/test/resources/region/r.2.0.mca");
        if (!file20.exists()) file20 = new File("core/src/test/resources/region/r.2.0.mca");
        if (!file20.exists()) file20 = new File("raycast-core/src/test/resources/region/r.2.0.mca");

        try (McaRegionReader reader = new McaRegionReader(file20.toPath(), registry)) {
            assertEquals(2, reader.getRegionX());
            assertEquals(0, reader.getRegionZ());
        }
    }

    @Test
    void testInvalidFileNameThrows() {
        BlockIdRegistry registry = new BlockIdRegistry();
        assertThrows(IllegalArgumentException.class, () -> {
            new McaRegionReader(Path.of("invalid_chunk_name.mca"), registry);
        });
    }

    @Test
    void testReadChunkSafeHandling() throws IOException {
        BlockIdRegistry registry = new BlockIdRegistry();
        File file00 = new File("src/test/resources/region/r.0.0.mca");
        if (!file00.exists()) file00 = new File("core/src/test/resources/region/r.0.0.mca");
        if (!file00.exists()) file00 = new File("raycast-core/src/test/resources/region/r.0.0.mca");

        try (McaRegionReader reader = new McaRegionReader(file00.toPath(), registry)) {
            int count00 = reader.readChunk(0, 0, (y, s) -> {});
            assertTrue(count00 > 0, "Chunk (0, 0) in test region must have parsed sections");

            int count31 = reader.readChunk(31, 31, (y, s) -> {});
            assertTrue(count31 > 0, "Chunk (31, 31) in test region must have parsed sections");
        }
    }
}
