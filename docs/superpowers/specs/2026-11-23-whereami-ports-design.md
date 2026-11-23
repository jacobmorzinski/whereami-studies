# whereami ports — design

Date: 2026-11-23
Status: approved 2026-11-23

## Intent

A learning exercise. Re-create `whereami` (George Mandis's Zig CLI that asks
the OS's native location service where you are; MIT licensed, upstream
`https://github.com/georgemandis/whereami`, reference copy at
`original/whereami`, commit `f4bff19`, version 0.4.0) in several languages.

The main point is to see, side by side, **what a simple, idiomatic CLI app looks
like in each language** — project file, entry point, argument parsing, JSON
output, choosing code per OS, and calling a native OS service through that
language's usual bindings. A secondary point is that one port may become the
version the owner keeps and iterates on, so each port should be a clean,
standalone project, not a throwaway.

## Goals

- Ports, in this order: **Rust → C# → Java → F# → Clojure** (F# and Clojure
  are "maybe": planned, but optional).
- Each port uses the **idiomatic bindings** for its language where they exist
  (see the bindings table). Raw by-hand FFI only where no bindings exist.
- Each port is structured to support **Windows, Linux, and macOS**. Windows is
  implemented first; Linux and macOS start as stubs and are filled in later.
- All ports share one **behavior spec** and are checked by one **conformance
  script**, so "done" is unambiguous.
- Each port's README has an **Anatomy** section that walks a reader through
  how that CLI is put together (the learning deliverable).
- Progress is visible in one grid (`PROGRESS.md`).

## Non-goals

- Byte-for-byte identical `--help` text or usage-error wording. Those come from
  each language's CLI library and should look native to that ecosystem.
