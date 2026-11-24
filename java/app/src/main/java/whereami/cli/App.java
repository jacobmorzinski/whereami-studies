package whereami.cli;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import whereami.platform.Address;
import whereami.platform.Location;
import whereami.platform.LocationError;
import whereami.platform.LocationException;
import whereami.platform.Platform;

/**
 * What whereami does, given a validated request and an OS backend. Pure: it
 * returns an {@link Outcome} instead of printing, so tests drive it with a
 * fake {@link Platform}.
 */
final class App {
    private App() {}

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    static Outcome run(Request req, Platform platform, Duration timeout) {
        return switch (req.mode()) {
            case Mode.Mock(Location loc) -> success(req, loc, null, true);
            case Mode.Current() -> withTimeout(req, timeout, () -> {
                Location loc = platform.getLocation(timeout);
                Address addr = platform.reverseGeocode(loc.latitude(), loc.longitude()).orElse(null);
                return success(req, loc, addr, true);
            });
            case Mode.At(Location loc) -> withTimeout(req, timeout, () -> {
                Optional<Address> addr = platform.reverseGeocode(loc.latitude(), loc.longitude());
                Outcome out = success(req, loc, addr.orElse(null), false);
                // With --at the address is the whole point, so say so when there isn't one.
                return addr.isPresent() ? out
                        : new Outcome(out.stdout(), "Warning: no address found for these coordinates.\n", 0);
            });
            case Mode.Lookup(String query) -> withTimeout(req, timeout, () -> lookup(req, platform, query));
        };
    }

    private static Outcome lookup(Request req, Platform platform, String query) throws LocationException {
        try {
            return platform.geocode(query)
                    .map(found -> success(req, found.location(), found.address(), false))
                    .orElseGet(() -> failure(req, "address_not_found",
                            "Error: couldn't find a location for \"" + query + "\".\n"
                                    + "Check the address and your network connection.\n"));
        } catch (LocationException e) {
            if (e.error() == LocationError.PLATFORM_UNSUPPORTED) {
                return failure(req, "platform_unsupported",
                        "Error: address lookup is not supported on this platform.\n");
            }
            throw e;
        }
    }

    /**
     * Runs all of a request's platform calls on one new thread and waits at
     * most {@code timeout} for them. Two details matter for the Windows backend:
     * <ul>
     *   <li>A <em>platform</em> thread, not a virtual one. COM setup belongs to
     *       one OS thread, and a virtual thread may move between OS threads.</li>
     *   <li>A <em>daemon</em> thread, so an OS call that never returns can't
     *       keep the JVM alive after we give up on it.</li>
     * </ul>
     */
    private static Outcome withTimeout(Request req, Duration timeout, Callable<Outcome> calls) {
        var result = new CompletableFuture<Outcome>();
        Thread worker = Thread.ofPlatform().daemon().name("whereami-platform").start(() -> {
            try {
                result.complete(calls.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        try {
            return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            worker.interrupt();
            return locationFailure(req, LocationError.TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return locationFailure(req, LocationError.TIMEOUT);
        } catch (ExecutionException e) {
            // Expected failures become an error message; anything else is a bug and propagates.
            switch (e.getCause()) {
                case LocationException le -> {
                    return locationFailure(req, le.error());
                }
                case RuntimeException re -> throw re;
                case Error err -> throw err;
                default -> throw new IllegalStateException(e.getCause());
            }
        }
    }

    private static Outcome success(Request req, Location loc, Address addr, boolean showAccuracy) {
        String stdout = req.json() ? Output.json(loc, addr) : Output.human(loc, addr, showAccuracy);
        return new Outcome(stdout, "", 0);
    }

    private static Outcome failure(Request req, String key, String message) {
        return new Outcome(req.json() ? Output.jsonError(key) : "", message, 1);
    }

    private static Outcome locationFailure(Request req, LocationError e) {
        String message = switch (e) {
            case PERMISSION_DENIED -> "Error: location permission denied.\n"
                    + "Grant location access in your system's privacy/location settings.\n";
            case LOCATION_UNAVAILABLE -> "Error: location unavailable.\nMake sure location services are enabled.\n";
            case TIMEOUT -> "Error: location request timed out.\n";
            case PLATFORM_UNSUPPORTED -> "Error: location is not supported on this platform yet.\n";
            case GEOCODING_FAILED -> "Error: unexpected error (GeocodingFailed)\n";
        };
        return failure(req, e.jsonKey(), message);
    }
}
