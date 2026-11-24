package whereami.platform.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * The plain C functions WinRT starts from, exported by {@code combase.dll}.
 * Each is a "downcall handle": a {@link MethodHandle} that calls a native
 * function with the signature its {@link FunctionDescriptor} describes.
 */
final class WinRt {
    private WinRt() {}

    static final int RO_INIT_MULTITHREADED = 1;

    /**
     * Holder class: its fields are created the first time one is used, so
     * combase.dll is only loaded on Windows, and only when needed.
     */
    private static final class Native {
        static final Linker LINKER = Linker.nativeLinker();
        static final SymbolLookup COMBASE = SymbolLookup.libraryLookup("combase.dll", Arena.global());

        static MethodHandle function(String name, FunctionDescriptor fd) {
            return LINKER.downcallHandle(COMBASE.findOrThrow(name), fd);
        }

        // HRESULT RoInitialize(RO_INIT_TYPE initType)
        static final MethodHandle RO_INITIALIZE = function("RoInitialize", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        // void RoUninitialize()
        static final MethodHandle RO_UNINITIALIZE = function("RoUninitialize", FunctionDescriptor.ofVoid());
        // HRESULT RoGetActivationFactory(HSTRING classId, REFIID iid, void** factory)
        static final MethodHandle RO_GET_ACTIVATION_FACTORY = function("RoGetActivationFactory",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        // HRESULT RoActivateInstance(HSTRING classId, IInspectable** instance)
        static final MethodHandle RO_ACTIVATE_INSTANCE = function("RoActivateInstance",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        // HRESULT WindowsCreateString(PCWSTR source, UINT32 length, HSTRING* string)
        static final MethodHandle WINDOWS_CREATE_STRING = function("WindowsCreateString",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        // HRESULT WindowsDeleteString(HSTRING string)
        static final MethodHandle WINDOWS_DELETE_STRING = function("WindowsDeleteString",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
    }

    // invokeExact needs the exact static types, hence the casts. It declares Throwable.

    static int roInitialize(int type) {
        try {
            return (int) Native.RO_INITIALIZE.invokeExact(type);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void roUninitialize() {
        try {
            Native.RO_UNINITIALIZE.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int roGetActivationFactory(MemorySegment classId, MemorySegment iid, MemorySegment out) {
        try {
            return (int) Native.RO_GET_ACTIVATION_FACTORY.invokeExact(classId, iid, out);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int roActivateInstance(MemorySegment classId, MemorySegment out) {
        try {
            return (int) Native.RO_ACTIVATE_INSTANCE.invokeExact(classId, out);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** A WinRT string (HSTRING) from a Java string. Must be freed with {@link #windowsDeleteString}. */
    static MemorySegment windowsCreateString(String text, Arena arena) {
        MemorySegment utf16 = arena.allocateFrom(text, StandardCharsets.UTF_16LE);
        MemorySegment out = arena.allocate(ADDRESS);
        int hr;
        try {
            hr = (int) Native.WINDOWS_CREATE_STRING.invokeExact(utf16, text.length(), out);
        } catch (Throwable t) {
            throw rethrow(t);
        }
        Com.check(hr);
        return out.get(ADDRESS, 0);
    }

    static void windowsDeleteString(MemorySegment hstring) {
        try {
            int ignored = (int) Native.WINDOWS_DELETE_STRING.invokeExact(hstring);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException e) {
            return e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }
}