- Packaging and distribution (Homebrew, Scoop, .deb, installers, release CI).
- Shell `completions` subcommand (optional per port; generate with the CLI
  library if it's easy — clap, picocli and System.CommandLine all can).
- Geocoding on Windows or Linux (the original has none there either).
- Any network / IP-based / third-party location lookup.

## Anti-goals (would make this a failure even if it works)

- Ports that are transliterated Zig (hand-written vtables in Rust or C# when
  idiomatic bindings exist). That defeats "what does an idiomatic X app look
  like".
- Ports that drift from each other in observable behavior, making side-by-side
  comparison meaningless.
- Modifying `original/`. It is read-only reference.

## Constraints

- Development machine is Windows 11. Only Windows can be tested live for now.
  Linux may later be tested in WSL2 (see Linux testing). macOS needs a Mac.
- Windows location for unpackaged desktop apps requires *Settings → Privacy &
  security → Location → Location services* and *Let desktop apps access your
  location* to be on.
- The outer repo (`sandbox2026/whereami`) has no commits yet;
  `original/whereami` is its own nested git clone.

## Approaches considered (repo organization)

| | Approach | Trade-off |
|---|---|---|
| **A (chosen)** | One repo; shared `spec/` + conformance script; one folder per language, each a standalone native project; `PROGRESS.md` grid | Easy side-by-side comparison, clear "done". Mixes several build systems in one repo (contained by folders). |
| B | One repo, folder per language, no shared spec — each port copies behavior from the Zig source | Less setup; ports drift, "done" is fuzzy. |
| C | Separate repo per language | Best for the eventual keeper, worst for comparison. A port in A can be split out later. |

Owner picked **A**. Owner also chose **idiomatic bindings where available** over
the faithful by-hand approach.

## Repository layout

```
whereami/                       (outer repo)
  README.md                     what this exercise is; links to everything below
  PROGRESS.md                   port × feature × platform grid
  original/whereami/            upstream reference (gitignored, see below)
  spec/
    behavior.md                 the behavior contract (summarized below)
    cases.json                  conformance cases (data, not code)
  conformance/
    run.ps1                     runs cases.json against one or all ports
    ports.json                  how to invoke each port's built binary
  rust/                         cargo project
  csharp/                       dotnet project
  java/                         gradle multi-project: platform/ (library) + app/ (CLI)
  fsharp/                       dotnet project (later)
  clojure/                      deps.edn project (later)
  docs/
    comparison.md               side-by-side notes, grows as ports land
    superpowers/specs/          this document
```

`original/` goes in the outer repo's `.gitignore`; README records the upstream
URL and commit so it can be re-cloned. (Making it a submodule is the
alternative; not needed for a read-only reference.)

### Inside each port (same shape in every language)

1. **model** — `Location { latitude, longitude, accuracy }`,
   `Address { street, city, state, postal_code, country }`,
   `GeocodeResult`, and the error kinds: `PermissionDenied`,
   `LocationUnavailable`, `Timeout`, `GeocodingFailed`, `PlatformUnsupported`.
2. **cli** — argument parsing, validation, human and JSON output, exit codes.
   No OS code here.
3. **platform** — one interface/trait/protocol with three operations
   (`get_location(timeout)`, `reverse_geocode(lat, lon)`, `geocode(query)`),
   and one implementation per OS selected by the language's idiomatic
   mechanism (Rust `#[cfg(target_os)]`; .NET `OperatingSystem.IsWindows()` plus
   per-OS target frameworks where needed; Java `System.getProperty("os.name")`).
4. **Stubs**: until an OS is implemented, its `get_location` returns
   `PlatformUnsupported` and `geocode` returns `PlatformUnsupported`;
   `reverse_geocode` returns "no address".

The Windows implementation of `reverse_geocode` returns no address and
`geocode` returns `PlatformUnsupported`, matching the original.

## Bindings per port

| Port | Windows (`Windows.Devices.Geolocation.Geolocator`) | Linux (GeoClue2 over system D-Bus) | macOS (CoreLocation / CLGeocoder) | CLI / JSON |
|---|---|---|---|---|
| Rust | `windows` crate (features `Devices_Geolocation`, `Foundation`) | `zbus` | `objc2-core-location` | `clap` (derive), `serde` + `serde_json` |
| C# | WinRT projection via `net10.0-windows10.0.19041.0` target framework | `Tmds.DBus` | .NET macOS bindings (`net10.0-macos`), needs an app bundle | `System.CommandLine`, `System.Text.Json` |
| Java (JDK 25) | **By hand** with the FFM API (`java.lang.foreign`): `RoInitialize`, `WindowsCreateString`, `RoActivateInstance`, vtable calls — no WinRT bindings exist for Java | `dbus-java` | By hand (FFM + Objective-C runtime) | `picocli`, Jackson |
| F# | same libraries as C# | same | same | `Argu`, `System.Text.Json` |
| Clojure | reuses the Java port's platform layer as a local dependency (`:local/root`) | same | same | `tools.cli`, `data.json` |

Java is the one port that shows by-hand OS calls; its README should say so and
point at the Zig `windows.zig` as the thing it mirrors.

macOS note (all ports): CoreLocation only grants permission to an app with a
bundle and `Info.plist` usage strings; the original builds a `.app` bundle for
this. Each port's macOS milestone includes producing that bundle.

## Behavior spec (contract for `spec/behavior.md`)

Derived from `original/whereami/src/main.zig`.

**Arguments**
- `--json` — JSON output.
- `--mock=LAT,LON` — use these coordinates instead of location services; no
  reverse geocoding (stays offline). Not range-checked.
- `--at=LAT,LON` — use these coordinates and reverse-geocode them. Must be in
  range (lat −90..90, lon −180..180).
- `LAT,LON` parsing: split at first comma, trim spaces, parse each as a float.
- `--version` / `-v` — print `whereami 0.4.0 (<os>)` where `<os>` is
  `windows`, `linux`, or `macos`; exit 0.
- `--help` / `-h` — usage; exit 0. Wording is the CLI library's.
- Positional args are joined with single spaces into one address (forward
  geocode). Empty after trimming → usage error.
- Usage errors (exit **2**): unknown flag; bad `--mock`/`--at` format; `--at`
  out of range; address combined with `--at`/`--mock`; `--at` with `--mock`;
  empty address. Message goes to stderr; wording may differ per port.
  Every port must configure its CLI library so parse errors exit 2 (e.g.
  System.CommandLine defaults to 1).
- **Grammar outside the listed cases is the library's.** Only the behaviors
  in `cases.json` are strict. Whether `--mock 1,2` (space-separated) works,
  whether repeated flags are rejected, whether `--help` beats an unknown flag,
  `--` handling, etc. follow each CLI library's normal declarative behavior.
  Ports must not hand-roll parsers to mimic Zig quirks (prefix matching,
  order-dependent `--version`). Negative coordinates must work in the
  `--mock=-33.8,151.2` form.
- **Number syntax**: each coordinate is a finite decimal number with optional
  sign, fraction and exponent, parsed culture-invariantly (`.` is the decimal
  point everywhere). `nan`, `inf`, `Infinity`, hex floats, and type suffixes
  are usage errors (exit 2). This tightens the original, which accepts
  `nan`/`inf` and then emits invalid JSON.
- **`completions` is reserved** in every port. A port that implements
  completions behaves as the original (`completions bash|zsh|fish`; missing or
  unknown shell → exit 2). A port that doesn't prints
  `Error: completions are not available in this port` and exits 2 — it is
  never treated as an address.

**Human output (stdout, exact)**
```
Location: {lat:.4}, {lon:.4}
Accuracy: {accuracy:.0}m        ← omitted for --at and for address lookups
Address: a, b, c               ← only if an address exists; empty fields skipped
```

**JSON output (stdout, one line, compared by value, not bytes)**
`{"latitude":…,"longitude":…,"accuracy":…,"address":null}` or with
`"address":{"street":…,"city":…,"state":…,"postal_code":…,"country":…}`.
Key names exact. Numbers compared numerically (so `0` vs `0.0` and
float formatting differences between serializers don't matter).

**Runtime errors (exit 1)**
- Message on stderr, as in the original (e.g.
  `Error: location permission denied.` …).
- With `--json`, also print to stdout `{"error":"<key>"}` where key is one of
  `permission_denied`, `location_unavailable`, `timeout`, `unknown_error`,
  `platform_unsupported`, `address_not_found`.
- `--at` with no address found: stderr `Warning: no address found for these
  coordinates.`, still print location, exit 0.
- **Extension over the original:** a stubbed OS's `get_location` reports
  `platform_unsupported` (stderr `Error: location is not supported on this
  platform yet.`), exit 1.

**Timeout**: location request waits up to 10 seconds.

## Conformance

`spec/cases.json` lists cases: `args`, expected `exit` code, expected `stdout`
as either `exact` text or `json` value, and optional `stderr_contains`. Cases
cover the deterministic paths: `--mock` human and JSON, `--version`, every
usage error's exit code, `--at` on Windows (location + warning, no accuracy
line), address on Windows (`platform_unsupported`, with and without `--json`).

`conformance/run.ps1 [-Port rust|csharp|…|all]` runs each case against the
port's built binary (command from `ports.json`, e.g.
`rust/target/release/whereami.exe`, `java/build/install/whereami/bin/whereami.bat`)
and prints pass/fail per case. A bash twin comes later with Linux support.

