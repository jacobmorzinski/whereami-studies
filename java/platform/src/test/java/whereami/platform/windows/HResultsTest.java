package whereami.platform.windows;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import whereami.platform.LocationError;

class HResultsTest {
    @Test
    void mapping() {
        assertEquals(LocationError.PERMISSION_DENIED, HResults.toError(0x80070005)); // E_ACCESSDENIED
        assertEquals(LocationError.TIMEOUT, HResults.toError(0x800705B4));           // ERROR_TIMEOUT
        assertEquals(LocationError.LOCATION_UNAVAILABLE, HResults.toError(0x80004005)); // E_FAIL
    }
}
