namespace Whereami

/// A position. Accuracy is in meters; 0 when unknown (e.g. --mock).
type Location = { Latitude: float; Longitude: float; Accuracy: float }

/// A postal address. Fields the OS didn't supply are empty strings.
type Address =
    { Street: string
      City: string
      State: string
      PostalCode: string
      Country: string }

/// What an address lookup finds.
type GeocodeResult = { Location: Location; Address: Address }

/// The ways a platform call can fail. A discriminated union: a value is
/// exactly one of these cases, and `match` must handle every one.
type LocationError =
    | PermissionDenied
    | LocationUnavailable
    | Timeout
    | GeocodingFailed
    | PlatformUnsupported

module LocationError =
    /// The value printed as {"error":"<key>"} with --json.
    let jsonKey =
        function
        | PermissionDenied -> "permission_denied"
        | LocationUnavailable -> "location_unavailable"
        | Timeout -> "timeout"
        | GeocodingFailed -> "unknown_error"
        | PlatformUnsupported -> "platform_unsupported"
