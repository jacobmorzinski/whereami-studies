package whereami.platform.windows;

import whereami.platform.LocationError;

/** Which whereami error a failed HRESULT means. Same mapping as the Rust and C# ports. */
final class HResults {
    private HResults() {}

    static final int E_ACCESSDENIED = 0x80070005;
    static final int ERROR_TIMEOUT = 0x800705B4; // HRESULT_FROM_WIN32(ERROR_TIMEOUT)

    static LocationError toError(int hr) {
        return switch (hr) {
            case E_ACCESSDENIED -> LocationError.PERMISSION_DENIED;
            case ERROR_TIMEOUT -> LocationError.TIMEOUT;
            default -> LocationError.LOCATION_UNAVAILABLE;
        };
    }
}
