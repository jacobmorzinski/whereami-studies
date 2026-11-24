package whereami.platform;

import java.time.Duration;
import java.util.Optional;

/** The OS boundary: one implementation per operating system. */
public interface Platform {
    /** Ask the OS for the current position, waiting at most {@code timeout}. */
    Location getLocation(Duration timeout) throws LocationException;

    /** Coordinates → address; empty if the OS has no geocoder or nothing matches. */
    Optional<Address> reverseGeocode(double lat, double lon) throws LocationException;

    /**
     * Address → coordinates; empty when nothing matches.
     * Throws {@code PLATFORM_UNSUPPORTED} where the OS has no geocoder.
     */
    Optional<GeocodeResult> geocode(String query) throws LocationException;
}
