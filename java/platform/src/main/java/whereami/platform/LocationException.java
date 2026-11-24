package whereami.platform;

/**
 * An expected failure from a platform call. It's a checked exception, so every
 * signature that can fail says {@code throws LocationException} and callers
 * must handle it. That's Java's closest match to Rust's {@code Result}.
 */
public final class LocationException extends Exception {
    private final LocationError error;

    public LocationException(LocationError error) {
        super(error.jsonKey());
        this.error = error;
    }

    public LocationError error() {
        return error;
    }
}
