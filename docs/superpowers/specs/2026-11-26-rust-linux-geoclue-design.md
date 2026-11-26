# Rust milestone 4: Linux backend over GeoClue2 (zbus)

A short design (approved 2026-11-26). Extends
[`2026-11-23-whereami-ports-design.md`](2026-11-23-whereami-ports-design.md)
(milestone 4) and follows
[`original/whereami/src/platform/linux.zig`](../../../original/whereami/src/platform/linux.zig).

## Intent

The Rust port gets a real location on Linux by asking GeoClue2 over the system
D-Bus, the same conversation the original has, written the way a Rust
developer would write it. Rust goes first; its shape is the reference for the
other ports' Linux backends.

## Goals / non-goals

- `whereami` on Linux prints the GeoClue position (dev machine: static source,
  42.3958, -71.1473, accuracy 10 m), human and `--json`.
- Errors map as in the original; the whole request is bounded by 10 s.
- Non-goals: geocoding on Linux (stays unsupported, as in the original);
  macOS; any change to Windows behavior.

## Approaches

- **`zbus`, blocking API (picked by the user):** pure Rust, no C library, no
  async runtime for a one-shot CLI request.
- `zbus` async + a runtime: more moving parts for one request.
- `dbus` crate (libdbus bindings): closest to the original but needs
  `libdbus-1-dev` and is less idiomatic.

## Decisions

- `rust/src/platform/linux.rs`, `#[cfg(target_os = "linux")]`; `unsupported`
  remains for other non-Windows OSes (macOS for now). `zbus` is a
  `[target.'cfg(target_os = "linux")'.dependencies]` entry, mirroring how
  `windows` is Windows-only.
- Conversation (as the original): system bus → `Manager.CreateClient` → set
  `DesktopId = "whereami"` and `RequestedAccuracyLevel = 8` (EXACT) →
  subscribe to `LocationUpdated` **on our client's path only** (the original
  matches any client's signal; no visible difference) → `Start` → read
  `Latitude`, `Longitude`, `Accuracy` from each update's new location path;
  after the first update keep listening **250 ms** and return the most
  accurate fix (smallest accuracy; tie → earlier) → `Stop` (best effort).
- **Why the 250 ms window (user decision, 2026-11-26):** live tests showed
  GeoClue sending a coarse BeaconDB/IP fix (±25 km, ~1 ms) and then the static
  source's ±10 m fix ~5 ms later; taking the first signal (as the original
  does) returned the coarse one. With only the IP source, GeoClue sends a
  single update (cold ~0.46 s, warm ~2 ms), so the window costs 250 ms there.
  Recorded as an extension in `spec/behavior.md`.
- Whole request runs under the existing `platform::with_timeout` (10 s total),
  like the Windows backend — unlike the original, whose per-call 5 s limits
  could add past 10 s.
- Errors: no system bus / `CreateClient` fails → `location_unavailable`; any
  D-Bus error whose name contains `AccessDenied` → `permission_denied`; other
  D-Bus failures → `location_unavailable`; no update in time → `timeout`. The
  name → error mapping is a pure function with unit tests.
- Reverse geocode → `None`; geocode → `PlatformUnsupported` (unchanged).
- **Desktop file (user's change):** copy the original's
  `assets/whereami.desktop` to `assets/whereami.desktop` at this repo's root,
  shared by all ports (they all use `DesktopId=whereami`). READMEs say to
  install it to `~/.local/share/applications/`.
- Docs: rust README (platform table, Anatomy section for `linux.rs`, replace
  "What Linux … will need"), `docs/comparison.md` (Linux binding row),
  root README layout row for `assets/`, PROGRESS (rust Linux ✅ after the live
  check, plus the .NET finding below).

## Recorded finding (2026-11-26)

Building `-f net10.0-windows10.0.19041.0` on Linux compiles C#/F# Windows
code: clean code gives 0 errors; a deliberately wrong WinRT member
(`PositionAccuracy.Highest`, `GetGeopositionAsyncX`) is reported with file and
line. The `net10.0` build does not compile the Windows code, so only the
Windows-target build catches such breakage.

## Verification

- Unit tests for the error mapping; existing tests and `bash conformance/run.sh`
  still pass; release build warning-free on Linux.
- Manual live checks, recorded in PROGRESS: (1) location on → the static
  position, human + JSON, exit 0; (2) GNOME location off → expected
  `permission_denied` (record actual); (3) desktop file removed → watch for a
  GNOME prompt, record what happens.

## Left to the builder

zbus version and features, proxy definitions (`#[proxy]` macro or hand-built
`blocking::Proxy`), file-internal structure.
