package whereami.platform.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * Calling COM methods by hand. A COM object pointer points at a pointer to
 * its vtable, an array of function pointers. Every WinRT interface starts
 * with the same six slots:
 * <pre>
 *   0 QueryInterface  1 AddRef  2 Release        (IUnknown)
 *   3 GetIids  4 GetRuntimeClassName  5 GetTrustLevel   (IInspectable)
 * </pre>
 * and the interface's own methods follow from slot 6, in the order the
 * Windows SDK headers declare them. Methods return an HRESULT (negative
 * means failure) and hand results back through pointer out-parameters.
 */
final class Com {
    private Com() {}

    private static final Linker LINKER = Linker.nativeLinker();

    private static final FunctionDescriptor QUERY_INTERFACE = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor RELEASE = FunctionDescriptor.of(JAVA_INT, ADDRESS);

    /** A failed HRESULT. Mapped to a whereami error at the edge, in {@link WindowsPlatform}. */
    static final class WinRtException extends RuntimeException {
        private final int hresult;

        WinRtException(int hresult) {
            super(String.format("HRESULT 0x%08X", hresult));
            this.hresult = hresult;
        }

        int hresult() {
            return hresult;
        }
    }

    /**
     * Calls method number {@code slot} on {@code obj}. {@code fd} describes the
     * full native signature, with {@code this} as its first argument and the
     * HRESULT as its return; {@code args} are the arguments after {@code this}.
     */
    static int call(MemorySegment obj, int slot, FunctionDescriptor fd, Object... args) {
        // Pointers that come back from native code have size 0; reinterpret says how much we may read.
        MemorySegment vtable = obj.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0)
                .reinterpret(ADDRESS.byteSize() * (slot + 1));
        MethodHandle method = LINKER.downcallHandle(vtable.getAtIndex(ADDRESS, slot), fd);
        Object[] all = new Object[args.length + 1];
        all[0] = obj;
        System.arraycopy(args, 0, all, 1, args.length);
        try {
            return (int) method.invokeWithArguments(all);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Throws for a failure HRESULT. S_OK (0) and S_FALSE (1) are both success. */
    static void check(int hr) {
        if (hr < 0) {
            throw new WinRtException(hr);
        }
    }

    /** Asks {@code obj} for another of its interfaces. The result must be released. */
    static MemorySegment queryInterface(MemorySegment obj, MemorySegment iid, Arena arena) {
        MemorySegment out = arena.allocate(ADDRESS);
        check(call(obj, 0, QUERY_INTERFACE, iid, out));
        return out.get(ADDRESS, 0);
    }

    /** Drops our reference. There's no garbage collector for COM objects. */
    static void release(MemorySegment obj) {
        if (!obj.equals(MemorySegment.NULL)) {
            call(obj, 2, RELEASE);
        }
    }
}
