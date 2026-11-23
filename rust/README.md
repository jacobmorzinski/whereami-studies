# whereami — Rust

A Rust port of [whereami](https://github.com/georgemandis/whereami). It asks
the operating system's own location service where you are. Behavior is defined
by [`spec/behavior.md`](../spec/behavior.md).

| | |
|---|---|
| Windows | ✅ `Windows.Devices.Geolocation` via the [`windows`](https://crates.io/crates/windows) crate |
| Linux | stub (planned: GeoClue2 via [`zbus`](https://crates.io/crates/zbus)) |
| macOS | stub (planned: CoreLocation via [`objc2-core-location`](https://crates.io/crates/objc2-core-location)) |

## Build, run, test

```powershell
cargo build --release --manifest-path rust/Cargo.toml
rust/target/release/whereami.exe --json
cargo test --manifest-path rust/Cargo.toml
pwsh conformance/run.ps1 -Port rust
```

On Windows, live location needs *Settings → Privacy & security → Location*
with *Location services* and *Let desktop apps access your location* on.

## Anatomy

Read the files in this order. Each one does a single job.

### 1. [`Cargo.toml`](Cargo.toml): the project file

Cargo is Rust's build tool and package manager in one. `[package]` names the
binary (`whereami`) and pins the language edition (2024). `[dependencies]`
lists crates from crates.io:

- `clap` (with the `derive` feature): command-line parsing generated from a struct.
- `serde` + `serde_json`: serialization. `#[derive(Serialize)]` on a struct is all it takes.

Then:

```toml
[target.'cfg(windows)'.dependencies]
windows = { version = "0.62", features = ["Devices_Geolocation", "Foundation"] }
```

This dependency exists **only when compiling for Windows**, so a Linux build
never downloads it. The `windows` crate is Microsoft's generated binding to
*all* Windows APIs. That's far too much to compile, so each API namespace is
behind a feature flag. You turn on just the ones you use:
`Windows.Devices.Geolocation` → `Devices_Geolocation`.

`Cargo.lock` (committed) records the exact versions resolved, so builds are
reproducible.

### 2. [`src/main.rs`](src/main.rs): the entry point

`mod app; mod cli; …` declares the modules (one file each). `main` is just
the wiring:

1. `cli::parse(std::env::args_os())`: on `--help`/`--version` or a usage
   error, clap's error type knows how to print itself and which exit code to
   use (`e.exit()`: 0 or 2).
2. `app::run(&request, &*platform::native())`: does the work and *returns*
   the output text and exit code instead of printing.
3. Print, flush, `std::process::exit(code)`.

Rust has no exceptions. Failure is a value (`Result<T, E>`), and
`unwrap_or_else` says what to do with the error case.

### 3. [`src/model.rs`](src/model.rs): the data

Plain structs (`Location`, `Address`, `GeocodeResult`) and an enum of the
ways things fail (`LocationError`). `#[derive(...)]` asks the compiler to
generate code: `Debug` for `{:?}` printing, `PartialEq` for `==` in tests,
`Clone`/`Copy` for duplication, and `Serialize` for JSON.

### 4. [`src/cli.rs`](src/cli.rs): argument parsing

The `Args` struct *is* the CLI definition. clap's derive macro reads the
field names, types and `///` doc comments and generates the parser and the
`--help` text. A few things to notice:

- `Option<(f64, f64)>` with `value_parser = parse_coord`: clap calls our
  function to turn `"40.7,-74.0"` into a tuple. If it returns `Err`, clap
  reports a usage error, exit 2.
- `allow_hyphen_values = true`: without it, `--mock -33.8,151.2` would read
  `-33.8,151.2` as another flag.
- `parse_coord` checks `is_finite()` because Rust's `f64` parser accepts `inf` and `NaN`.
- Rules clap can't express (`--at` range, `--at` with `--mock`, reserved
  `completions`) are checked after parsing. They report through
  `Args::command().error(...)`, so they look and exit exactly like clap's own errors.

The result is a `Request { mode, json }`. `Mode` is an enum with data
attached (`Mock(Location)`, `Address(String)`, …). That's Rust's way of
saying "exactly one of these, and the data that goes with it."

### 5. [`src/output.rs`](src/output.rs): formatting

`format!("{:.4}", x)` gives fixed decimals for the human output. For JSON, a
private `JsonOutput` struct with `address: Option<&Address>` lets serde write
`null` for `None`, with no string building by hand.

### 6. [`src/app.rs`](src/app.rs): the logic

`run(req, platform) -> Outcome` is the whole program's behavior: which mode,
what to print, which exit code. It takes `&dyn Platform`, a reference to
*any* type implementing the `Platform` trait, so the tests at the bottom of the
file pass a `Fake` that returns whatever each test needs. That's how the
error paths (permission denied, timeout, address not found) are tested
without touching the OS.

`match` on `req.mode` must handle every variant; the compiler rejects a
missing case.

### 7. [`src/platform/mod.rs`](src/platform/mod.rs): choosing the OS

The `Platform` trait (an interface) has three methods. Then:

```rust
#[cfg(target_os = "windows")]
mod windows;
```

`#[cfg(...)]` is conditional compilation. On Linux the `windows` module isn't
just unused; it doesn't exist. `native()` returns the right implementation
the same way. Picking the OS costs nothing at runtime, and code for other
OSes can't break your build. `VERSION` is spelled out per OS because
`concat!` only takes literals.

### 8. [`src/platform/windows.rs`](src/platform/windows.rs): calling the OS

This is the part the exercise is about. Here's the whole location request:

```rust
if Geolocator::RequestAccessAsync()?.join()? != GeolocationAccessStatus::Allowed { … }
let geolocator = Geolocator::new()?;
geolocator.SetDesiredAccuracy(PositionAccuracy::High)?;
let position = geolocator.GetGeopositionAsyncWithAgeAndTimeout(…)?.join()?;
let coordinate = position.Coordinate()?;
```

Compare this with [`original/…/windows.zig`](../original/whereami/src/platform/windows.zig),
which does the same thing in about 475 lines. The `windows` crate hides all of
the following:

| Zig does by hand | `windows` crate |
|---|---|
| Declares interface GUIDs (`IID_IGeolocator`, …) | generated from Windows metadata |
| Declares vtable structs, calls `vtable.GetGeopositionAsync(self, &out)` | `geolocator.GetGeopositionAsync()` |
| `RoInitialize`, `WindowsCreateString`, `RoActivateInstance` | `Geolocator::new()` |
| `QueryInterface` + `Release` on every object | automatic (`Drop`) |
| Polls `IAsyncInfo::GetStatus` in a loop, giving up after 10 s | `.join()` blocks until done; `platform::with_timeout` runs it on a worker thread and stops waiting after 10 s |
| Checks every `HRESULT` | `?` turns a failing HRESULT into `Err(windows::core::Error)` |

The `?` operator means "if this is an error, return it from the function
now". `query` returns `windows::core::Result<…>`, and `get_location` turns
any Windows error into whereami's error kinds with `map_hresult`
(`E_ACCESSDENIED` → permission denied, `ERROR_TIMEOUT` → timeout).

## What Linux and macOS will need

- **Linux**: `zbus` talks to the system D-Bus in pure Rust. GeoClue2 needs
  `Manager.CreateClient`, then setting `DesktopId` and `RequestedAccuracyLevel`,
  then `Client.Start` and waiting for the `LocationUpdated` signal (see
  [`linux.zig`](../original/whereami/src/platform/linux.zig)).
- **macOS**: `objc2-core-location` gives typed `CLLocationManager` and
  `CLGeocoder`. The app needs a delegate and a run loop, and must ship as a
  `.app` bundle with an `Info.plist` usage string or macOS won't grant
  permission.
