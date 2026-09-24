package com.pixel.qve.core.test;

import com.pixel.qve.mca.McaRegionReader;
import com.pixel.qve.mca.McaVoxelGrid;
import com.pixel.qve.state.BlockIdRegistry;
import com.pixel.qve.world.VoxelSection;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link McaVoxelGrid} lazy-loading, negative caching, and memory safety.
 */
public class McaVoxelGridTest {

    @Test
    void testNegativeCacheForMissingRegion() {
        BlockIdRegistry registry = new BlockIdRegistry();
        Path dummyDir = Path.of("non/existent/region/dir");
        McaVoxelGrid grid = new McaVoxelGrid(registry, dummyDir);

        // Section in region r.999.999.mca which does not exist
        VoxelSection section1 = grid.getSection(999 << 5, 0, 999 << 5);
        assertNull(section1, "Missing region must return null");

        // Subsequent lookup must hit the negative cache without error
        VoxelSection section2 = grid.getSection((999 << 5) + 1, 0, (999 << 5) + 1);
        assertNull(section2, "Subsequent lookup in missing region must hit negative cache");
    }

    @Test
    void testNegativeCacheForLoadedChunkEmptySection() throws IOException {
        BlockIdRegistry registry = new BlockIdRegistry();
        File file00 = new File("src/test/resources/region/r.0.0.mca");
        if (!file00.exists()) file00 = new File("core/src/test/resources/region/r.0.0.mca");
        if (!file00.exists()) file00 = new File("raycast-core/src/test/resources/region/r.0.0.mca");
        assertTrue(file00.exists(), "Region file r.0.0.mca must exist");

        McaVoxelGrid grid = new McaVoxelGrid(registry, file00.getParentFile().toPath());
        try (McaRegionReader reader = new McaRegionReader(file00.toPath(), registry)) {
            grid.registerRegion(reader);
        }

        // Section Y=24 in Overworld (Y=384) is above build height and empty
        VoxelSection skySection = grid.getSection(0, 24, 0);
        assertNull(skySection, "Sky section above build height must be empty/null");

        // Subsequent queries to other empty sections in chunk (0, 0) hit the loadedChunks cache instantly
        VoxelSection skySection2 = grid.getSection(0, 25, 0);
        assertNull(skySection2, "Probing another empty section in loaded chunk must return null instantly");
    }

    @Test
    void testClearCacheResetsNegativeCaches() {
        BlockIdRegistry registry = new BlockIdRegistry();
        McaVoxelGrid grid = new McaVoxelGrid(registry, Path.of("empty/dir"));

        grid.getSection(100, 0, 100);
        grid.clearCache();
        assertEquals(0, grid.getCachedSectionCount(), "Cache must be empty after clearCache()");
    }
}
