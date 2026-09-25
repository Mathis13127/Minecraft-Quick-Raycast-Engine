package com.pixel.qve.mca;

import com.pixel.qve.mca.storage.NativeBufferCleaner;
import java.nio.ByteBuffer;

/**
 * Cross-platform native direct buffer cleaner supporting Windows, Linux, and macOS on Java 21+.
 * Delegates to {@link NativeBufferCleaner} in the storage subsystem.
 */
public final class DirectBufferCleaner {

    private DirectBufferCleaner() {}

    /**
     * Explicitly unmaps and releases the native memory backing a DirectByteBuffer or MappedByteBuffer.
     * Safe to call multiple times or with null / heap buffers (no-op).
     *
     * @param buffer The ByteBuffer to unmap
     * @return true if cleanly unmapped, false if not supported or not a direct buffer
     */
    public static boolean clean(ByteBuffer buffer) {
        return NativeBufferCleaner.clean(buffer);
    }
}
