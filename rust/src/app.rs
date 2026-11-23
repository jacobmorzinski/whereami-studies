//! What whereami does, given a parsed request and an OS backend.
//! Pure: returns the text and exit code instead of printing, so it's testable
//! with a fake `Platform`.

use crate::cli::{Mode, Request};
use crate::model::{Address, Location, LocationError};
use crate::output;
use crate::platform::Platform;
use std::time::Duration;

const TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Debug, Default, PartialEq)]
pub struct Outcome {
    pub stdout: String,
    pub stderr: String,
    pub code: i32,
}

impl Outcome {
    fn success(req: &Request, loc: &Location, addr: Option<&Address>, show_accuracy: bool) -> Self {
        let stdout = if req.json {
            output::json(loc, addr)
        } else {
            output::human(loc, addr, show_accuracy)
        };
        Outcome { stdout, ..Default::default() }
    }

    fn failure(req: &Request, key: &str, message: String) -> Self {
        Outcome {
            stdout: if req.json { output::json_error(key) } else { String::new() },
            stderr: message,
            code: 1,
        }
    }
}

fn location_error_message(e: LocationError) -> String {
    match e {
        LocationError::PermissionDenied => "Error: location permission denied.\n\
             Grant location access in your system's privacy/location settings.\n"
            .into(),
        LocationError::LocationUnavailable => {
            "Error: location unavailable.\nMake sure location services are enabled.\n".into()
        }
        LocationError::Timeout => "Error: location request timed out.\n".into(),
        LocationError::PlatformUnsupported => {
            "Error: location is not supported on this platform yet.\n".into()
        }
        other => format!("Error: unexpected error ({other:?})\n"),
    }
}

pub fn run(req: &Request, platform: &dyn Platform) -> Outcome {
    let loc = match &req.mode {
        Mode::Address(query) => return lookup_address(req, platform, query),
        Mode::Mock(loc) => return Outcome::success(req, loc, None, true),
        Mode::At(loc) => *loc,
        Mode::Current => match platform.get_location(TIMEOUT) {
            Ok(loc) => loc,
            Err(e) => return Outcome::failure(req, e.json_key(), location_error_message(e)),
        },
    };

    let addr = platform.reverse_geocode(loc.latitude, loc.longitude);
    let is_at = matches!(req.mode, Mode::At(_));
    let mut out = Outcome::success(req, &loc, addr.as_ref(), !is_at);
    // With --at the address is the whole point, so say so when there isn't one.
    if is_at && addr.is_none() {
        out.stderr = "Warning: no address found for these coordinates.\n".into();
    }
    out
}

fn lookup_address(req: &Request, platform: &dyn Platform, query: &str) -> Outcome {
    match platform.geocode(query) {
        Ok(Some(found)) => Outcome::success(req, &found.location, Some(&found.address), false),
        Ok(None) => Outcome::failure(
            req,
            "address_not_found",
            format!(
                "Error: couldn't find a location for \"{query}\".\n\
                 Check the address and your network connection.\n"
            ),
        ),
        Err(LocationError::PlatformUnsupported) => Outcome::failure(
            req,
            "platform_unsupported",
            "Error: address lookup is not supported on this platform.\n".into(),
        ),
        Err(e) => Outcome::failure(req, e.json_key(), location_error_message(e)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cli::{Mode, Request};
    use crate::model::{Address, GeocodeResult, Location, LocationError};
    use crate::platform::Platform;
    use std::time::Duration;

    struct Fake {
        loc: Result<Location, LocationError>,
        addr: Option<Address>,
        geo: Result<Option<GeocodeResult>, LocationError>,
    }

    impl Platform for Fake {
        fn get_location(&self, _: Duration) -> Result<Location, LocationError> {
            self.loc
        }
        fn reverse_geocode(&self, _: f64, _: f64) -> Option<Address> {
            self.addr.clone()
        }
        fn geocode(&self, _: &str) -> Result<Option<GeocodeResult>, LocationError> {
            self.geo.clone()
        }
    }

    fn addr() -> Address {
        Address {
            street: "45 Hoyt St".into(),
            city: "New York".into(),
            state: "NY".into(),
            postal_code: "11201".into(),
            country: "US".into(),
        }
    }

    fn fake() -> Fake {
        Fake {
            loc: Ok(Location { latitude: 47.6062, longitude: -122.3321, accuracy: 12.4 }),
            addr: None,
            geo: Ok(None),
        }
    }

    fn req(mode: Mode, json: bool) -> Request {
        Request { mode, json }
    }

    fn at(lat: f64, lon: f64) -> Location {
        Location { latitude: lat, longitude: lon, accuracy: 0.0 }
    }

    #[test]
    fn current_ok_human() {
        let o = run(&req(Mode::Current, false), &fake());
        assert_eq!(o.stdout, "Location: 47.6062, -122.3321\nAccuracy: 12m\n");
        assert_eq!(o.code, 0);
    }

    #[test]
    fn current_permission_denied_json() {
        let f = Fake { loc: Err(LocationError::PermissionDenied), ..fake() };
        let o = run(&req(Mode::Current, true), &f);
        assert_eq!(o.stdout, "{\"error\":\"permission_denied\"}\n");
        assert!(o.stderr.starts_with("Error: location permission denied."), "{}", o.stderr);
        assert_eq!(o.code, 1);
    }

    #[test]
    fn current_timeout_human() {
        let f = Fake { loc: Err(LocationError::Timeout), ..fake() };
        let o = run(&req(Mode::Current, false), &f);
        assert_eq!(o.stdout, "");
        assert_eq!(o.stderr, "Error: location request timed out.\n");
        assert_eq!(o.code, 1);
    }

    #[test]
    fn current_unsupported() {
        let f = Fake { loc: Err(LocationError::PlatformUnsupported), ..fake() };
        let o = run(&req(Mode::Current, false), &f);
        assert_eq!(o.stderr, "Error: location is not supported on this platform yet.\n");
        assert_eq!(o.code, 1);
    }

    #[test]
    fn mock_skips_reverse_geocode() {
        let f = Fake { addr: Some(addr()), ..fake() };
        let o = run(&req(Mode::Mock(at(1.0, 2.0)), false), &f);
        assert!(!o.stdout.contains("Address:"), "{}", o.stdout);
        assert_eq!(o.code, 0);
    }

    #[test]
    fn at_no_address_warns() {
        let o = run(&req(Mode::At(at(1.0, 2.0)), false), &fake());
        assert_eq!(o.stdout, "Location: 1.0000, 2.0000\n");
        assert_eq!(o.stderr, "Warning: no address found for these coordinates.\n");
        assert_eq!(o.code, 0);
    }

    #[test]
    fn address_not_found_json() {
        let o = run(&req(Mode::Address("Nowhere".into()), true), &fake());
        assert_eq!(o.stdout, "{\"error\":\"address_not_found\"}\n");
        assert!(o.stderr.contains("couldn't find a location for \"Nowhere\""), "{}", o.stderr);
        assert_eq!(o.code, 1);
    }

    #[test]
    fn address_found_no_accuracy() {
        let f = Fake {
            geo: Ok(Some(GeocodeResult { location: at(40.6892, -73.9857), address: addr() })),
            ..fake()
        };
        let o = run(&req(Mode::Address("45 Hoyt St".into()), false), &f);
        assert!(o.stdout.contains("Address: 45 Hoyt St, New York, NY, 11201, US\n"), "{}", o.stdout);
        assert!(!o.stdout.contains("Accuracy:"), "{}", o.stdout);
        assert_eq!(o.code, 0);
    }
}
