namespace Whereami;

/// A position fix. Accuracy is in meters; 0 for coordinates the user gave us.
public readonly record struct Location(double Latitude, double Longitude, double Accuracy);

/// A postal address. Fields the geocoder didn't supply are empty strings.
public sealed record Address(string Street, string City, string State, string PostalCode, string Country);

/// A forward-geocoded address: where it is, plus its normalized components.
public sealed record GeocodeResult(Location Location, Address Address);

public enum LocationError
{
    PermissionDenied,
    LocationUnavailable,
    Timeout,
    GeocodingFailed,
    PlatformUnsupported,
}

public static class LocationErrorExtensions
{
    /// The value of "error" in --json output.
    public static string JsonKey(this LocationError e) => e switch
    {
        LocationError.PermissionDenied => "permission_denied",
        LocationError.LocationUnavailable => "location_unavailable",
        LocationError.Timeout => "timeout",
        LocationError.PlatformUnsupported => "platform_unsupported",
        _ => "unknown_error",
    };
}

/// How platforms report failure. C# signals errors with exceptions where
/// Rust returns a Result; the kind travels in <see cref="Error"/>.
public sealed class LocationException(LocationError error) : Exception(error.ToString())
{
    public LocationError Error { get; } = error;
}
