package com.pixel.raycast.core.test;

import com.pixel.raycast.core.mca.McaRegionReader;
import com.pixel.raycast.core.voxel.BlockIdRegistry;
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
        if (!file00.exists()) file00 = new File("raycast-core/src/test/resources/region/r.0.0.mca");

        try (McaRegionReader reader = new McaRegionReader(file00.toPath(), registry)) {
            assertEquals(0, reader.getRegionX());
            assertEquals(0, reader.getRegionZ());
            assertTrue(reader.hasChunk(0, 0));
        }

        File file20 = new File("src/test/resources/region/r.2.0.mca");
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
}
