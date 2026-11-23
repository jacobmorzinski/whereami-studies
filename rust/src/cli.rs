//! Command-line parsing: clap does the grammar, we add whereami's rules.

use crate::model::Location;
use clap::{ArgAction, CommandFactory, Parser, error::ErrorKind};
use std::ffi::OsString;

use crate::platform::VERSION;

/// Get your current location using native OS location services,
/// or look up an address or a pair of coordinates.
#[derive(Parser, Debug)]
#[command(
    name = "whereami",
    version = VERSION,
    disable_version_flag = true,
    after_help = "Examples:\n  whereami\n  whereami --at=40.6892,-73.9857\n  \
                  whereami \"1600 Pennsylvania Ave NW, Washington, DC\" --json\n\n\
                  Rust port of whereami by George Mandis: https://github.com/georgemandis/whereami"
)]
struct Args {
    /// Output as JSON
    #[arg(long)]
    json: bool,

    /// Use provided coordinates instead of location services
    #[arg(long, value_name = "LAT,LON", allow_hyphen_values = true, value_parser = parse_coord)]
    mock: Option<(f64, f64)>,

    /// Look up the address at these coordinates (macOS)
    #[arg(long, value_name = "LAT,LON", allow_hyphen_values = true, value_parser = parse_coord)]
    at: Option<(f64, f64)>,

    /// Show version
    #[arg(short = 'v', long, action = ArgAction::Version)]
    version: Option<bool>,

    /// Look up the coordinates of an address (macOS)
    #[arg(value_name = "ADDRESS")]
    address: Vec<String>,
}

/// What the user asked for.
#[derive(Debug, Clone, PartialEq)]
pub enum Mode {
    /// Ask the OS where we are.
    Current,
    /// Pretend we're here; no lookups at all.
    Mock(Location),
    /// Reverse-geocode these coordinates.
    At(Location),
    /// Forward-geocode this address.
    Address(String),
}

#[derive(Debug, Clone, PartialEq)]
pub struct Request {
    pub mode: Mode,
    pub json: bool,
}

/// Parse `LAT,LON`: split at the first comma, trim spaces, and require two
/// finite decimal numbers (`f64::from_str` alone would accept `inf`/`nan`).
pub fn parse_coord(s: &str) -> Result<(f64, f64), String> {
    let (lat, lon) = s
        .split_once(',')
        .ok_or_else(|| "expected LAT,LON (e.g. 40.7128,-74.0060)".to_string())?;
    let number = |part: &str| {
        let part = part.trim_matches(' ');
        part.parse::<f64>()
            .ok()
            .filter(|v| v.is_finite())
            .ok_or_else(|| format!("'{part}' is not a finite number"))
    };
    Ok((number(lat)?, number(lon)?))
}

fn coords((latitude, longitude): (f64, f64)) -> Location {
    Location { latitude, longitude, accuracy: 0.0 }
}

/// Parse a full argument list (including the program name). Errors carry
/// clap's exit code: 0 for --help/--version, 2 for usage errors.
pub fn parse<I, T>(args: I) -> Result<Request, clap::Error>
where
    I: IntoIterator<Item = T>,
    T: Into<OsString> + Clone,
{
    let args = Args::try_parse_from(args)?;
    let fail = |kind, msg: &str| Args::command().error(kind, msg);

    if args.address.first().map(String::as_str) == Some("completions") {
        return Err(fail(ErrorKind::InvalidSubcommand, "completions are not available in this port"));
    }
    if let Some((lat, lon)) = args.at {
        if !(-90.0..=90.0).contains(&lat) || !(-180.0..=180.0).contains(&lon) {
            return Err(fail(
                ErrorKind::ValueValidation,
                "--at coordinates out of range (latitude -90..90, longitude -180..180)",
            ));
        }
    }

    let has_address = !args.address.is_empty();
    let address = args.address.join(" ").trim().to_string();
    if has_address && address.is_empty() {
        return Err(fail(ErrorKind::ValueValidation, "address is empty"));
    }
    if has_address && (args.at.is_some() || args.mock.is_some()) {
        return Err(fail(ErrorKind::ArgumentConflict, "an address can't be combined with --at or --mock"));
    }
    if args.at.is_some() && args.mock.is_some() {
        return Err(fail(ErrorKind::ArgumentConflict, "--at and --mock can't be used together"));
    }

    let mode = match (args.mock, args.at, has_address) {
        (Some(m), _, _) => Mode::Mock(coords(m)),
        (_, Some(a), _) => Mode::At(coords(a)),
        (_, _, true) => Mode::Address(address),
        _ => Mode::Current,
    };
    Ok(Request { mode, json: args.json })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn coord_trims() {
        assert_eq!(parse_coord(" 51.5 , -0.1276").unwrap(), (51.5, -0.1276));
    }

    #[test]
    fn coord_negative() {
        assert_eq!(parse_coord("-33.8688,151.2093").unwrap(), (-33.8688, 151.2093));
    }

    #[test]
    fn coord_rejects() {
        for s in ["40.7", "abc,def", "1,2,3", "nan,0", "0,inf", "0,-Infinity"] {
            assert!(parse_coord(s).is_err(), "{s}");
        }
    }

    #[test]
    fn mock_hyphen_value() {
        let r = parse(["whereami", "--mock=-33.8,151.2"]).unwrap();
        assert!(matches!(r.mode, Mode::Mock(l) if l.latitude == -33.8));
    }

    #[test]
    fn at_boundary_ok() {
        assert!(matches!(parse(["whereami", "--at=90,-180"]).unwrap().mode, Mode::At(_)));
    }

    #[test]
    fn usage_errors_exit_2() {
        let cases: &[&[&str]] = &[
            &["--at=91,0"],
            &["--at=0,-180.5"],
            &["--at=1,2", "--mock=1,2"],
            &["--mock=1,2", "Paris"],
            &["   "],
            &["--bogus"],
            &["completions", "bash"],
            &["--json", "--mock=x"],
        ];
        for a in cases {
            let mut v = vec!["whereami"];
            v.extend(a.iter().copied());
            assert_eq!(parse(v.clone()).unwrap_err().exit_code(), 2, "{v:?}");
        }
    }

    #[test]
    fn address_joined() {
        assert!(matches!(
            parse(["whereami", "350", "5th", "Ave"]).unwrap().mode,
            Mode::Address(ref s) if s == "350 5th Ave"
        ));
    }

    #[test]
    fn json_flag_any_position() {
        assert!(parse(["whereami", "--json", "--mock=1,2"]).unwrap().json);
    }
}
