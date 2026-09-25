package com.pixel.qve.mca;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/**
 * Cross-platform native direct buffer cleaner supporting Windows, Linux, and macOS on Java 21+.
 * <p>
 * Under Windows, memory-mapped byte buffers ({@link java.nio.MappedByteBuffer}) lock the underlying
 * file in the OS kernel until explicitly unmapped. On Linux and macOS, explicit unmapping immediately
 * releases virtual address space and file descriptor backing without waiting for GC cycles.
 * </p>
 */
public final class DirectBufferCleaner {

    private static final System.Logger LOGGER = System.getLogger(DirectBufferCleaner.class.getName());
    private static final MethodHandle INVOKE_CLEANER_HANDLE;

    static {
        MethodHandle handle = null;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            Object theUnsafe = theUnsafeField.get(null);

            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle method = lookup.findVirtual(unsafeClass, "invokeCleaner",
                    MethodType.methodType(void.class, ByteBuffer.class));
            handle = method.bindTo(theUnsafe);
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Failed to initialize direct buffer unmapper via Unsafe.invokeCleaner: {0}", t.getMessage());
        }
        INVOKE_CLEANER_HANDLE = handle;
    }

    private DirectBufferCleaner() {
    }

    /**
     * Explicitly unmaps and releases the native memory backing a DirectByteBuffer or MappedByteBuffer.
     * Safe to call multiple times or with null / heap buffers (no-op).
     *
     * @param buffer The ByteBuffer to unmap
     * @return true if cleanly unmapped, false if not supported or not a direct buffer
     */
    public static boolean clean(ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect()) {
            return false;
        }

        if (INVOKE_CLEANER_HANDLE != null) {
            try {
                INVOKE_CLEANER_HANDLE.invokeExact(buffer);
                return true;
            } catch (Throwable t) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Exception during direct buffer unmapping: {0}", t.getMessage());
                return false;
            }
        }
        return false;
    }
}
