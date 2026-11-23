//! The OS boundary. Each OS gets one implementation of `Platform`;
//! `#[cfg]` picks it at compile time, so other OSes' code isn't even built.

use crate::model::{Address, GeocodeResult, Location, LocationError};
use std::time::Duration;

#[cfg(not(target_os = "windows"))]
mod unsupported;
#[cfg(target_os = "windows")]
mod windows;

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

/// Run a blocking OS query on a worker thread and give up after `timeout`.
///
/// OS calls like `.join()` on a WinRT async operation can block with no limit
/// of their own. If time runs out we return `Timeout` and leave the worker
/// behind; the process exits right after, which ends it.
#[allow(dead_code)] // used by the Windows backend only
pub(crate) fn with_timeout<F>(timeout: Duration, query: F) -> Result<Location, LocationError>
where
    F: FnOnce() -> Result<Location, LocationError> + Send + 'static,
{
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        // The receiver may be gone if we already timed out; that's fine.
        let _ = tx.send(query());
    });
    rx.recv_timeout(timeout).unwrap_or(Err(LocationError::Timeout))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn with_timeout_returns_result_when_fast() {
        let r = with_timeout(Duration::from_secs(5), || Ok(Location { latitude: 1.0, longitude: 2.0, accuracy: 3.0 }));
        assert_eq!(r, Ok(Location { latitude: 1.0, longitude: 2.0, accuracy: 3.0 }));
    }

    #[test]
    fn with_timeout_passes_errors_through() {
        assert_eq!(with_timeout(Duration::from_secs(5), || Err(LocationError::PermissionDenied)), Err(LocationError::PermissionDenied));
    }

    #[test]
    fn with_timeout_gives_up_when_slow() {
        let start = std::time::Instant::now();
        let r = with_timeout(Duration::from_millis(50), || {
            std::thread::sleep(Duration::from_secs(2));
            Ok(Location { latitude: 0.0, longitude: 0.0, accuracy: 0.0 })
        });
        assert_eq!(r, Err(LocationError::Timeout));
        assert!(start.elapsed() < Duration::from_secs(1), "waited {:?}", start.elapsed());
    }
}

/// The implementation for the OS we were compiled for.
pub fn native() -> Box<dyn Platform> {
    #[cfg(target_os = "windows")]
    return Box::new(windows::Windows);
    #[cfg(not(target_os = "windows"))]
    return Box::new(unsupported::Unsupported);
}
