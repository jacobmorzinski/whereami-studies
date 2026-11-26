package whereami.platform.linux;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.UInt32;
import whereami.platform.Address;
import whereami.platform.GeocodeResult;
import whereami.platform.Location;
import whereami.platform.LocationError;
import whereami.platform.LocationException;
import whereami.platform.Platform;

/**
 * Linux location from GeoClue2 over the system D-Bus, through the
 * {@code dbus-java} library (the Windows backend, by contrast, calls WinRT by
 * hand). Compare {@code original/whereami/src/platform/linux.zig}, which drives
 * libdbus directly, and {@code rust/src/platform/linux.rs}, whose conversation
 * this follows exactly.
 *
 * <p>Extension over the original: GeoClue may send a coarse fix first and a
 * precise one moments later, so after the first fix we listen a little longer
 * and return the most accurate (see {@link #bestFix}).
 */
public final class LinuxPlatform implements Platform {
    /** {@code RequestedAccuracyLevel} for GeoClue's EXACT level (a D-Bus uint32). */
    private static final long ACCURACY_EXACT = 8;

    /** How long dbus-java may keep trying to connect to the bus. */
    private static final int CONNECT_TIMEOUT_MILLIS = 500;

    /** After the first fix, how long to keep listening for a better one. */
    private static final Duration BEST_FIX_WINDOW = Duration.ofMillis(250);

    /** Time kept in reserve before the overall deadline, for Stop and exit. */
    private static final Duration DEADLINE_MARGIN = Duration.ofMillis(50);

    @Override
    public Location getLocation(Duration timeout) throws LocationException {
        long deadline = System.nanoTime() + timeout.toNanos();
        try (DBusConnection conn = DBusConnectionBuilder.forSystemBus()
                // dbus-java retries a failed connect for the whole transport timeout
                // (default 10 s, in 500 ms steps); a missing bus should fail fast.
                .transportConfig().withTimeout(CONNECT_TIMEOUT_MILLIS).back()
                .build()) {
            return query(conn, deadline);
        } catch (DBusExecutionException e) {
            // dbus-java turns a D-Bus error name into an exception class (AccessDenied
            // for org.freedesktop.DBus.Error.AccessDenied); that class name is the
            // name we map. (getType() says the same but is marked for removal.)
            throw new LocationException(fromDbusErrorName(e.getClass().getName()));
        } catch (org.freedesktop.dbus.exceptions.DBusException | IOException e) {
            // No bus, no GeoClue service, malformed reply: nothing to locate with.
            throw new LocationException(LocationError.LOCATION_UNAVAILABLE);
        } catch (RuntimeException e) {
            // dbus-java can throw unchecked exceptions too (e.g. from a malformed bus
            // address); like Rust's Connection::system() errors, they mean no location.
            throw new LocationException(LocationError.LOCATION_UNAVAILABLE);
        }
    }

    private static Location query(DBusConnection conn, long deadline)
            throws LocationException, org.freedesktop.dbus.exceptions.DBusException {
        GeoClue.Manager manager = conn.getRemoteObject(GeoClue.SERVICE, GeoClue.MANAGER_PATH, GeoClue.Manager.class);
        DBusPath clientPath = manager.CreateClient();
        GeoClue.Client client = conn.getRemoteObject(GeoClue.SERVICE, clientPath.getPath(), GeoClue.Client.class);
        try {
            Properties props = conn.getRemoteObject(GeoClue.SERVICE, clientPath.getPath(), Properties.class);
            props.Set(GeoClue.CLIENT_INTERFACE, "DesktopId", "whereami");
            props.Set(GeoClue.CLIENT_INTERFACE, "RequestedAccuracyLevel", new UInt32(ACCURACY_EXACT));

            // Subscribe before Start, so the first fix can't slip past us. Passing
            // our client's remote object restricts the match to its path only.
            BlockingQueue<Location> fixes = new LinkedBlockingQueue<>();
            conn.addSigHandler(GeoClue.Client.LocationUpdated.class, client, signal -> {
                try {
                    fixes.add(readLocation(conn, signal.newLocation()));
                } catch (RuntimeException e) {
                    // A fix with unreadable properties is dropped, not defaulted:
                    // an accuracy of 0 would always win bestFix.
                }
            });
            client.Start();

            long end = deadline - DEADLINE_MARGIN.toNanos();
            try {
                return bestFix(fixes, BEST_FIX_WINDOW, end)
                        .orElseThrow(() -> new LocationException(LocationError.TIMEOUT));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LocationException(LocationError.TIMEOUT);
            }
        } finally {
            // Best effort, sent on every path including timeout.
            try {
                client.Stop();
            } catch (RuntimeException e) {
                // Nothing more to do; closing the connection ends our client anyway.
            }
        }
    }

    private static Location readLocation(DBusConnection conn, DBusPath path) {
        try {
            Properties props = conn.getRemoteObject(GeoClue.SERVICE, path.getPath(), Properties.class);
            return new Location(
                    ((Number) props.Get(GeoClue.LOCATION_INTERFACE, "Latitude")).doubleValue(),
                    ((Number) props.Get(GeoClue.LOCATION_INTERFACE, "Longitude")).doubleValue(),
                    ((Number) props.Get(GeoClue.LOCATION_INTERFACE, "Accuracy")).doubleValue());
        } catch (org.freedesktop.dbus.exceptions.DBusException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Translate a D-Bus error name into whereami's error kinds. */
    static LocationError fromDbusErrorName(String name) {
        if (name != null && name.contains("AccessDenied")) {
            return LocationError.PERMISSION_DENIED;
        }
        return LocationError.LOCATION_UNAVAILABLE;
    }

    /**
     * Wait for the first fix, then keep listening for {@code grace} (but never
     * past {@code deadline}, a {@link System#nanoTime()} value) and return the
     * most accurate one seen: smallest accuracy, and on a tie the earlier. The
     * first wait is bounded by the deadline too; empty means no fix by then.
     */
    static Optional<Location> bestFix(BlockingQueue<Location> updates, Duration grace, long deadline)
            throws InterruptedException {
        Location best = updates.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        if (best == null) {
            return Optional.empty();
        }
        long end = Math.min(System.nanoTime() + grace.toNanos(), deadline);
        Location fix;
        while ((fix = updates.poll(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS)) != null) {
            if (fix.accuracy() < best.accuracy()) {
                best = fix;
            }
        }
        return Optional.of(best);
    }

    /** GeoClue has no geocoder. */
    @Override
    public Optional<Address> reverseGeocode(double lat, double lon) {
        return Optional.empty();
    }

    @Override
    public Optional<GeocodeResult> geocode(String query) throws LocationException {
        throw new LocationException(LocationError.PLATFORM_UNSUPPORTED);
    }
}
