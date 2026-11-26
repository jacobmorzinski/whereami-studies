# whereami — Rust

A Rust port of [whereami](https://github.com/georgemandis/whereami). It asks
the operating system's own location service where you are. Behavior is defined
by [`spec/behavior.md`](../spec/behavior.md).

| | |
|---|---|
| Windows | ✅ `Windows.Devices.Geolocation` via the [`windows`](https://crates.io/crates/windows) crate |
| Linux | ✅ GeoClue2 over D-Bus via [`zbus`](https://crates.io/crates/zbus) (blocking API) |
| macOS | stub (planned: CoreLocation via [`objc2-core-location`](https://crates.io/crates/objc2-core-location)) |

## Build, run, test

Windows (PowerShell):

```powershell
cargo build --release --manifest-path rust/Cargo.toml
rust/target/release/whereami.exe --json
cargo test --manifest-path rust/Cargo.toml
pwsh conformance/run.ps1 -Port rust
```

Linux (`sh`):

```sh
cargo build --release --manifest-path rust/Cargo.toml
rust/target/release/whereami --json
cargo test --manifest-path rust/Cargo.toml
bash conformance/run.sh --port rust
```

On Windows, live location needs *Settings → Privacy & security → Location*
with *Location services* and *Let desktop apps access your location* on.

On Linux (GNOME), turn on *Settings → Privacy → Location Services* and install
the desktop file from the repo root:
`cp assets/whereami.desktop ~/.local/share/applications/`.

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

Linux gets its own table the same way (`cfg(windows)` and `cfg(target_os = "windows")` are equivalent spellings, as are the Linux forms):

```toml
[target.'cfg(target_os = "linux")'.dependencies]
zbus = "5"
```

`zbus` is the D-Bus client used to reach GeoClue2 (see section 9), and it is
only built on Linux. It's pinned to major version 5 because zbus 6 dropped
the blocking API the backend uses.

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
#[cfg(target_os = "linux")]
mod linux;
#[cfg(target_os = "windows")]
mod windows;
```

`#[cfg(...)]` is conditional compilation. On Linux the `windows` module isn't
just unused; it doesn't exist, and on Windows the same is true of `linux`.
`native()` returns the right implementation the same way: `Windows` on
Windows, `Linux` on Linux, and an `Unsupported` stub (macOS for now)
everywhere else. Picking the OS costs nothing at runtime, and code for other
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

### 9. [`src/platform/linux.rs`](src/platform/linux.rs): GeoClue2 over D-Bus

Linux has no single location API. The desktop's location service, GeoClue2,
is a program you talk to over the system D-Bus, so this file is a
conversation. The original ([`linux.zig`](../original/whereami/src/platform/linux.zig))
drives libdbus by hand; here `zbus` does the wire protocol and the typed
proxies hide the rest. The conversation:

1. `Manager.CreateClient` returns the object path of a fresh client.
2. Set the client's `DesktopId` (`"whereami"`) and `RequestedAccuracyLevel`
   (8, GeoClue's EXACT level).
3. Subscribe to the client's `LocationUpdated` signal, **then** call `Start`.
   Subscribing first means the first fix can't arrive before anyone is listening.
4. `LocationUpdated` carries the path of a `Location` object. Read its
   `Latitude`, `Longitude` and `Accuracy` properties.
5. Send `Stop`, without waiting for the reply. This happens on every path, including a timeout.

**Typed proxies.** Each D-Bus interface is a trait with an attribute:

```rust
#[zbus::proxy(
    interface = "org.freedesktop.GeoClue2.Client",
    default_service = "org.freedesktop.GeoClue2",
    gen_async = false
)]
trait Client {
    fn start(&self) -> zbus::Result<()>;
    #[zbus(no_reply)]
    fn stop(&self) -> zbus::Result<()>;
    #[zbus(property)]
    fn set_desktop_id(&self, id: &str) -> zbus::Result<()>;
    #[zbus(signal)]
    fn location_updated(&self, old: OwnedObjectPath, new: OwnedObjectPath) -> zbus::Result<()>;
}
```

The macro writes a `ClientProxy` struct whose methods make the D-Bus calls.
`#[zbus(no_reply)]` makes `stop` a fire-and-forget call, `#[zbus(property)]` turns `set_desktop_id` into a property write, and
`#[zbus(signal)]` generates `receive_location_updated()`. There are three
proxies (`Manager`, `Client`, `GeoLocation`); `Client` and `GeoLocation` have
no fixed path, so each is built with `.path(...)` from what GeoClue returned.

**Blocking API.** `gen_async = false` asks for plain blocking methods instead
of `async fn`s, which keeps the code the same shape as the Windows backend
(no async runtime). `zbus` 5 is pinned (`zbus = "5"`) because zbus 6 dropped
the blocking API.

**Only our signals.** The `Client` proxy sits on our client's own object
path, so `receive_location_updated()` yields only our client's signals. No
filtering by hand.

**Timeout.** The signal iterator blocks with no limit of its own, so
`get_location` runs the whole conversation through `platform::with_timeout`
(the same helper as Windows) and computes one `deadline` from the overall
timeout. A reader thread turns each signal into a `Location` and sends it over
an `mpsc` channel; `best_fix` receives from it, and even its wait for the first fix is
bounded: it stops `DEADLINE_MARGIN` (50 ms) before the deadline to leave room
for `Stop` and exit. If no fix has arrived by then, `query` sends `Stop` and
returns a timeout error; if the reader thread ended instead, the error is
location unavailable. The reader thread silently skips updates whose
properties can't be read, keeping the best fix so far.

**Best-fix window (an extension).** The original returns the first
`LocationUpdated`. On the machine used for development, GeoClue sent a coarse
fix first (BeaconDB/IP, about ±25 km, ~1 ms) and the precise one (a static
±10 m source) about 5 ms later. So `best_fix` takes the first fix, keeps
listening for `BEST_FIX_WINDOW` (250 ms, never past the deadline), and returns
the one with the smallest `accuracy`; on a tie, the earlier. With only an IP
source there is a single update, so the wait is the window and nothing more.
This is an addition to the original; see "Linux fix selection (extension)"
in [`spec/behavior.md`](../spec/behavior.md) and the
[design note](../docs/superpowers/specs/2026-11-26-rust-linux-geoclue-design.md).

**Errors.** `map_zbus_error` takes a `zbus::Error`. A method error or an
`fdo::Error` (what property reads and writes report) is mapped by its D-Bus
error name: a name containing `AccessDenied` → permission denied, anything
else → location unavailable. Other failures (no bus, GeoClue not running,
malformed reply) are also location unavailable. Matching on the name string
keeps `map_dbus_error_name` a plain function that is easy to unit test.

`reverse_geocode` returns `None` and `geocode` returns `PlatformUnsupported`:
GeoClue has no geocoder.

**The desktop file.** `DesktopId` names an app GeoClue can look up. The repo
ships [`assets/whereami.desktop`](../assets/whereami.desktop); install it, from the repo root, with

```sh
cp assets/whereami.desktop ~/.local/share/applications/
```

On the Ubuntu 26.04 GNOME setup used for the live check, a terminal-launched
`whereami` still got a location with the file removed and no prompt appeared.
Other desktops or GeoClue configurations may need it, so installing it is
still the recommendation (the original does the same).

## What macOS will need

- **macOS**: `objc2-core-location` gives typed `CLLocationManager` and
  `CLGeocoder`. The app needs a delegate and a run loop, and must ship as a
  `.app` bundle with an `Info.plist` usage string or macOS won't grant
  permission.
