package whereami.platform;

import java.time.Duration;
import java.util.Optional;

/** Stand-in for an OS whose backend isn't written yet. */
public final class UnsupportedPlatform implements Platform {
    @Override
    public Location getLocation(Duration timeout) throws LocationException {
        throw new LocationException(LocationError.PLATFORM_UNSUPPORTED);
    }

    @Override
    public Optional<Address> reverseGeocode(double lat, double lon) {
        return Optional.empty();
    }

    @Override
    public Optional<GeocodeResult> geocode(String query) throws LocationException {
        throw new LocationException(LocationError.PLATFORM_UNSUPPORTED);
    }
}
