package whereami.platform;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ModelTest {
    @Test
    void jsonKeys() {
        assertEquals("permission_denied", LocationError.PERMISSION_DENIED.jsonKey());
        assertEquals("location_unavailable", LocationError.LOCATION_UNAVAILABLE.jsonKey());
        assertEquals("timeout", LocationError.TIMEOUT.jsonKey());
        assertEquals("unknown_error", LocationError.GEOCODING_FAILED.jsonKey());
        assertEquals("platform_unsupported", LocationError.PLATFORM_UNSUPPORTED.jsonKey());
    }
}
