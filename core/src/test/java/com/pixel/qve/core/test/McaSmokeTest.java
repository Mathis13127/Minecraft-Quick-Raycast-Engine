package com.pixel.qve.core.test;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Diagnostic smoke test for inspecting Minecraft Anvil (.mca) region files in pure Java 21.
 */
public class McaSmokeTest {

    @Test
    void testReadMcaHeaderAndScanBlocks() throws Exception {
        File file = new File("src/test/resources/region/r.0.0.mca");
        if (!file.exists()) {
            file = new File("core/src/test/resources/region/r.0.0.mca");
        }
        if (!file.exists()) {
            file = new File("raycast-core/src/test/resources/region/r.0.0.mca");
        }
        assertTrue(file.exists(), "Region file r.0.0.mca must exist in test resources");

        try (RandomAccessFile raf = new RandomAccessFile(file, "r");
             FileChannel channel = raf.getChannel()) {

            ByteBuffer header = ByteBuffer.allocate(4096);
            channel.read(header, 0);
            header.flip();

            Set<String> uniqueBlocks = new HashSet<>();
            int populatedChunks = 0;

            for (int chunkIndex = 0; chunkIndex < 1024; chunkIndex++) {
                header.position(chunkIndex * 4);
                int b0 = header.get() & 0xFF;
                int b1 = header.get() & 0xFF;
                int b2 = header.get() & 0xFF;
                int sectorCount = header.get() & 0xFF;
                int sectorOffset = (b0 << 16) | (b1 << 8) | b2;
                if (sectorOffset == 0 || sectorCount == 0) continue;
                populatedChunks++;

                ByteBuffer cHead = ByteBuffer.allocate(5);
                channel.read(cHead, (long) sectorOffset * 4096L);
                cHead.flip();
                int cLen = cHead.getInt();
                int cComp = cHead.get() & 0xFF;
                if (cComp != 2) continue; // Expect Zlib

                ByteBuffer payloadBuf = ByteBuffer.allocate(cLen - 1);
                channel.read(payloadBuf, (long) sectorOffset * 4096L + 5L);
                payloadBuf.flip();

                byte[] cBytes = new byte[payloadBuf.remaining()];
                payloadBuf.get(cBytes);

                byte[] decomp;
                try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(cBytes))) {
                    decomp = inflater.readAllBytes();
                }

                ByteBuffer chunkNbt = ByteBuffer.wrap(decomp);
                chunkNbt.get(); // Root TAG_Compound (10)
                int rNameLen = chunkNbt.getShort() & 0xFFFF;
                chunkNbt.position(chunkNbt.position() + rNameLen);

                while (chunkNbt.hasRemaining()) {
                    byte tagType = chunkNbt.get();
                    if (tagType == 0) break;
                    int tNameLen = chunkNbt.getShort() & 0xFFFF;
                    byte[] tNameBytes = new byte[tNameLen];
                    chunkNbt.get(tNameBytes);
                    String tagName = new String(tNameBytes, StandardCharsets.UTF_8);

                    if ("sections".equals(tagName) && tagType == 9) {
                        chunkNbt.get(); // elemType
                        int sCount = chunkNbt.getInt();
                        for (int s = 0; s < sCount; s++) {
                            collectSectionBlocks(chunkNbt, uniqueBlocks);
                        }
                    } else {
                        skipTagPayload(chunkNbt, tagType);
                    }
                }
            }

            System.out.println("Scan complete: " + populatedChunks + " chunks scanned in r.0.0.mca");
            System.out.println("Discovered " + uniqueBlocks.size() + " unique block types: " + uniqueBlocks);
            assertTrue(populatedChunks > 0);
            assertTrue(uniqueBlocks.size() > 0);
        }
    }

    private static void collectSectionBlocks(ByteBuffer buf, Set<String> uniqueBlocks) {
        while (true) {
            byte childType = buf.get();
            if (childType == 0) break;
            int nameLen = buf.getShort() & 0xFFFF;
            byte[] nb = new byte[nameLen];
            buf.get(nb);
            String name = new String(nb, StandardCharsets.UTF_8);

            if ("block_states".equals(name) && childType == 10) {
                while (true) {
                    byte bsType = buf.get();
                    if (bsType == 0) break;
                    int bsNameLen = buf.getShort() & 0xFFFF;
                    byte[] bsNb = new byte[bsNameLen];
                    buf.get(bsNb);
                    String bsName = new String(bsNb, StandardCharsets.UTF_8);

                    if ("palette".equals(bsName) && bsType == 9) {
                        buf.get(); // elemType
                        int pCount = buf.getInt();
                        for (int pi = 0; pi < pCount; pi++) {
                            while (true) {
                                byte itemType = buf.get();
                                if (itemType == 0) break;
                                int itemLen = buf.getShort() & 0xFFFF;
                                byte[] itemNb = new byte[itemLen];
                                buf.get(itemNb);
                                String itemName = new String(itemNb, StandardCharsets.UTF_8);
                                if ("Name".equals(itemName) && itemType == 8) {
                                    int strLen = buf.getShort() & 0xFFFF;
                                    byte[] strNb = new byte[strLen];
                                    buf.get(strNb);
                                    String blockName = new String(strNb, StandardCharsets.UTF_8);
                                    if (!"minecraft:air".equals(blockName)) {
                                        uniqueBlocks.add(blockName);
                                    }
                                } else {
                                    skipTagPayload(buf, itemType);
                                }
                            }
                        }
                    } else {
                        skipTagPayload(buf, bsType);
                    }
                }
            } else {
                skipTagPayload(buf, childType);
            }
        }
    }

    private static void skipTagPayload(ByteBuffer buf, byte tagType) {
        switch (tagType) {
            case 1 -> buf.position(buf.position() + 1); // Byte
            case 2 -> buf.position(buf.position() + 2); // Short
            case 3 -> buf.position(buf.position() + 4); // Int
            case 4 -> buf.position(buf.position() + 8); // Long
            case 5 -> buf.position(buf.position() + 4); // Float
            case 6 -> buf.position(buf.position() + 8); // Double
            case 7 -> { // Byte Array
                int len = buf.getInt();
                buf.position(buf.position() + len);
            }
            case 8 -> { // String
                int len = buf.getShort() & 0xFFFF;
                buf.position(buf.position() + len);
            }
            case 9 -> { // List
                byte elemType = buf.get();
                int count = buf.getInt();
                for (int i = 0; i < count; i++) {
                    skipTagPayload(buf, elemType);
                }
            }
            case 10 -> { // Compound
                while (true) {
                    byte childType = buf.get();
                    if (childType == 0) break;
                    int childNameLen = buf.getShort() & 0xFFFF;
                    buf.position(buf.position() + childNameLen);
                    skipTagPayload(buf, childType);
                }
            }
            case 11 -> { // Int Array
                int len = buf.getInt();
                buf.position(buf.position() + len * 4);
            }
            case 12 -> { // Long Array
                int len = buf.getInt();
                buf.position(buf.position() + len * 8);
            }
            default -> throw new IllegalArgumentException("Unknown tag type: " + tagType);
        }
    }
}
