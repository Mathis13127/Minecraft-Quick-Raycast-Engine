package com.pixel.qve.core.test;

import com.pixel.qve.mca.writer.SectorAllocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

public class SectorAllocatorTest {

    @Test
    @DisplayName("Verify in-place overwrite when chunk fits in existing allocation")
    void testInPlaceOverwrite() {
        SectorAllocator allocator = new SectorAllocator();

        // 1. Initial allocation: chunk 0 needs 4 sectors
        SectorAllocator.AllocationResult res1 = allocator.allocate(0, 4);
        assertEquals(2, res1.sectorOffset(), "First chunk should start at sector 2 (after 8KB header)");
        assertEquals(4, res1.sectorCount());
        assertTrue(res1.isRelocated());

        // 2. Overwrite chunk 0 with 3 sectors (fits within 4)
        SectorAllocator.AllocationResult res2 = allocator.allocate(0, 3);
        assertEquals(2, res2.sectorOffset(), "Should stay at sector 2 (in-place)");
        assertEquals(3, res2.sectorCount());
        assertFalse(res2.isRelocated(), "Should NOT be relocated");

        // Sector 5 was freed (2 + 3 = 5, old was 2 + 4 = 6).
        // Let's allocate chunk 1 needing 1 sector -> it should take sector 5!
        SectorAllocator.AllocationResult res3 = allocator.allocate(1, 1);
        assertEquals(5, res3.sectorOffset(), "Should reuse freed sector 5");
        assertEquals(1, res3.sectorCount());
    }

    @Test
    @DisplayName("Verify hole filling and relocation when chunk expands")
    void testExpansionAndHoleFilling() {
        SectorAllocator allocator = new SectorAllocator();

        // Chunk 0: sectors 2..4 (3 sectors)
        SectorAllocator.AllocationResult c0 = allocator.allocate(0, 3);
        assertEquals(2, c0.sectorOffset());

        // Chunk 1: sectors 5..7 (3 sectors)
        SectorAllocator.AllocationResult c1 = allocator.allocate(1, 3);
        assertEquals(5, c1.sectorOffset());

        // Chunk 2: sectors 8..9 (2 sectors)
        SectorAllocator.AllocationResult c2 = allocator.allocate(2, 2);
        assertEquals(8, c2.sectorOffset());

        // Now expand Chunk 0: needs 5 sectors.
        // It cannot fit in [2..4]. Old [2..4] is freed. Chunk 0 must relocate to EOF (sector 10).
        SectorAllocator.AllocationResult c0_expanded = allocator.allocate(0, 5);
        assertEquals(10, c0_expanded.sectorOffset());
        assertEquals(5, c0_expanded.sectorCount());
        assertTrue(c0_expanded.isRelocated());

        // Now allocate Chunk 3 needing 3 sectors: it should fit into the freed hole at sector 2!
        SectorAllocator.AllocationResult c3 = allocator.allocate(3, 3);
        assertEquals(2, c3.sectorOffset(), "Chunk 3 should fill the hole left by Chunk 0");
        assertEquals(3, c3.sectorCount());
    }

    @Test
    @DisplayName("Verify header serialization and loading round-trip")
    void testHeaderRoundTrip() {
        SectorAllocator allocator = new SectorAllocator();
        allocator.allocate(10, 2);
        allocator.allocate(50, 5);
        allocator.allocate(1000, 1);

        ByteBuffer headerBuf = ByteBuffer.allocate(8192);
        allocator.writeHeader(headerBuf);
        headerBuf.flip();

        SectorAllocator loaded = new SectorAllocator();
        loaded.loadHeader(headerBuf);

        assertTrue(loaded.hasChunk(10));
        assertTrue(loaded.hasChunk(50));
        assertTrue(loaded.hasChunk(1000));
        assertFalse(loaded.hasChunk(11));

        assertEquals(allocator.getLocation(10), loaded.getLocation(10));
        assertEquals(allocator.getLocation(50), loaded.getLocation(50));
        assertEquals(allocator.getLocation(1000), loaded.getLocation(1000));
    }

    @Test
    @DisplayName("Verify atomic snapshot capture and rollback")
    void testSnapshotAndRollback() {
        SectorAllocator allocator = new SectorAllocator();
        allocator.allocate(5, 2);
        allocator.allocate(10, 4);

        int loc5_before = allocator.getLocation(5);
        int loc10_before = allocator.getLocation(10);

        SectorAllocator.Snapshot snapshot = allocator.createSnapshot();

        // Mutate allocator: allocate new chunk and relocate chunk 10
        allocator.allocate(20, 3);
        allocator.allocate(10, 8);

        assertTrue(allocator.hasChunk(20));
        assertNotEquals(loc10_before, allocator.getLocation(10));

        // Restore snapshot
        allocator.restoreSnapshot(snapshot);

        assertFalse(allocator.hasChunk(20), "Chunk 20 should be rolled back");
        assertEquals(loc5_before, allocator.getLocation(5), "Chunk 5 should match pre-snapshot location");
        assertEquals(loc10_before, allocator.getLocation(10), "Chunk 10 should match pre-snapshot location");
    }
}
