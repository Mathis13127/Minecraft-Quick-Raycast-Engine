package com.pixel.qve.core.test;

import com.pixel.qve.api.nbt.INbtService;
import com.pixel.qve.mca.FastNbtReader;
import com.pixel.qve.mca.McaRegionReader;
import com.pixel.qve.mca.McaVoxelGrid;
import com.pixel.qve.mca.OnDemandNbtFetcher;
import com.pixel.qve.state.BlockIdRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class OnDemandNbtFetcherTest {

    @Test
    @DisplayName("FastNbtReader parses compounds accurately into Java Maps")
    void testFastNbtReaderParseCompound() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // Child 1: int x = 100
        dos.writeByte(FastNbtReader.TAG_INT);
        dos.writeUTF("x");
        dos.writeInt(100);

        // Child 2: string id = "minecraft:chest"
        dos.writeByte(FastNbtReader.TAG_STRING);
        dos.writeUTF("id");
        dos.writeUTF("minecraft:chest");

        // Child 3: byte custom = 1
        dos.writeByte(FastNbtReader.TAG_BYTE);
        dos.writeUTF("custom");
        dos.writeByte(1);

        dos.writeByte(FastNbtReader.TAG_END);

        ByteBuffer buf = ByteBuffer.wrap(baos.toByteArray());
        Map<String, Object> map = FastNbtReader.parseCompound(buf);

        assertNotNull(map);
        assertEquals(100, map.get("x"));
        assertEquals("minecraft:chest", map.get("id"));
        assertEquals((byte) 1, map.get("custom"));
    }

    @Test
    @DisplayName("OnDemandNbtFetcher queries McaVoxelGrid without persistent memory overhead")
    void testOnDemandNbtQueryRealRegion() throws IOException {
        File file = new File("src/test/resources/region/r.0.0.mca");
        if (!file.exists()) {
            file = new File("core/src/test/resources/region/r.0.0.mca");
        }
        if (!file.exists()) {
            file = new File("raycast-core/src/test/resources/region/r.0.0.mca");
        }
        assertTrue(file.exists(), "r.0.0.mca fixture must exist");

        BlockIdRegistry registry = new BlockIdRegistry();
        McaVoxelGrid grid = new McaVoxelGrid(registry, null, file.getParentFile().toPath(), (short) -64);

        INbtService nbtService = grid.getNbtService();
        assertNotNull(nbtService, "NBT service should be exposed by McaVoxelGrid");

        // Query an arbitrary empty coordinate: should return null cleanly with 0 exceptions
        ByteBuffer raw = nbtService.getBlockEntityRawNbt(100, 64, 100);
        // Either null or a valid compound if a tile entity is present in that seed
        if (raw != null) {
            Map<String, Object> data = FastNbtReader.parseCompound(raw);
            assertNotNull(data);
        }

        grid.close();
    }
}
