package whereami.platform.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import whereami.platform.Address;
import whereami.platform.GeocodeResult;
import whereami.platform.Location;
import whereami.platform.LocationError;
import whereami.platform.LocationException;
import whereami.platform.Platform;

/**
 * Windows location via {@code Windows.Devices.Geolocation.Geolocator}, called
 * by hand: Java has no WinRT bindings, so this does what the Rust
 * {@code windows} crate and the C# projection generate for you. It follows
 * {@code original/whereami/src/platform/windows.zig} step by step.
 *
 * <p>All calls for one request must run on one OS thread, between
 * {@code RoInitialize} and {@code RoUninitialize}. {@code App} guarantees that.
 */
public final class WindowsPlatform implements Platform {
    static final String GEOLOCATOR_CLASS = "Windows.Devices.Geolocation.Geolocator";

    // Interface IDs, from the Windows SDK (and windows.zig).
    static final String IID_IGEOLOCATOR_STATICS = "9A8E7571-2DF5-4591-9F87-EB5FD894E9B7";
    static final String IID_IGEOLOCATOR = "A9C3BF62-4524-4989-8AA9-DE019D2E551F";
    static final String IID_IASYNC_INFO = "00000036-0000-0000-C000-000000000046";
    static final String IID_IASYNC_OPERATION_ACCESS_STATUS = "DE2B24D0-B726-57B1-A7C5-E5A13932B7DE";
    static final String IID_IASYNC_OPERATION_GEOPOSITION = "EE73ECF0-099D-57E5-8407-5B32E5AF1CC4";

    // Vtable slots (0–5 are IUnknown + IInspectable; see Com).
    private static final int STATICS_REQUEST_ACCESS_ASYNC = 6;
    private static final int GEOLOCATOR_PUT_DESIRED_ACCURACY = 7;
    private static final int GEOLOCATOR_GET_GEOPOSITION_ASYNC_WITH_AGE_AND_TIMEOUT = 14;
    private static final int ASYNC_INFO_GET_STATUS = 7;
    private static final int ASYNC_INFO_GET_ERROR_CODE = 8;
    private static final int ASYNC_INFO_CANCEL = 9;
    private static final int ASYNC_OPERATION_GET_RESULTS = 8;
    private static final int GEOPOSITION_GET_COORDINATE = 6;
    private static final int GEOCOORDINATE_GET_LATITUDE = 6;
    private static final int GEOCOORDINATE_GET_LONGITUDE = 7;
    private static final int GEOCOORDINATE_GET_ACCURACY = 9;

    private static final int ACCESS_STATUS_ALLOWED = 1;
    private static final int POSITION_ACCURACY_HIGH = 0;
    private static final int ASYNC_COMPLETED = 1;
    private static final int ASYNC_CANCELED = 2;
    private static final int ASYNC_ERROR = 3;

