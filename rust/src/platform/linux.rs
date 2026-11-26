//! Linux backend: GeoClue2 over the system D-Bus, through `zbus`'s blocking
//! API and its `#[proxy]`-generated typed proxies. Compare with
//! `original/whereami/src/platform/linux.zig`, which drives libdbus by hand.
//! Uses zbus 5 because its blocking API was removed in zbus 6.
//!
//! Extension over the original: GeoClue may send a coarse fix first and a
//! precise one moments later, so after the first fix we listen a little longer
//! and return the most accurate (see `best_fix`).

use super::Platform;
use crate::model::{Address, GeocodeResult, Location, LocationError};
use std::sync::mpsc::{self, Receiver};
use std::time::{Duration, Instant};
use zbus::blocking::Connection;
use zbus::zvariant::OwnedObjectPath;

pub struct Linux;

/// `RequestedAccuracyLevel` for GeoClue's EXACT level.
const ACCURACY_EXACT: u32 = 8;

#[zbus::proxy(
    interface = "org.freedesktop.GeoClue2.Manager",
    default_service = "org.freedesktop.GeoClue2",
    default_path = "/org/freedesktop/GeoClue2/Manager",
    gen_async = false
)]
trait Manager {
    fn create_client(&self) -> zbus::Result<OwnedObjectPath>;
}

#[zbus::proxy(
    interface = "org.freedesktop.GeoClue2.Client",
    default_service = "org.freedesktop.GeoClue2",
    gen_async = false
)]
trait Client {
    fn start(&self) -> zbus::Result<()>;
    fn stop(&self) -> zbus::Result<()>;

    #[zbus(property)]
    fn set_desktop_id(&self, id: &str) -> zbus::Result<()>;
    #[zbus(property)]
    fn set_requested_accuracy_level(&self, level: u32) -> zbus::Result<()>;

    #[zbus(signal)]
    fn location_updated(&self, old: OwnedObjectPath, new: OwnedObjectPath) -> zbus::Result<()>;
}

#[zbus::proxy(
    interface = "org.freedesktop.GeoClue2.Location",
    default_service = "org.freedesktop.GeoClue2",
    gen_async = false
)]
trait GeoLocation {
    #[zbus(property)]
    fn latitude(&self) -> zbus::Result<f64>;
    #[zbus(property)]
    fn longitude(&self) -> zbus::Result<f64>;
    #[zbus(property)]
    fn accuracy(&self) -> zbus::Result<f64>;
}

/// Translate a D-Bus error name into whereami's error kinds.
fn map_dbus_error_name(name: &str) -> LocationError {
    if name.contains("AccessDenied") {
        LocationError::PermissionDenied
    } else {
        LocationError::LocationUnavailable
    }
}

/// A failed D-Bus call: method errors map by name, anything else (no bus,
/// no GeoClue service, malformed reply) means no location is available.
fn map_zbus_error(e: zbus::Error) -> LocationError {
    match e {
        zbus::Error::MethodError(name, _, _) => map_dbus_error_name(name.as_str()),
        _ => LocationError::LocationUnavailable,
    }
}

/// After the first fix, how long to keep listening for a better one. GeoClue
/// sends a coarse IP fix first and the precise fix a few ms later.
const BEST_FIX_WINDOW: Duration = Duration::from_millis(250);

/// Wait for the first fix, then keep listening for `grace` and return the most
/// accurate one seen (smallest `accuracy`; on a tie, the earlier). `None` if
/// the sender goes away before any fix arrives.
fn best_fix(updates: &Receiver<Location>, grace: Duration) -> Option<Location> {
    let mut best = updates.recv().ok()?;
    let deadline = Instant::now() + grace;
    while let Ok(fix) = updates.recv_timeout(deadline.saturating_duration_since(Instant::now())) {
        if fix.accuracy < best.accuracy {
            best = fix;
        }
    }
    Some(best)
}

