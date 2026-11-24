package whereami.platform.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link Com} against a fake COM object built in Java: a vtable whose
 * slots are "upcall stubs", native function pointers that call back into the
 * static methods below. Runs on any OS.
 */
class ComTest {
    private static final AtomicInteger RELEASES = new AtomicInteger();

    /** Slot 2, IUnknown::Release. */
    static int release(MemorySegment self) {
        return RELEASES.incrementAndGet();
    }

    /** Slot 6, a made-up method: HRESULT Answer(this, int* out). */
    static int answer(MemorySegment self, MemorySegment out) {
        out.reinterpret(JAVA_INT.byteSize()).set(JAVA_INT, 0, 42);
        return 0;
    }

    private Arena arena;
    private MemorySegment fake;

    @BeforeEach
    void buildFake() throws ReflectiveOperationException {
        arena = Arena.ofConfined();
        Linker linker = Linker.nativeLinker();
        var lookup = MethodHandles.lookup();
        MemorySegment vtable = arena.allocate(ADDRESS, 7);
        vtable.setAtIndex(ADDRESS, 2, linker.upcallStub(
                lookup.findStatic(ComTest.class, "release", MethodType.methodType(int.class, MemorySegment.class)),
                FunctionDescriptor.of(JAVA_INT, ADDRESS), arena));
        vtable.setAtIndex(ADDRESS, 6, linker.upcallStub(
                lookup.findStatic(ComTest.class, "answer",
                        MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class)),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), arena));
        // A COM object is a pointer to (a struct whose first field is) a pointer to its vtable.
        fake = arena.allocate(ADDRESS);
        fake.set(ADDRESS, 0, vtable);
        RELEASES.set(0);
    }

    @AfterEach
    void close() {
        arena.close();
    }

    @Test
    void callsThroughVtableSlot() {
        MemorySegment out = arena.allocate(JAVA_INT);
        int hr = Com.call(fake, 6, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), out);
        assertEquals(0, hr);
        assertEquals(42, out.get(JAVA_INT, 0));
    }

    @Test
    void releaseCallsSlotTwo() {
        Com.release(fake);
        assertEquals(1, RELEASES.get());
        Com.release(MemorySegment.NULL);
        assertEquals(1, RELEASES.get());
    }

    @Test
    void checkThrowsOnFailure() {
        var e = assertThrows(Com.WinRtException.class, () -> Com.check(0x80070005));
        assertEquals(0x80070005, e.hresult());
        assertDoesNotThrow(() -> Com.check(1)); // S_FALSE is a success code
    }
}
