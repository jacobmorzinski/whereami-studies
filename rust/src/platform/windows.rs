//! Windows backend: the WinRT `Windows.Devices.Geolocation.Geolocator` class,
//! through the `windows` crate's generated bindings. Compare with
//! `original/whereami/src/platform/windows.zig`, which declares the same COM
//! vtables, GUIDs and async polling by hand.

use super::Platform;
use crate::model::{Address, GeocodeResult, Location, LocationError};
use std::time::Duration;
use windows::Devices::Geolocation::{GeolocationAccessStatus, Geolocator, PositionAccuracy};
use windows::Foundation::TimeSpan;

pub struct Windows;

const E_ACCESSDENIED: u32 = 0x8007_0005;
/// HRESULT_FROM_WIN32(ERROR_TIMEOUT): what the timeout overload fails with.
const HRESULT_TIMEOUT: u32 = 0x8007_05B4;

/// Translate a failed WinRT call's HRESULT into whereami's error kinds.
fn map_hresult(code: i32) -> LocationError {
    match code as u32 {
        E_ACCESSDENIED => LocationError::PermissionDenied,
        HRESULT_TIMEOUT => LocationError::Timeout,
        _ => LocationError::LocationUnavailable,
    }
}

fn query(timeout: Duration) -> windows::core::Result<Result<Location, LocationError>> {
    // Each WinRT async call returns an IAsyncOperation; `.join()` blocks
    // this thread until it completes (the Zig version polls GetStatus).
    if Geolocator::RequestAccessAsync()?.join()? != GeolocationAccessStatus::Allowed {
        return Ok(Err(LocationError::PermissionDenied));
    }

    let geolocator = Geolocator::new()?;
    geolocator.SetDesiredAccuracy(PositionAccuracy::High)?;
    let position = geolocator
        .GetGeopositionAsyncWithAgeAndTimeout(TimeSpan::from(Duration::ZERO), TimeSpan::from(timeout))?
        .join()?;

    let coordinate = position.Coordinate()?;
    Ok(Ok(Location {
        latitude: coordinate.Latitude()?,
        longitude: coordinate.Longitude()?,
        accuracy: coordinate.Accuracy()?,
    }))
}

impl Platform for Windows {
    fn get_location(&self, timeout: Duration) -> Result<Location, LocationError> {
        query(timeout).unwrap_or_else(|e| Err(map_hresult(e.code().0)))
    }

    /// Windows has no built-in geocoder (MapLocationFinder needs a Bing Maps key).
    fn reverse_geocode(&self, _lat: f64, _lon: f64) -> Option<Address> {
        None
    }

    fn geocode(&self, _query: &str) -> Result<Option<GeocodeResult>, LocationError> {
        Err(LocationError::PlatformUnsupported)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hresult_access_denied() {
        assert_eq!(map_hresult(0x80070005u32 as i32), LocationError::PermissionDenied);
    }

    #[test]
    fn hresult_timeout() {
        assert_eq!(map_hresult(0x800705B4u32 as i32), LocationError::Timeout);
    }

    #[test]
    fn hresult_other() {
        assert_eq!(map_hresult(0x80004005u32 as i32), LocationError::LocationUnavailable);
    }
}