Live location (no `--mock`) is not automatable; each port's Windows milestone
includes a manual smoke check recorded in `PROGRESS.md`.

## Linux testing (later)

Plan to try WSL2 with systemd enabled, `geoclue-2.0` installed, GeoClue ≥ 2.7
configured with its static source (`/etc/geolocation`) so it reports a fixed
position, and `whereami` allowed in `/etc/geoclue/geoclue.conf`. Untested;
verify when the first Linux backend is written. If it doesn't work, Linux
backends ship "builds and is code-reviewed, not live-tested".

## Tracking

- **`PROGRESS.md`** — one row per port, columns:
  `scaffold | conformance (mock) | Windows live | Linux | macOS | Anatomy written`.
  Cells: ⬜ not started, 🟨 in progress, ✅ done, ➖ n/a.
- **Git workflow: local only.** Default branch `main`; no remote for now.
  One local branch per milestone (e.g. `m0-shared`, `rust-m1-scaffold`,
  `rust-m2-windows`), merged into `main` once its conformance cases pass.
- **Milestones per port** (each one branch):
  1. Scaffold + cli + model + stubs; all `--mock`/usage conformance cases pass.
  2. Windows backend; Windows conformance cases pass; manual live check.
  3. README Anatomy section; add the port's column to `docs/comparison.md`.
  4. (later) Linux backend. 5. (later) macOS backend + bundle.
- Shared work (spec, cases, conformance script, PROGRESS, root README) is
  milestone 0, done before the Rust port.
- Implementation plans live alongside this spec as dated files under
  `docs/superpowers/plans/`, one per port milestone set.

## Decided by the owner

- Approach A; order Rust → C# → Java → F# → Clojure.
- Idiomatic bindings where available.
- All three OSes as the target; Windows first; others later.
- Git: local only, `main` branch, one branch per milestone.

## Calls made in this design (owner can override)

- Help/usage-error wording left to each CLI library; exit codes, stdout output,
  and JSON keys are strict.
- JSON compared by value, not bytes.
- `platform_unsupported` extension for stubbed OSes.
- Version string stays `0.4.0` (behavior baseline) in every port.
- Toolchains: latest stable Rust; .NET 10 (LTS); JDK 25 (LTS) with Gradle;
  Clojure CLI with deps.edn.
- `original/` gitignored rather than a submodule.
- Completions optional, but the word `completions` is reserved everywhere.
- Argument grammar beyond `cases.json` follows each CLI library; parse errors
  exit 2.
- Coordinates: finite, culture-invariant decimals only; non-finite → exit 2.
- Java is a Gradle multi-project from the start (`java/platform` library with
  model + platform layer, `java/app` CLI), so the Clojure port can depend on
  `java/platform` (published to `mavenLocal` or consumed as a jar) without
  restructuring Java later.

## Left to the builder

- Exact crate/package versions; project-internal file names beyond the
  model/cli/platform split.
- How each port waits on the async location call (e.g. Rust `.get()`, C#
  `await` with a 10s cancellation, Java polling `IAsyncInfo`).
- Whether the C# project multi-targets or uses one project per OS group, as
  long as `dotnet build` on Windows produces a working Windows binary.
- Clojure: whether to reuse the Java platform layer or call FFM via a Clojure
  library (`coffi`) — decide when that port starts.
