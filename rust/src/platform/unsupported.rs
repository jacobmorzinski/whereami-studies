//! Stand-in for an OS whose backend isn't written yet.

use super::Platform;
use crate::model::{Address, GeocodeResult, Location, LocationError};
use std::time::Duration;

pub struct Unsupported;

impl Platform for Unsupported {
    fn get_location(&self, _timeout: Duration) -> Result<Location, LocationError> {
        Err(LocationError::PlatformUnsupported)
    }

    fn reverse_geocode(&self, _lat: f64, _lon: f64) -> Option<Address> {
        None
    }

    fn geocode(&self, _query: &str) -> Result<Option<GeocodeResult>, LocationError> {
        Err(LocationError::PlatformUnsupported)
    }
}
