//! Turning results into the text whereami prints.

use crate::model::{Address, Location};
use serde::Serialize;

/// Human-readable output. `show_accuracy` is false for looked-up places,
/// which have no position fix.
pub fn human(loc: &Location, addr: Option<&Address>, show_accuracy: bool) -> String {
    let mut s = format!("Location: {:.4}, {:.4}\n", loc.latitude, loc.longitude);
    if show_accuracy {
        s += &format!("Accuracy: {:.0}m\n", loc.accuracy);
    }
    if let Some(a) = addr {
        let parts: Vec<&str> = [&a.street, &a.city, &a.state, &a.postal_code, &a.country]
            .into_iter()
            .map(String::as_str)
            .filter(|f| !f.is_empty())
            .collect();
        s += &format!("Address: {}\n", parts.join(", "));
    }
    s
}

#[derive(Serialize)]
struct JsonOutput<'a> {
    latitude: f64,
    longitude: f64,
    accuracy: f64,
    address: Option<&'a Address>,
}

/// One line of JSON; `address` is `null` when there is none.
pub fn json(loc: &Location, addr: Option<&Address>) -> String {
    let out = JsonOutput {
        latitude: loc.latitude,
        longitude: loc.longitude,
        accuracy: loc.accuracy,
        address: addr,
    };
    serde_json::to_string(&out).expect("plain struct always serializes") + "\n"
}

/// `{"error":"<key>"}` for runtime errors in `--json` mode.
pub fn json_error(key: &str) -> String {
    serde_json::json!({ "error": key }).to_string() + "\n"
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{Address, Location};

    #[test]
    fn human_with_accuracy() {
        let l = Location { latitude: 40.7128, longitude: -74.0060, accuracy: 0.0 };
        assert_eq!(human(&l, None, true), "Location: 40.7128, -74.0060\nAccuracy: 0m\n");
    }

    #[test]
    fn human_without_accuracy_with_address_skips_empty() {
        let l = Location { latitude: 38.8977, longitude: -77.0366, accuracy: 5.0 };
        let a = Address {
            street: "1600 Pennsylvania Ave NW".into(),
            city: "Washington".into(),
            state: "".into(),
            postal_code: "20500".into(),
            country: "US".into(),
        };
        assert_eq!(
            human(&l, Some(&a), false),
            "Location: 38.8977, -77.0366\nAddress: 1600 Pennsylvania Ave NW, Washington, 20500, US\n"
        );
    }

    #[test]
    fn json_null_address() {
        let l = Location { latitude: 40.7128, longitude: -74.006, accuracy: 0.0 };
        let v: serde_json::Value = serde_json::from_str(&json(&l, None)).unwrap();
        assert_eq!(
            v,
            serde_json::json!({"latitude":40.7128,"longitude":-74.006,"accuracy":0.0,"address":null})
        );
    }

    #[test]
    fn json_escapes_address() {
        let l = Location { latitude: 1.0, longitude: 2.0, accuracy: 3.0 };
        let a = Address {
            street: "5 \"Q\" St\\".into(),
            city: "".into(),
            state: "".into(),
            postal_code: "".into(),
            country: "".into(),
        };
        let v: serde_json::Value = serde_json::from_str(&json(&l, Some(&a))).unwrap();
        assert_eq!(v["address"]["street"], "5 \"Q\" St\\");
    }

    #[test]
    fn json_error_shape() {
        assert_eq!(json_error("timeout"), "{\"error\":\"timeout\"}\n");
    }
}
