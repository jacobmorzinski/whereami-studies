# whereami behavior contract

Every port implements this. The **strict** part is [`cases.json`](cases.json),
checked by [`conformance/run.ps1`](../conformance/run.ps1). Everything below
that isn't covered by a case is guidance; where a CLI library has its own
convention, the library wins (see *Grammar*).

Derived from `original/whereami/src/main.zig` (upstream 0.4.0, commit `f4bff19`).

## Arguments

| Argument | Meaning |
|---|---|
| `--json` | JSON output |
| `--mock=LAT,LON` | Use these coordinates instead of location services. No reverse geocoding (stays offline). Not range-checked. |
| `--at=LAT,LON` | Use these coordinates and reverse-geocode them. Must be in range: latitude −90..90, longitude −180..180 (inclusive). |
| `--version`, `-v` | Print `whereami 0.4.0 (<os>)` (`windows`, `linux`, `macos`), exit 0 |
| `--help`, `-h` | Usage on stdout, exit 0. Wording is the CLI library's. |
| `completions <shell>` | Reserved word. Ports that implement completions: `bash`, `zsh`, `fish`; missing/unknown shell → exit 2. Ports that don't: stderr `Error: completions are not available in this port`, exit 2. Never treated as an address. |
| positional words | Joined with single spaces into one address and forward-geocoded |

**Coordinates (`LAT,LON`)**: split at the first comma, trim spaces, parse each
part as a finite decimal number (optional sign, fraction, exponent), with `.`
as the decimal point regardless of locale. `nan`, `inf`, `Infinity`, hex
floats and type suffixes are usage errors. A third comma-separated part makes
the second part invalid.

**Grammar**: only the behaviors in `cases.json` are strict. Space-separated
values (`--mock 1,2`), repeated flags, `--`, and whether `--help` beats an
unknown flag follow each port's CLI library. Ports must not hand-roll parsers
to mimic Zig quirks. The `--mock=-33.8,151.2` form must work.

## Exit codes

- **0** success (including `--at` with no address found)
- **1** runtime error
- **2** usage error: unknown flag; bad `--mock`/`--at` value; `--at` out of
  range; address with `--at` or `--mock`; `--at` with `--mock`; empty address;
  `completions` in a port without them. Ports configure their CLI library so
  its parse errors also exit 2.

Usage errors print a message to stderr and **never** print JSON on stdout.

## Human output (stdout)

```
Location: {lat:.4}, {lon:.4}
Accuracy: {accuracy:.0}m
Address: street, city, state, postal_code, country
```

- `Accuracy:` is omitted for `--at` and for address lookups.
- `Address:` appears only when there is an address; empty fields are skipped.

## JSON output (stdout, one line)

```json
{"latitude":40.7128,"longitude":-74.006,"accuracy":0,"address":null}
{"latitude":38.8976635,"longitude":-77.036574,"accuracy":0,"address":{"street":"1600 Pennsylvania Ave NW","city":"Washington","state":"DC","postal_code":"20500","country":"US"}}
```

Key names are exact. Values are compared, not bytes: `0` and `0.0` are equal.

## Runtime errors (exit 1)

Message on stderr. With `--json`, also `{"error":"<key>"}` on stdout.

| Condition | Key | stderr |
|---|---|---|
| Permission denied | `permission_denied` | `Error: location permission denied.` / `Grant location access in your system's privacy/location settings.` |
| Location unavailable | `location_unavailable` | `Error: location unavailable.` / `Make sure location services are enabled.` |
| Timeout (10 s) | `timeout` | `Error: location request timed out.` |
| Location not implemented on this OS yet *(extension)* | `platform_unsupported` | `Error: location is not supported on this platform yet.` |
| Address lookup not available on this OS | `platform_unsupported` | `Error: address lookup is not supported on this platform.` |
| Address not found | `address_not_found` | `Error: couldn't find a location for "<address>".` / `Check the address and your network connection.` |
| Anything else | `unknown_error` | `Error: unexpected error (<name>)` |

`--at` with no address found is not an error: stderr
`Warning: no address found for these coordinates.`, location still printed, exit 0.

## Platform support

| OS | Location | Reverse geocode | Forward geocode |
|---|---|---|---|
| Windows | `Windows.Devices.Geolocation.Geolocator` | none | unsupported |
| Linux | GeoClue2 over system D-Bus | none | unsupported |
| macOS | CoreLocation | `CLGeocoder` | `CLGeocoder` |
