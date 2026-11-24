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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import whereami.platform.LocationError;
import whereami.platform.LocationException;

/**
 * The failure paths of {@code WindowsPlatform.awaitAsync}, against a fake
 * async operation built from upcall stubs (see {@link ComTest}). The fake
 * answers QueryInterface with itself, reports a scripted sequence of
 * {@code IAsyncInfo.Status} values, and counts Cancel and Release calls.
 * Runs on any OS.
 */
class AwaitAsyncTest {
    private static final int STARTED = 0;
    private static final int COMPLETED = 1;
    private static final int CANCELED = 2;
    private static final int ERROR = 3;

    // Fake state, read by the static upcall targets below.
    private static final Deque<Integer> statuses = new ArrayDeque<>();
    private static int lastStatus;
    private static int errorCode;
    private static int cancels;
    private static int releases;

    /** Slot 0: QueryInterface. Every IID gets the same object back. */
    static int queryInterface(MemorySegment self, MemorySegment iid, MemorySegment out) {
        out.reinterpret(ADDRESS.byteSize()).set(ADDRESS, 0, self);
        return 0;
    }

    /** Slot 2: Release. */
    static int release(MemorySegment self) {
        return --releases;
    }

    /** Slot 7: get_Status. Repeats the last scripted value once the script runs out. */
    static int getStatus(MemorySegment self, MemorySegment out) {
        if (!statuses.isEmpty()) {
            lastStatus = statuses.pop();
        }
        out.reinterpret(JAVA_INT.byteSize()).set(JAVA_INT, 0, lastStatus);
        return 0;
    }

    /** Slot 8: get_ErrorCode. */
    static int getErrorCode(MemorySegment self, MemorySegment out) {
        out.reinterpret(JAVA_INT.byteSize()).set(JAVA_INT, 0, errorCode);
        return 0;
    }

    /** Slot 9: Cancel. */
    static int cancel(MemorySegment self) {
        cancels++;
        return 0;
    }

    private Arena arena;
    private MemorySegment operation;

    @BeforeEach
    void buildFake() throws ReflectiveOperationException {
        arena = Arena.ofConfined();
        Linker linker = Linker.nativeLinker();
        var lookup = MethodHandles.lookup();
        MemorySegment vtable = arena.allocate(ADDRESS, 10);
        var thisOnly = FunctionDescriptor.of(JAVA_INT, ADDRESS);
        var outParam = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
        var one = MethodType.methodType(int.class, MemorySegment.class);
        var two = MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class);
        vtable.setAtIndex(ADDRESS, 0, linker.upcallStub(
                lookup.findStatic(AwaitAsyncTest.class, "queryInterface",
                        MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), arena));
        vtable.setAtIndex(ADDRESS, 2, linker.upcallStub(
                lookup.findStatic(AwaitAsyncTest.class, "release", one), thisOnly, arena));
        vtable.setAtIndex(ADDRESS, 7, linker.upcallStub(
                lookup.findStatic(AwaitAsyncTest.class, "getStatus", two), outParam, arena));
        vtable.setAtIndex(ADDRESS, 8, linker.upcallStub(
                lookup.findStatic(AwaitAsyncTest.class, "getErrorCode", two), outParam, arena));
        vtable.setAtIndex(ADDRESS, 9, linker.upcallStub(
                lookup.findStatic(AwaitAsyncTest.class, "cancel", one), thisOnly, arena));
        operation = arena.allocate(ADDRESS);
        operation.set(ADDRESS, 0, vtable);

        statuses.clear();
        lastStatus = STARTED;
        errorCode = 0;
        cancels = 0;
        // QueryInterface hands out one reference to IAsyncInfo; awaitAsync must give it back.
        releases = 1;
    }

    @AfterEach
    void close() {
        Thread.interrupted(); // never leak an interrupt into the next test
        arena.close();
    }

    private static long inSeconds(long seconds) {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    }

    private void script(Integer... values) {
        statuses.addAll(List.of(values));
    }

    @Test
    void completesAfterPolling() throws LocationException {
        script(STARTED, STARTED, COMPLETED);
        WindowsPlatform.awaitAsync(operation, inSeconds(5), arena);
        assertEquals(0, releases, "IAsyncInfo released exactly once");
        assertEquals(0, cancels);
    }

    @Test
    void errorStatusThrowsTheOperationsErrorCode() {
        script(STARTED, ERROR);
        errorCode = HResults.E_ACCESSDENIED;
        var e = assertThrows(Com.WinRtException.class,
                () -> WindowsPlatform.awaitAsync(operation, inSeconds(5), arena));
        assertEquals(HResults.E_ACCESSDENIED, e.hresult());
        assertEquals(LocationError.PERMISSION_DENIED, HResults.toError(e.hresult()));
        assertEquals(0, releases, "IAsyncInfo released on the error path");
    }

    @Test
    void canceledStatusIsTimeout() {
        script(CANCELED);
        var e = assertThrows(LocationException.class,
                () -> WindowsPlatform.awaitAsync(operation, inSeconds(5), arena));
        assertEquals(LocationError.TIMEOUT, e.error());
        assertEquals(0, releases, "IAsyncInfo released on the canceled path");
    }

    @Test
    void deadlineCancelsAndTimesOut() {
        script(STARTED); // never finishes
        long start = System.nanoTime();
        var e = assertThrows(LocationException.class,
                () -> WindowsPlatform.awaitAsync(operation, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150), arena));
        assertEquals(LocationError.TIMEOUT, e.error());
        assertEquals(1, cancels, "the operation is cancelled, not abandoned");
        assertEquals(0, releases, "IAsyncInfo released on the deadline path");
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
    }

    @Test
    void interruptCancelsAndKeepsTheInterruptFlag() {
        script(STARTED); // never finishes
        Thread.currentThread().interrupt(); // what App does when it gives up waiting
        var e = assertThrows(LocationException.class,
                () -> WindowsPlatform.awaitAsync(operation, inSeconds(60), arena));
        assertEquals(LocationError.TIMEOUT, e.error());
        assertEquals(1, cancels, "the operation is cancelled on interrupt");
        assertEquals(0, releases, "IAsyncInfo released on the interrupt path");
        assertTrue(Thread.currentThread().isInterrupted(), "interrupt flag restored for the caller");
    }
}
