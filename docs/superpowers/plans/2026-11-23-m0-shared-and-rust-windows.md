# M0 Shared Groundwork + Rust Port (Windows) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the shared behavior spec, conformance cases and runner, and a Rust `whereami` that passes them and reports live location on Windows.

**Architecture:** `spec/cases.json` is data; `conformance/run.ps1` runs it against any port's binary. The Rust crate splits into model / output / cli / app / platform; `app::run` takes a `&dyn Platform` so all CLI behavior is unit-tested with a fake, and the real Windows backend is a thin `windows`-crate wrapper chosen by `#[cfg(target_os)]`.

**Tech Stack:** PowerShell 7.6, Rust 1.99 (edition 2024), `clap` 4 (derive), `serde` + `serde_json`, `windows` crate (features `Devices_Geolocation`, `Foundation`).

**Spec:** `docs/superpowers/specs/2026-11-23-whereami-ports-design.md`

## Global Constraints

- Version output: `whereami 0.4.0 (<os>)`, `<os>` ∈ `windows`, `linux`, `macos`.
- Usage errors exit **2**; runtime errors exit **1**; success exit 0.
- Human output exact: `Location: {lat:.4}, {lon:.4}`, `Accuracy: {acc:.0}m` (omitted for `--at` and address lookups), `Address: …` (non-empty fields joined by `, `). LF line endings.
- JSON keys exact: `latitude`, `longitude`, `accuracy`, `address` (null or object with `street`, `city`, `state`, `postal_code`, `country`); error object `{"error":"<key>"}`; keys `permission_denied`, `location_unavailable`, `timeout`, `unknown_error`, `platform_unsupported`, `address_not_found`.
- JSON error objects go to stdout only for runtime errors with `--json`; usage errors never print JSON.
- Coordinates: finite decimal, culture-invariant; `nan`/`inf`/`infinity` (any case) are usage errors. `--mock` not range-checked; `--at` must be lat −90..90, lon −180..180 inclusive.
- `completions` is reserved: this port does not implement it → stderr `Error: completions are not available in this port`, exit 2.
- Location timeout: 10 seconds.
- Don't modify `original/`.
- Git: local only, branch per milestone off `main`, merge with `git merge --no-ff` when its checks pass. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

- Negative coordinates in `--mock=-33.8688,151.2093` form must parse (clap treats leading `-` specially) → case `mock-negative`.
- Spaces around the numbers (`--mock= 51.5 , -0.1276`) must be trimmed → case `mock-spaces` + unit test.
- `--at` boundary values `90,-180` accepted, `91,0` / `0,-180.5` rejected → cases `at-boundary`, `at-lat-out`, `at-lon-out`.
- `--json` combined with a usage error prints nothing on stdout → case `json-usage-error-no-stdout`.
- Location services off / permission denied on Windows must give exit 1 with the right key, not a panic → `map_hresult` unit tests in Task 5 + manual check.

---

## File Structure

```
.gitignore                         (exists: original/)
README.md                          exercise overview, upstream URL + commit f4bff19, links
PROGRESS.md                        port × milestone grid
spec/behavior.md                   human-readable contract (from design doc's Behavior spec)
spec/cases.json                    conformance cases
conformance/ports.json             per-port binary path + features
conformance/run.ps1                runner
docs/comparison.md                 side-by-side notes, Rust column first
rust/Cargo.toml
rust/README.md                     Anatomy
rust/src/main.rs                   parse → app::run(native) → print → exit
rust/src/model.rs                  Location, Address, GeocodeResult, LocationError
rust/src/output.rs                 human/json formatting
rust/src/cli.rs                    clap Args → Request
rust/src/app.rs                    run(&Request, &dyn Platform) -> Outcome
rust/src/platform/mod.rs           Platform trait, OS const, native()
rust/src/platform/unsupported.rs   stub used on linux/macos (and windows until Task 5)
rust/src/platform/windows.rs       Task 5
```

