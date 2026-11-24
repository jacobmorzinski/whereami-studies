package whereami.platform;

/** The ways a platform call can fail. */
public enum LocationError {
    PERMISSION_DENIED("permission_denied"),
    LOCATION_UNAVAILABLE("location_unavailable"),
    TIMEOUT("timeout"),
    GEOCODING_FAILED("unknown_error"),
    PLATFORM_UNSUPPORTED("platform_unsupported");

    private final String jsonKey;

    LocationError(String jsonKey) {
        this.jsonKey = jsonKey;
    }

    /** The value printed as {@code {"error":"<key>"}} with --json. */
    public String jsonKey() {
        return jsonKey;
    }
}
