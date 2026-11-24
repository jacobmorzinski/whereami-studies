package whereami.platform;

/** A position. {@code accuracy} is in meters; 0 when unknown (e.g. --mock). */
public record Location(double latitude, double longitude, double accuracy) {}