    // Native signatures, `this` first. HRESULT f(this)
    private static final FunctionDescriptor THIS_ONLY = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    // HRESULT f(this, T* out) — any pointer out-parameter
    private static final FunctionDescriptor OUT_PARAM = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    // HRESULT put_DesiredAccuracy(this, PositionAccuracy value)
    private static final FunctionDescriptor INT_IN = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);
    // HRESULT GetGeopositionAsyncWithAgeAndTimeout(this, TimeSpan maximumAge, TimeSpan timeout, IAsyncOperation** out).
    // TimeSpan is a struct holding one int64 (100 ns ticks); on x64 it's passed exactly like an int64.
    private static final FunctionDescriptor AGE_TIMEOUT_OUT =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS);

    private static final long POLL_MILLIS = 50;

    @Override
    public Location getLocation(Duration timeout) throws LocationException {
        long deadline = System.nanoTime() + timeout.toNanos();
        // S_OK, or S_FALSE if this thread was already initialized: both need a matching RoUninitialize.
        if (WinRt.roInitialize(WinRt.RO_INIT_MULTITHREADED) < 0) {
            throw new LocationException(LocationError.LOCATION_UNAVAILABLE);
        }
        try (Arena arena = Arena.ofConfined()) {
            return query(arena, timeout, deadline);
        } catch (Com.WinRtException e) {
            throw new LocationException(HResults.toError(e.hresult()));
        } finally {
            WinRt.roUninitialize();
        }
    }

    private static Location query(Arena arena, Duration timeout, long deadline) throws LocationException {
        // Every COM pointer we get back is ours to Release; collect them and release in reverse order.
        Deque<MemorySegment> owned = new ArrayDeque<>();
        MemorySegment out = arena.allocate(ADDRESS);
        MemorySegment className = WinRt.windowsCreateString(GEOLOCATOR_CLASS, arena);
        try {
            // 1. Ask for permission: Geolocator.RequestAccessAsync() is a static method,
            //    so it lives on the class's activation factory (IGeolocatorStatics).
            Com.check(WinRt.roGetActivationFactory(className, Guid.allocate(arena, IID_IGEOLOCATOR_STATICS), out));
            MemorySegment statics = take(owned, out);
            Com.check(Com.call(statics, STATICS_REQUEST_ACCESS_ASYNC, OUT_PARAM, out));
            MemorySegment accessAsync = take(owned, out);
            awaitAsync(accessAsync, deadline, arena);
            MemorySegment accessOp = keep(owned,
                    Com.queryInterface(accessAsync, Guid.allocate(arena, IID_IASYNC_OPERATION_ACCESS_STATUS), arena));
            MemorySegment status = arena.allocate(JAVA_INT);
            Com.check(Com.call(accessOp, ASYNC_OPERATION_GET_RESULTS, OUT_PARAM, status));
            if (status.get(JAVA_INT, 0) != ACCESS_STATUS_ALLOWED) {
                throw new LocationException(LocationError.PERMISSION_DENIED);
            }

            // 2. new Geolocator(), then DesiredAccuracy = High.
            Com.check(WinRt.roActivateInstance(className, out));
            MemorySegment inspectable = take(owned, out);
            MemorySegment geolocator = keep(owned,
                    Com.queryInterface(inspectable, Guid.allocate(arena, IID_IGEOLOCATOR), arena));
            Com.check(Com.call(geolocator, GEOLOCATOR_PUT_DESIRED_ACCURACY, INT_IN, POSITION_ACCURACY_HIGH));

            // 3. GetGeopositionAsync(maximumAge: 0 = a fresh fix, timeout), and wait for it.
            long ticks = timeout.toNanos() / 100;
            Com.check(Com.call(geolocator, GEOLOCATOR_GET_GEOPOSITION_ASYNC_WITH_AGE_AND_TIMEOUT,
                    AGE_TIMEOUT_OUT, 0L, ticks, out));
            MemorySegment positionAsync = take(owned, out);
            awaitAsync(positionAsync, deadline, arena);
            MemorySegment positionOp = keep(owned,
                    Com.queryInterface(positionAsync, Guid.allocate(arena, IID_IASYNC_OPERATION_GEOPOSITION), arena));

            // 4. Read the result. GetResults and get_Coordinate return each class's
            //    default interface (IGeoposition, IGeocoordinate), so no QueryInterface needed.
            Com.check(Com.call(positionOp, ASYNC_OPERATION_GET_RESULTS, OUT_PARAM, out));
            MemorySegment position = take(owned, out);
            Com.check(Com.call(position, GEOPOSITION_GET_COORDINATE, OUT_PARAM, out));
            MemorySegment coordinate = take(owned, out);
            return new Location(
                    readDouble(coordinate, GEOCOORDINATE_GET_LATITUDE, arena),
                    readDouble(coordinate, GEOCOORDINATE_GET_LONGITUDE, arena),
                    readDouble(coordinate, GEOCOORDINATE_GET_ACCURACY, arena));
        } finally {
            while (!owned.isEmpty()) {
                Com.release(owned.pop());
            }
            WinRt.windowsDeleteString(className);
        }
    }

    /** Reads the pointer a call just wrote to the out-parameter {@code out}, and keeps it for release. */
    private static MemorySegment take(Deque<MemorySegment> owned, MemorySegment out) {
        return keep(owned, out.get(ADDRESS, 0));
    }

    /** Records {@code pointer} for release in {@code query}'s {@code finally}. */
    private static MemorySegment keep(Deque<MemorySegment> owned, MemorySegment pointer) {
        owned.push(pointer);
        return pointer;
    }

    private static double readDouble(MemorySegment obj, int slot, Arena arena) {
        MemorySegment value = arena.allocate(JAVA_DOUBLE);
        Com.check(Com.call(obj, slot, OUT_PARAM, value));
        return value.get(JAVA_DOUBLE, 0);
    }

    /**
     * Waits for a WinRT async operation by polling its IAsyncInfo.Status, as
     * windows.zig does. (The alternative is registering a completion callback,
     * which in Java would mean an upcall stub implementing a COM interface.)
     * Package-private so {@code AwaitAsyncTest} can drive it with a fake operation.
     */
    static void awaitAsync(MemorySegment operation, long deadline, Arena arena) throws LocationException {
        MemorySegment info = Com.queryInterface(operation, Guid.allocate(arena, IID_IASYNC_INFO), arena);
        try {
            MemorySegment value = arena.allocate(JAVA_INT);
            while (true) {
                Com.check(Com.call(info, ASYNC_INFO_GET_STATUS, OUT_PARAM, value));
                switch (value.get(JAVA_INT, 0)) {
                    case ASYNC_COMPLETED -> {
                        return;
                    }
                    case ASYNC_CANCELED -> throw new LocationException(LocationError.TIMEOUT);
                    case ASYNC_ERROR -> {
                        Com.check(Com.call(info, ASYNC_INFO_GET_ERROR_CODE, OUT_PARAM, value));
                        throw new Com.WinRtException(value.get(JAVA_INT, 0));
                    }
                    default -> { } // still running
                }
                if (System.nanoTime() >= deadline) {
                    Com.call(info, ASYNC_INFO_CANCEL, THIS_ONLY);
                    throw new LocationException(LocationError.TIMEOUT);
                }
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException e) {
                    // App gave up waiting for us.
                    Com.call(info, ASYNC_INFO_CANCEL, THIS_ONLY);
                    Thread.currentThread().interrupt();
                    throw new LocationException(LocationError.TIMEOUT);
                }
            }
        } finally {
            Com.release(info);
        }
    }

    /** No reverse geocoding on Windows: MapLocationFinder needs a Bing Maps key. */
    @Override
    public Optional<Address> reverseGeocode(double lat, double lon) {
        return Optional.empty();
    }

    @Override
    public Optional<GeocodeResult> geocode(String query) throws LocationException {
        throw new LocationException(LocationError.PLATFORM_UNSUPPORTED);
    }
}
