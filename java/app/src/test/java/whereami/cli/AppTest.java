package whereami.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import whereami.platform.Address;
import whereami.platform.GeocodeResult;
import whereami.platform.Location;
import whereami.platform.LocationError;
import whereami.platform.LocationException;
import whereami.platform.Platform;

class AppTest {
    interface Call<T> {
        T get() throws LocationException;
    }

    /** A platform whose answers are set per test. Anything not set fails the test if called. */
    static final class Fake implements Platform {
        Call<Location> location = () -> fail("getLocation should not be called");
        Call<Optional<Address>> address = Optional::empty;
        Call<Optional<GeocodeResult>> geocode = () -> fail("geocode should not be called");
        String lastQuery;

        @Override
        public Location getLocation(Duration timeout) throws LocationException {
            return location.get();
        }

        @Override
        public Optional<Address> reverseGeocode(double lat, double lon) throws LocationException {
            return address.get();
        }

        @Override
        public Optional<GeocodeResult> geocode(String query) throws LocationException {
            lastQuery = query;
            return geocode.get();
        }
    }

    private static final Address WHITE_HOUSE =
            new Address("1600 Pennsylvania Ave NW", "Washington", "DC", "20500", "US");

    private static Outcome run(Mode mode, boolean json, Fake fake) {
        return App.run(new Request(mode, json), fake, Duration.ofSeconds(5));
    }

    private static LocationException error(LocationError e) {
        return new LocationException(e);
    }

    @Test
    void mockSkipsPlatform() {
        var out = run(new Mode.Mock(new Location(40.7128, -74.006, 0)), false, new Fake());
        assertEquals(new Outcome("Location: 40.7128, -74.0060\nAccuracy: 0m\n", "", 0), out);
    }

    @Test
    void currentWithAddress() {
        var fake = new Fake();
        fake.location = () -> new Location(1, 2, 3);
        fake.address = () -> Optional.of(WHITE_HOUSE);
        var out = run(new Mode.Current(), false, fake);
        assertEquals(0, out.exitCode());
        assertEquals("Location: 1.0000, 2.0000\nAccuracy: 3m\n"
                + "Address: 1600 Pennsylvania Ave NW, Washington, DC, 20500, US\n", out.stdout());
        assertEquals("", out.stderr());
    }

    @Test
    void atWithoutAddressWarns() {
        var out = run(new Mode.At(new Location(90, -180, 0)), false, new Fake());
        assertEquals(new Outcome("Location: 90.0000, -180.0000\n",
                "Warning: no address found for these coordinates.\n", 0), out);
    }

    @Test
    void permissionDeniedJson() {
        var fake = new Fake();
        fake.location = () -> { throw error(LocationError.PERMISSION_DENIED); };
        var out = run(new Mode.Current(), true, fake);
        assertEquals("{\"error\":\"permission_denied\"}\n", out.stdout());
        assertTrue(out.stderr().startsWith("Error: location permission denied."));
        assertEquals(1, out.exitCode());
    }

    @Test
    void unsupportedLocation() {
        var fake = new Fake();
        fake.location = () -> { throw error(LocationError.PLATFORM_UNSUPPORTED); };
        assertEquals(new Outcome("", "Error: location is not supported on this platform yet.\n", 1),
                run(new Mode.Current(), false, fake));
    }

    @Test
    void lookupUnsupported() {
        var fake = new Fake();
        fake.geocode = () -> { throw error(LocationError.PLATFORM_UNSUPPORTED); };
        assertEquals(new Outcome("{\"error\":\"platform_unsupported\"}\n",
                "Error: address lookup is not supported on this platform.\n", 1),
                run(new Mode.Lookup("Paris"), true, fake));
    }

    @Test
    void lookupNotFound() {
        var fake = new Fake();
        fake.geocode = Optional::empty;
        var out = run(new Mode.Lookup("Paris"), true, fake);
        assertEquals("{\"error\":\"address_not_found\"}\n", out.stdout());
        assertTrue(out.stderr().contains("couldn't find a location for \"Paris\""));
        assertEquals(1, out.exitCode());
        assertEquals("Paris", fake.lastQuery);
    }

    @Test
    void lookupFoundOmitsAccuracy() {
        var fake = new Fake();
        fake.geocode = () -> Optional.of(new GeocodeResult(new Location(38.8977, -77.0366, 0), WHITE_HOUSE));
        var out = run(new Mode.Lookup("1600 Pennsylvania Ave"), false, fake);
        assertEquals(0, out.exitCode());
        assertFalse(out.stdout().contains("Accuracy:"));
        assertTrue(out.stdout().contains("Address: 1600 Pennsylvania Ave NW"));
    }

    @Test
    void timeoutHoldsWhenPlatformIgnoresInterrupt() {
        var fake = new Fake();
        fake.location = () -> {
            while (true) {
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException ignored) {
                    // a badly behaved OS call: keeps waiting
                }
            }
        };
        var out = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> App.run(new Request(new Mode.Current(), true), fake, Duration.ofMillis(200)));
        assertEquals(new Outcome("{\"error\":\"timeout\"}\n", "Error: location request timed out.\n", 1), out);
    }

    @Test
    void platformRunsOnOnePlatformThread() {
        List<Thread> seen = new ArrayList<>();
        var fake = new Fake();
        fake.location = () -> {
            seen.add(Thread.currentThread());
            return new Location(1, 2, 3);
        };
        fake.address = () -> {
            seen.add(Thread.currentThread());
            return Optional.empty();
        };
        run(new Mode.Current(), false, fake);
        assertEquals(2, seen.size());
        assertSame(seen.get(0), seen.get(1));
        assertFalse(seen.get(0).isVirtual());
        assertNotSame(Thread.currentThread(), seen.get(0));
    }

    @Test
    void unexpectedExceptionIsNotSwallowed() {
        var fake = new Fake();
        fake.location = () -> { throw new IllegalStateException("bug"); };
        assertThrows(IllegalStateException.class, () -> run(new Mode.Current(), false, fake));
    }
}