---

### Task 1: Shared spec, cases, and conformance runner (branch `m0-shared`)

**Files:**
- Create: `README.md`, `PROGRESS.md`, `spec/behavior.md`, `spec/cases.json`, `conformance/ports.json`, `conformance/run.ps1`, `docs/comparison.md`

**Interfaces:**
- Produces: `cases.json` schema and `run.ps1 [-Port <name>|all]` used by every later task.

`cases.json` is an array of:
```json
{ "name": "mock-human",
  "args": ["--mock=40.7128,-74.0060"],
  "platforms": ["windows","linux","macos"],
  "requires": {"completions": false},
  "exit": 0,
  "stdout": {"exact": "Location: 40.7128, -74.0060\nAccuracy: 0m\n"},
  "stderr_contains": "..." }
```
`platforms` and `requires` optional (default: all / none). `stdout` is one of `{"exact": s}` (`{os}` substituted), `{"json": v}`, `{"empty": true}`, `{"nonempty": true}`, or absent (not checked).

`ports.json`: `{"rust": {"path": "rust/target/release/whereami", "build": "cargo build --release --manifest-path rust/Cargo.toml", "features": {"completions": false}}}`. Runner appends `.exe` on Windows when that file exists.

Runner rules: run from repo root; current OS from `$IsWindows/$IsLinux/$IsMacOS`; skip cases whose `platforms` exclude it or whose `requires` don't match the port's `features`; normalize CRLF→LF before `exact`; `json` compares parsed values recursively with numbers compared as `[double]`; print `PASS name` / `FAIL name: <reason>` per case and `N passed, M failed, K skipped`; exit 1 if any failure or binary missing (`MISSING <port>: build with <build>`).

- [ ] **Step 1: Write `spec/cases.json`** with exactly these cases (all platforms unless noted):

| name | args | exit | stdout | stderr_contains |
|---|---|---|---|---|
| version | `--version` | 0 | exact `whereami 0.4.0 ({os})\n` | |
| version-short | `-v` | 0 | exact `whereami 0.4.0 ({os})\n` | |
| help | `--help` | 0 | nonempty | |
| help-short | `-h` | 0 | nonempty | |
| mock-human | `--mock=40.7128,-74.0060` | 0 | exact `Location: 40.7128, -74.0060\nAccuracy: 0m\n` | |
| mock-json | `--mock=40.7128,-74.0060 --json` | 0 | json `{"latitude":40.7128,"longitude":-74.006,"accuracy":0,"address":null}` | |
| mock-json-first | `--json --mock=40.7128,-74.0060` | 0 | same json | |
| mock-negative | `--mock=-33.8688,151.2093` | 0 | exact `Location: -33.8688, 151.2093\nAccuracy: 0m\n` | |
| mock-spaces | `--mock= 51.5 , -0.1276` (one arg) | 0 | exact `Location: 51.5000, -0.1276\nAccuracy: 0m\n` | |
| mock-unchecked-range | `--mock=200,400` | 0 | exact `Location: 200.0000, 400.0000\nAccuracy: 0m\n` | |
| mock-no-comma | `--mock=40.7128` | 2 | | |
| mock-not-number | `--mock=abc,def` | 2 | | |
| mock-three-parts | `--mock=1,2,3` | 2 | | |
| mock-nan | `--mock=nan,0` | 2 | | |
| mock-inf | `--mock=0,inf` | 2 | | |
| json-usage-error-no-stdout | `--json --mock=x` | 2 | empty | |
| at-lat-out | `--at=91,0` | 2 | | |
| at-lon-out | `--at=0,-180.5` | 2 | | |
| at-boundary (windows,linux) | `--at=90,-180` | 0 | exact `Location: 90.0000, -180.0000\n` | `Warning: no address found for these coordinates.` |
| at-json (windows,linux) | `--at=40.6892,-73.9857 --json` | 0 | json `{"latitude":40.6892,"longitude":-73.9857,"accuracy":0,"address":null}` | |
| at-and-mock | `--at=1,2 --mock=1,2` | 2 | | |
| address-and-mock | `--mock=1,2 Paris` | 2 | | |
| empty-address | `"   "` (one arg) | 2 | | |
| unknown-flag | `--bogus` | 2 | | |
| address-unsupported (windows,linux) | `Paris` | 1 | empty | `address lookup is not supported on this platform` |
| address-unsupported-json (windows,linux) | `Paris --json` | 1 | json `{"error":"platform_unsupported"}` | |
| address-multiword (windows,linux) | `350 5th Ave` (three args) | 1 | | `address lookup is not supported` |
| completions-reserved (requires completions=false) | `completions bash` | 2 | empty | `completions are not available in this port` |