fn query() -> Result<Location, LocationError> {
    let conn = Connection::system().map_err(map_zbus_error)?;
    let client_path = ManagerProxy::new(&conn)
        .and_then(|manager| manager.create_client())
        .map_err(map_zbus_error)?;

    let client = ClientProxy::builder(&conn)
        .path(client_path)
        .and_then(|builder| builder.build())
        .map_err(map_zbus_error)?;
    client.set_desktop_id("whereami").map_err(map_zbus_error)?;
    client.set_requested_accuracy_level(ACCURACY_EXACT).map_err(map_zbus_error)?;

    // Subscribe before `Start`, so the first fix can't slip past us. The proxy
    // sits on our client's own path, so only our signals arrive.
    let updates = client.receive_location_updated().map_err(map_zbus_error)?;
    let (tx, rx) = mpsc::channel();
    let reader_conn = conn.clone();
    // The iterator blocks without a limit, so it gets its own thread; it ends
    // with the process.
    std::thread::spawn(move || {
        for signal in updates {
            let fix = signal
                .args()
                .map_err(map_zbus_error)
                .and_then(|args| read_location(&reader_conn, args.new));
            if let Ok(fix) = fix {
                if tx.send(fix).is_err() {
                    break;
                }
            }
        }
    });
    client.start().map_err(map_zbus_error)?;

    let result = best_fix(&rx, BEST_FIX_WINDOW).ok_or(LocationError::LocationUnavailable);
    let _ = client.stop(); // best effort
    result
}

fn read_location(conn: &Connection, path: OwnedObjectPath) -> Result<Location, LocationError> {
    let location = GeoLocationProxy::builder(conn)
        .path(path)
        .and_then(|builder| builder.build())
        .map_err(map_zbus_error)?;
    Ok(Location {
        latitude: location.latitude().map_err(map_zbus_error)?,
        longitude: location.longitude().map_err(map_zbus_error)?,
        accuracy: location.accuracy().map_err(map_zbus_error)?,
    })
}

impl Platform for Linux {
    /// The whole conversation gets `timeout` in total: the signal iterator
    /// blocks without a limit, so the query runs under `with_timeout`.
    fn get_location(&self, timeout: Duration) -> Result<Location, LocationError> {
        super::with_timeout(timeout, query)
    }

    /// GeoClue has no geocoder.
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
    fn access_denied() {
        assert_eq!(map_dbus_error_name("org.freedesktop.DBus.Error.AccessDenied"), LocationError::PermissionDenied);
    }

    #[test]
    fn service_unknown() {
        assert_eq!(map_dbus_error_name("org.freedesktop.DBus.Error.ServiceUnknown"), LocationError::LocationUnavailable);
    }

    #[test]
    fn failed() {
        assert_eq!(map_dbus_error_name("org.freedesktop.DBus.Error.Failed"), LocationError::LocationUnavailable);
    }

    #[test]
    fn empty_name() {
        assert_eq!(map_dbus_error_name(""), LocationError::LocationUnavailable);
    }

    fn fix(accuracy: f64) -> Location {
        Location { latitude: accuracy, longitude: 0.0, accuracy }
    }

    #[test]
    fn best_fix_prefers_fine_after_coarse() {
        let (tx, rx) = mpsc::channel();
        tx.send(fix(25000.0)).unwrap();
        tx.send(fix(10.0)).unwrap();
        assert_eq!(best_fix(&rx, Duration::from_millis(20)), Some(fix(10.0)));
    }

    #[test]
    fn best_fix_single_fix_returns_after_grace() {
        let (tx, rx) = mpsc::channel();
        tx.send(fix(5.0)).unwrap();
        let start = Instant::now();
        assert_eq!(best_fix(&rx, Duration::from_millis(50)), Some(fix(5.0)));
        assert!(start.elapsed() < Duration::from_secs(1), "waited {:?}", start.elapsed());
        drop(tx);
    }

    #[test]
    fn best_fix_tie_keeps_first() {
        let (tx, rx) = mpsc::channel();
        let first = Location { latitude: 1.0, longitude: 1.0, accuracy: 10.0 };
        let second = Location { latitude: 2.0, longitude: 2.0, accuracy: 10.0 };
        tx.send(first).unwrap();
        tx.send(second).unwrap();
        assert_eq!(best_fix(&rx, Duration::from_millis(20)), Some(first));
    }

    #[test]
    fn best_fix_ignores_fix_after_window() {
        let (tx, rx) = mpsc::channel();
        tx.send(fix(100.0)).unwrap();
        let late = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(80));
            let _ = tx.send(fix(1.0));
        });
        assert_eq!(best_fix(&rx, Duration::from_millis(50)), Some(fix(100.0)));
        late.join().unwrap();
    }

    #[test]
    fn best_fix_none_when_sender_dropped() {
        let (tx, rx) = mpsc::channel::<Location>();
        drop(tx);
        assert_eq!(best_fix(&rx, Duration::from_millis(20)), None);
    }
}
