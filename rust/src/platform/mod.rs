//! The OS boundary. Each OS gets one implementation of `Platform`;
//! `#[cfg]` picks it at compile time, so other OSes' code isn't even built.

use crate::model::{Address, GeocodeResult, Location, LocationError};
use std::time::Duration;

mod unsupported;

// `concat!` only takes literals, so the version line is spelled out per OS.
#[cfg(target_os = "windows")]
pub const VERSION: &str = "0.4.0 (windows)";
#[cfg(target_os = "macos")]
pub const VERSION: &str = "0.4.0 (macos)";
#[cfg(not(any(target_os = "windows", target_os = "macos")))]
pub const VERSION: &str = "0.4.0 (linux)";

pub trait Platform {
    /// Ask the OS for the current position, waiting at most `timeout`.
    fn get_location(&self, timeout: Duration) -> Result<Location, LocationError>;
    /// Coordinates → address, or `None` if there's no geocoder or no match.
    fn reverse_geocode(&self, lat: f64, lon: f64) -> Option<Address>;
    /// Address → coordinates. `Ok(None)`: no match. `Err(PlatformUnsupported)`: no geocoder.
    fn geocode(&self, query: &str) -> Result<Option<GeocodeResult>, LocationError>;
}

/// The implementation for the OS we were compiled for.
pub fn native() -> Box<dyn Platform> {
    Box::new(unsupported::Unsupported)
}
