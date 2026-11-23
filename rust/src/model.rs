//! The data whereami works with, independent of OS and output format.

use serde::Serialize;

/// A position fix. `accuracy` is in meters; 0 for coordinates the user gave us.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Location {
    pub latitude: f64,
    pub longitude: f64,
    pub accuracy: f64,
}

/// A postal address. Fields the geocoder didn't supply are empty strings.
#[derive(Serialize, Clone, Debug, PartialEq)]
pub struct Address {
    pub street: String,
    pub city: String,
    pub state: String,
    pub postal_code: String,
    pub country: String,
}

/// A forward-geocoded address: where it is, plus its normalized components.
#[derive(Clone, Debug, PartialEq)]
pub struct GeocodeResult {
    pub location: Location,
    pub address: Address,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LocationError {
    PermissionDenied,
    LocationUnavailable,
    Timeout,
    #[allow(dead_code)] // produced by the macOS geocoder, not written yet
    GeocodingFailed,
    PlatformUnsupported,
}

impl LocationError {
    /// The value of `"error"` in `--json` output.
    pub fn json_key(&self) -> &'static str {
        match self {
            LocationError::PermissionDenied => "permission_denied",
            LocationError::LocationUnavailable => "location_unavailable",
            LocationError::Timeout => "timeout",
            LocationError::PlatformUnsupported => "platform_unsupported",
            LocationError::GeocodingFailed => "unknown_error",
        }
    }
}