- [ ] **Step 2: Write `conformance/ports.json`** with the `rust` entry above.

- [ ] **Step 3: Write `conformance/run.ps1`** per the runner rules.

- [ ] **Step 4: Verify the runner reports a missing binary**

Run: `pwsh conformance/run.ps1 -Port rust`
Expected: `MISSING rust: build with cargo build --release --manifest-path rust/Cargo.toml`, exit code 1.

- [ ] **Step 5: Write `spec/behavior.md`** (the design doc's "Behavior spec" section plus the grammar/number/completions rules, as a standalone contract; point to `cases.json` as the strict part), `README.md` (purpose, layout, upstream `https://github.com/georgemandis/whereami` @ `f4bff19`, MIT attribution, how to run conformance), `PROGRESS.md` (rows rust, csharp, java, fsharp, clojure; columns `scaffold | conformance (mock) | Windows live | Linux | macOS | Anatomy`; all ⬜; legend ⬜ 🟨 ✅ ➖), `docs/comparison.md` (rows: project file, entry point, arg parsing, JSON, per-OS selection, Windows binding, async wait, error handling; one column per port, empty).

- [ ] **Step 6: Commit and merge**

```powershell
git switch -c m0-shared
git add README.md PROGRESS.md spec conformance docs/comparison.md
git commit -m "Add shared behavior spec, conformance cases, and runner"
git switch main; git merge --no-ff m0-shared
```

---

### Task 2: Rust crate, model, and output formatting (branch `rust-m1-scaffold`)

**Files:**
- Create: `rust/Cargo.toml` (package `whereami`, version `0.4.0`, edition 2024; deps `clap` with `derive`, `serde` with `derive`, `serde_json`; `[target.'cfg(windows)'.dependencies] windows` with features `Devices_Geolocation`, `Foundation`), `rust/src/main.rs` (temporary: `mod model; mod output; fn main() {}`), `rust/src/model.rs`, `rust/src/output.rs`; add `rust/target/` to `.gitignore`.

**Interfaces:**
- Produces:
  - `model::Location { latitude: f64, longitude: f64, accuracy: f64 }` (`Clone, Copy, Debug, PartialEq`)
  - `model::Address { street, city, state, postal_code, country: String }` (`Serialize, Clone, Debug, PartialEq`)
  - `model::GeocodeResult { location: Location, address: Address }`
  - `model::LocationError { PermissionDenied, LocationUnavailable, Timeout, GeocodingFailed, PlatformUnsupported }` with `fn json_key(&self) -> &'static str` (`GeocodingFailed` → `unknown_error`)
  - `output::human(loc: &Location, addr: Option<&Address>, show_accuracy: bool) -> String`
  - `output::json(loc: &Location, addr: Option<&Address>) -> String` (one line + `\n`)
  - `output::json_error(key: &str) -> String` (`{"error":"<key>"}\n`)

- [ ] **Step 1: Write failing tests in `output.rs`**

```rust
#[test] fn human_with_accuracy() {
    let l = Location { latitude: 40.7128, longitude: -74.0060, accuracy: 0.0 };
    assert_eq!(human(&l, None, true), "Location: 40.7128, -74.0060\nAccuracy: 0m\n");
}
#[test] fn human_without_accuracy_with_address_skips_empty() {
    let l = Location { latitude: 38.8977, longitude: -77.0366, accuracy: 5.0 };
    let a = Address { street: "1600 Pennsylvania Ave NW".into(), city: "Washington".into(),
        state: "".into(), postal_code: "20500".into(), country: "US".into() };
    assert_eq!(human(&l, Some(&a), false),
        "Location: 38.8977, -77.0366\nAddress: 1600 Pennsylvania Ave NW, Washington, 20500, US\n");
}
#[test] fn json_null_address() {
    let l = Location { latitude: 40.7128, longitude: -74.006, accuracy: 0.0 };
    let v: serde_json::Value = serde_json::from_str(&json(&l, None)).unwrap();
    assert_eq!(v, serde_json::json!({"latitude":40.7128,"longitude":-74.006,"accuracy":0.0,"address":null}));
}
#[test] fn json_escapes_address() {
    let l = Location { latitude: 1.0, longitude: 2.0, accuracy: 3.0 };
    let a = Address { street: "5 \"Q\" St\\".into(), city: "".into(), state: "".into(), postal_code: "".into(), country: "".into() };
    let v: serde_json::Value = serde_json::from_str(&json(&l, Some(&a))).unwrap();
    assert_eq!(v["address"]["street"], "5 \"Q\" St\\");
}
#[test] fn json_error_shape() { assert_eq!(json_error("timeout"), "{\"error\":\"timeout\"}\n"); }
```

- [ ] **Step 2: Run** `cargo test --manifest-path rust/Cargo.toml` — Expected: FAIL (functions not defined).

- [ ] **Step 3: Implement `model.rs` and `output.rs`.** `json` serializes a private `#[derive(Serialize)]` struct with an `Option<&Address>` field so `None` becomes `null`.

- [ ] **Step 4: Run** `cargo test --manifest-path rust/Cargo.toml` — Expected: 5 passed.

- [ ] **Step 5: Commit**

```powershell
git switch -c rust-m1-scaffold
git add .gitignore rust
git commit -m "rust: add crate, model, and output formatting"
```

---

### Task 3: Rust argument parsing

**Files:**
- Create: `rust/src/cli.rs`; Modify: `rust/src/main.rs` (add `mod cli;`)

**Interfaces:**
- Consumes: `model::Location`.
- Produces:
  - `cli::parse_coord(s: &str) -> Result<(f64, f64), String>` — split at first `,`, trim spaces, `f64::from_str` each, reject non-finite.
  - `cli::Mode { Current, Mock(Location), At(Location), Address(String) }` (accuracy 0 for Mock/At)
  - `cli::Request { mode: Mode, json: bool }`
  - `cli::parse<I, T>(args: I) -> Result<Request, clap::Error> where I: IntoIterator<Item = T>, T: Into<OsString> + Clone` (args include program name)

clap setup: `#[command(name = "whereami", version = VERSION, disable_version_flag = true)]` where `VERSION = concat!("0.4.0 (", <os>, ")")` from `platform::OS` (define a local `cfg`-selected const here if `platform` doesn't exist yet; Task 4 moves it); explicit `-v/--version` arg with `ArgAction::Version`; `--mock`/`--at` as `Option<String>` with `allow_hyphen_values = true`; `address: Vec<String>` trailing positional. After `try_get_matches_from`, semantic checks build errors with `Command::error(ErrorKind::…, msg)` so they exit 2: bad coord (`ValueValidation`), `--at` range, at+mock, address+at/mock (`ArgumentConflict`), empty address after joining with `' '` and trimming, first positional `completions` → message `completions are not available in this port`.

- [ ] **Step 1: Write failing tests in `cli.rs`**

```rust
#[test] fn coord_trims() { assert_eq!(parse_coord(" 51.5 , -0.1276").unwrap(), (51.5, -0.1276)); }
#[test] fn coord_negative() { assert_eq!(parse_coord("-33.8688,151.2093").unwrap(), (-33.8688, 151.2093)); }
#[test] fn coord_rejects() { for s in ["40.7", "abc,def", "1,2,3", "nan,0", "0,inf", "0,-Infinity"] { assert!(parse_coord(s).is_err(), "{s}"); } }
#[test] fn mock_hyphen_value() {
    let r = parse(["whereami", "--mock=-33.8,151.2"]).unwrap();
    assert!(matches!(r.mode, Mode::Mock(l) if l.latitude == -33.8));
}
#[test] fn at_boundary_ok() { assert!(matches!(parse(["whereami","--at=90,-180"]).unwrap().mode, Mode::At(_))); }
#[test] fn usage_errors_exit_2() {
    for a in [vec!["--at=91,0"], vec!["--at=0,-180.5"], vec!["--at=1,2","--mock=1,2"], vec!["--mock=1,2","Paris"],
              vec!["   "], vec!["--bogus"], vec!["completions","bash"], vec!["--json","--mock=x"]] {
        let mut v = vec!["whereami"]; v.extend(a.iter().copied());
        assert_eq!(parse(v.clone()).unwrap_err().exit_code(), 2, "{v:?}");
    }
}
#[test] fn address_joined() {
    assert!(matches!(parse(["whereami","350","5th","Ave"]).unwrap().mode, Mode::Address(ref s) if s == "350 5th Ave"));
}
#[test] fn json_flag_any_position() { assert!(parse(["whereami","--json","--mock=1,2"]).unwrap().json); }
```

- [ ] **Step 2: Run** `cargo test --manifest-path rust/Cargo.toml cli` — Expected: FAIL.
- [ ] **Step 3: Implement `cli.rs`** as described.
- [ ] **Step 4: Run** `cargo test --manifest-path rust/Cargo.toml` — Expected: all pass.
- [ ] **Step 5: Commit** `git commit -am "rust: parse arguments with clap"` (add `rust/src/cli.rs` first).

---

### Task 4: Rust app logic, platform trait, stub, and main — conformance passes

**Files:**
- Create: `rust/src/app.rs`, `rust/src/platform/mod.rs`, `rust/src/platform/unsupported.rs`; Modify: `rust/src/main.rs`, `rust/src/cli.rs` (use `platform::OS`)

**Interfaces:**
- Consumes: `cli::parse`, `cli::Request`, `output::*`, `model::*`.
- Produces:
  - `platform::OS: &str` (`"windows"|"linux"|"macos"` via `cfg`)
  - `trait Platform { fn get_location(&self, timeout: Duration) -> Result<Location, LocationError>; fn reverse_geocode(&self, lat: f64, lon: f64) -> Option<Address>; fn geocode(&self, query: &str) -> Result<Option<GeocodeResult>, LocationError>; }`
  - `platform::native() -> Box<dyn Platform>` (returns `Unsupported` everywhere for now; Task 5 switches Windows)
  - `unsupported::Unsupported` — `get_location` → `Err(PlatformUnsupported)`, `reverse_geocode` → `None`, `geocode` → `Err(PlatformUnsupported)`
  - `app::Outcome { stdout: String, stderr: String, code: i32 }`, `app::run(req: &Request, p: &dyn Platform) -> Outcome`

`run` behavior (mirror `original/whereami/src/main.zig:342-404`): Address → geocode: `Err(PlatformUnsupported)` → stderr `Error: address lookup is not supported on this platform.\n`, json key `platform_unsupported`, code 1; `Ok(None)` → stderr `Error: couldn't find a location for "<q>".\nCheck the address and your network connection.\n`, key `address_not_found`, code 1; `Ok(Some(r))` → output with `show_accuracy=false`; other `Err(e)` → treat like location errors. Current → `get_location(10s)`; errors → stderr text by kind (exact strings from `main.zig:127-138`; `PlatformUnsupported` → `Error: location is not supported on this platform yet.\n`; `GeocodingFailed` → `Error: unexpected error (GeocodingFailed)\n`), plus `json_error(e.json_key())` when json, code 1. Mock → no reverse geocode. At/Current → `reverse_geocode`; At with `None` → stderr `Warning: no address found for these coordinates.\n`. `show_accuracy` false only for At.

main: `cli::parse(std::env::args_os())` → on `Err(e)` call `e.exit()`; else `run(&req, &*platform::native())`, write stdout/stderr, `std::process::exit(code)`.

- [ ] **Step 1: Write failing tests in `app.rs`** with a `Fake { loc: Result<Location, LocationError>, addr: Option<Address>, geo: Result<Option<GeocodeResult>, LocationError> }` implementing `Platform`:
  - `current_ok_human`: loc 47.6062,-122.3321 acc 12.4, no addr → stdout `"Location: 47.6062, -122.3321\nAccuracy: 12m\n"`, code 0.
  - `current_permission_denied_json`: → stdout `{"error":"permission_denied"}\n`, stderr starts with `Error: location permission denied.`, code 1.
  - `current_timeout_human`: → stdout empty, stderr `"Error: location request timed out.\n"`, code 1.
  - `current_unsupported`: → stderr `"Error: location is not supported on this platform yet.\n"`, code 1.
  - `mock_skips_reverse_geocode`: Fake addr Some(..), Mode::Mock → stdout has no `Address:` line.
  - `at_no_address_warns`: → stdout `"Location: 1.0000, 2.0000\n"`, stderr `"Warning: no address found for these coordinates.\n"`, code 0.
  - `address_not_found_json`: geo Ok(None) → stdout `{"error":"address_not_found"}\n`, stderr contains `couldn't find a location for "Nowhere"`, code 1.
  - `address_found_no_accuracy`: geo Ok(Some(..)) → stdout has `Address:` line and no `Accuracy:` line.

- [ ] **Step 2: Run** `cargo test --manifest-path rust/Cargo.toml app` — Expected: FAIL.
- [ ] **Step 3: Implement** `platform/mod.rs`, `unsupported.rs`, `app.rs`, `main.rs`.
- [ ] **Step 4: Run** `cargo test --manifest-path rust/Cargo.toml` — Expected: all pass.
- [ ] **Step 5: Run conformance**

```powershell
cargo build --release --manifest-path rust/Cargo.toml
pwsh conformance/run.ps1 -Port rust
```
Expected: `28 passed, 0 failed, 0 skipped` on Windows.

- [ ] **Step 6: Update `PROGRESS.md`** rust: scaffold ✅, conformance (mock) ✅. Commit, merge:

```powershell
git add rust PROGRESS.md
git commit -m "rust: app logic, platform trait, stubs; passes conformance"
git switch main; git merge --no-ff rust-m1-scaffold
```

---

### Task 5: Rust Windows backend (branch `rust-m2-windows`)

**Files:**
- Create: `rust/src/platform/windows.rs`; Modify: `rust/src/platform/mod.rs` (`#[cfg(windows)] mod windows;` and `native()` returns `Windows` on Windows)

**Interfaces:**
- Consumes: `Platform` trait, `LocationError`.
- Produces: `windows::Windows` implementing `Platform`; `fn map_hresult(code: i32) -> LocationError`.

Behavior (mirror `original/whereami/src/platform/windows.zig`): `Geolocator::RequestAccessAsync()?.get()?`; `Denied` → `PermissionDenied`, `Unspecified` → `LocationUnavailable`. Then `Geolocator::new()`, `SetDesiredAccuracy(PositionAccuracy::High)`, `GetGeopositionAsyncWithAgeAndTimeout(TimeSpan::from(Duration::ZERO), TimeSpan::from(timeout))?.get()`, then `Coordinate()` → `Latitude()`, `Longitude()`, `Accuracy()`. Any `windows::core::Error` → `map_hresult(err.code().0)`. `reverse_geocode` → `None`; `geocode` → `Err(PlatformUnsupported)`.

`map_hresult`: `0x80070005` (E_ACCESSDENIED) → `PermissionDenied`; `0x800705B4` (ERROR_TIMEOUT) → `Timeout`; anything else → `LocationUnavailable`.

- [ ] **Step 1: Write failing tests in `windows.rs`**

```rust
#[test] fn hresult_access_denied() { assert_eq!(map_hresult(0x80070005u32 as i32), LocationError::PermissionDenied); }
#[test] fn hresult_timeout() { assert_eq!(map_hresult(0x800705B4u32 as i32), LocationError::Timeout); }
#[test] fn hresult_other() { assert_eq!(map_hresult(0x80004005u32 as i32), LocationError::LocationUnavailable); }
```
(`LocationError` needs `PartialEq, Debug` — add the derive in `model.rs` if missing.)

- [ ] **Step 2: Run** `cargo test --manifest-path rust/Cargo.toml windows` — Expected: FAIL.
- [ ] **Step 3: Implement `windows.rs`** and switch `native()`.
- [ ] **Step 4: Run** `cargo test --manifest-path rust/Cargo.toml` then rebuild and `pwsh conformance/run.ps1 -Port rust` — Expected: all pass (no conformance case calls live location).
- [ ] **Step 5: Manual live check** (human runs; the agent can't grant location permission): with Windows location services and "Let desktop apps access your location" on, `rust/target/release/whereami.exe` prints `Location:` and `Accuracy:` lines, exit 0; `--json` prints a JSON object with `"address":null`. With "Let desktop apps access your location" off: exit 1, stderr `Error: location permission denied.` (if it reports `location unavailable` instead, record the actual HRESULT and add it to `map_hresult` with a test). Record the result in `PROGRESS.md` (Windows live ✅) with the date.
- [ ] **Step 6: Commit and merge**

```powershell
git add rust PROGRESS.md
git commit -m "rust: Windows location via windows crate"
git switch main; git merge --no-ff rust-m2-windows
```

---

### Task 6: Rust Anatomy and comparison (branch `rust-m3-docs`)

**Files:**
- Create: `rust/README.md`; Modify: `docs/comparison.md`, `PROGRESS.md`

- [ ] **Step 1: Write `rust/README.md`**: build/run/test commands; **Anatomy**, a guided tour in reading order: `Cargo.toml` (deps, target-specific `windows` dependency and why its features are opt-in) → `main.rs` → `cli.rs` (clap derive, why `allow_hyphen_values`, how semantic errors reuse clap's exit 2) → `app.rs` (pure logic, `Outcome`, testability via `&dyn Platform`) → `platform/mod.rs` (`#[cfg(target_os)]` selection) → `platform/windows.rs` (WinRT projection: what `Geolocator::new()` and `.get()` do for you, compared with the hand-written vtables in `original/whereami/src/platform/windows.zig`). Note what Linux (`zbus`) and macOS (`objc2-core-location`) will need.
- [ ] **Step 2: Fill the Rust column of `docs/comparison.md`** (one short cell per row, file links).
- [ ] **Step 3: Verify** every file path linked in both docs exists: `Get-ChildItem rust/src -Recurse`.
- [ ] **Step 4: Update `PROGRESS.md`** rust: Anatomy ✅; Linux ⬜, macOS ⬜. Commit, merge:

```powershell
git add rust/README.md docs/comparison.md PROGRESS.md
git commit -m "rust: Anatomy walkthrough and comparison column"
git switch main; git merge --no-ff rust-m3-docs
```
