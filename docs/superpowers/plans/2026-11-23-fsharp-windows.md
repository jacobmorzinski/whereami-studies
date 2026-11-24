# F# Port (Windows) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An F# `whereami` that passes `spec/cases.json` on Windows and gets live location through the same WinRT projection the C# port uses, plus an Anatomy walkthrough. The walkthrough shows what changes when the libraries stay the same and only the language changes.

**Architecture:**
- **Same .NET pieces as C#:** the same two target frameworks (`net10.0;net10.0-windows10.0.19041.0`), `#if WINDOWS`, and `System.Text.Json`.
- **Written the F# way:**
  - Records and discriminated unions for the model.
  - `Result<_, LocationError>` for expected failures instead of exceptions.
  - The platform is a **record of functions**, not an interface, so a fake is a record expression.
  - `task { }` for async code.
  - Argu, a discriminated union that *is* the CLI.
- `App.run : Request -> Platform -> TimeSpan -> Task<Outcome>` is pure and tested with fakes.
- F# compiles files in the order the `.fsproj` lists them, so that order is part of the design (below).

**Tech Stack:**
- .NET SDK 10.0.402, pinned by the root `global.json`; F# and FSharp.Core come with the SDK.
- Argu 6.2.5.
- System.Text.Json, which handles F# records and `option` natively.
- xUnit 2.9.3 from `dotnet new xunit -lang "F#"`.

**Spec:** `docs/superpowers/specs/2026-11-23-whereami-ports-design.md`, plus `spec/behavior.md` and `spec/cases.json`. The C# port (`csharp/`) is the closest reference. Its stderr wording comes from `rust/src/app.rs`, word for word.

**Spike (throwaway, 2026-11-23):** I checked Argu 6.2.5 and the Windows target in F# before writing this plan.
- In F#, the `-windows10.0.19041.0` target framework defines `WINDOWS`, and `Windows.Devices.Geolocation.Geolocator()` can be constructed.
- Argu:
  - `-h` is *not* a help flag unless the type has `[<HelpFlags("--help", "-h")>]`. With it, `Parse(raiseOnUsage = true)` raises `ArguParseException` with `ErrorCode.HelpText`, and the usage text is in `.Message`.
  - A `[<MainCommand>]` list case swallows everything left over, including `--bogus`, `-x`, `--json=x`, `--` and `/?`.
  - `@home` is not expanded.
  - `[<EqualsAssignmentOrSpaced>]` accepts both `--mock=-33.8,151` and `--mock -33.8,151`.
  - `--mock` with no value is an `ErrorCode.CommandLine` error.
  - An address together with `--mock` parses without complaint.

## Global Constraints

- Version output: `whereami 0.4.0 (<os>)` (`windows`, `linux`, `macos`), for both `--version` and `-v`.
- Exit codes: usage errors **2**, runtime errors **1**, success 0. Usage errors print to stderr only, never JSON on stdout. Help goes to stdout with exit 0.
- Human output, exact:
  - `Location: {lat:F4}, {lon:F4}`
  - `Accuracy: {acc:F0}m` (omitted for `--at` and for address lookups)
  - `Address: …` (non-empty fields joined by `, `)
  - Culture-invariant, `\n` line endings.
- JSON keys: `latitude`, `longitude`, `accuracy`, `address` (`null` or `{street, city, state, postal_code, country}`). Errors are `{"error":"<key>"}`. stderr messages match `rust/src/app.rs` word for word.
- Coordinates:
  - Split at the first comma and trim **spaces only** (`Trim(' ')`).
  - Parse with `NumberStyles.AllowLeadingSign ||| NumberStyles.AllowDecimalPoint ||| NumberStyles.AllowExponent` and `CultureInfo.InvariantCulture`, then require `Double.IsFinite`.
  - `--mock` is not range-checked; `--at` must be within −90..90 and −180..180 inclusive.
- `completions` as the first address word is a usage error: `completions are not available in this port`.
- Any address word starting with `-` is a usage error (`unknown flag`). The spike shows Argu would otherwise treat it as an address.
- 10-second limit on the **whole request** (location + reverse geocode, or geocode). One budget, not one per call, and it must hold even if a platform function ignores its `CancellationToken`.
- `Console.OutputEncoding <- UTF8Encoding(false)` in `main`. Checked by hand for C#: it doesn't change the calling console.
- Don't modify `original/`, `rust/`, `csharp/` or `java/`.
- Git is local only. Branches `fsharp-m1-scaffold` (Tasks 1–3), `fsharp-m2-windows` (Task 4) and `fsharp-m3-docs` (Task 5) are each merged into `main` with `git merge --no-ff` once their checks pass. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

- **`--bogus` swallowed as an address** (confirmed by the spike). It must exit 2. Covered by `unknownFlagIsUsageError` and the case `unknown-flag`.
- **`-h`** is not help without `HelpFlags` (confirmed by the spike). It must print help and exit 0. Covered by `shortHelpIsInfo` and the case `help-short`.
- **German culture.** Output must print `40.7128`, and parsing must accept it. Covered by `CultureTests`.
- **A platform function that ignores cancellation, late in the request.** If the location arrives quickly but reverse geocoding hangs, the request must still end at the deadline. That is the gap the C# port has. Covered by `timeoutCoversWholeRequest`.
- **F# `option` in JSON.** `None` must be written as `"address":null`, and `Some address` as a plain object, not `{"Value":…}`. Covered by `jsonNullAddress` and `jsonEscapesAddress`.

---

## File Structure

`.fsproj` compile order (each file can only use the ones above it):

```
fsharp/Whereami.slnx
fsharp/src/Whereami/Whereami.fsproj   TargetFrameworks net10.0;net10.0-windows10.0.19041.0; AssemblyName whereami; Version 0.4.0
  Model.fs          Location, Address, GeocodeResult, LocationError (+ jsonKey)
  Output.fs         human / json / jsonError
  Platform.fs       Platform record type, osName, requestTimeout, unsupported
  Windows.fs        (#if WINDOWS) Windows.platform, mapException
  Native.fs         Native.platform (): picks Windows.platform or Platform.unsupported
  CommandLine.fs    Argu Args, Mode, Request, Parsed, parseCoord, parse
  App.fs            Outcome, run
  Program.fs        [<EntryPoint>] main
fsharp/tests/Whereami.Tests/Whereami.Tests.fsproj   same TargetFrameworks; references src
  OutputTests.fs CultureTests.fs CommandLineTests.fs AppTests.fs WindowsTests.fs
fsharp/README.md
```
Add `fsharp/**/bin/`, `fsharp/**/obj/` and `fsharp/out/` to `.gitignore`.

Conformance build: `dotnet publish fsharp/src/Whereami -c Release -f net10.0-windows10.0.19041.0 -o fsharp/out/windows`. This produces `fsharp/out/windows/whereami.exe`.

---

### Task 1: Solution, model, and output (branch `fsharp-m1-scaffold`)

**Files:**
- Create: `Whereami.slnx`, both `.fsproj`s, `Model.fs`, `Output.fs`, `Program.fs` (temporary `0`), `OutputTests.fs`, `CultureTests.fs`
- Modify: `.gitignore`

**Interfaces:**
- Produces (namespace `Whereami`):
  - `type Location = { Latitude: float; Longitude: float; Accuracy: float }`
  - `type Address = { Street: string; City: string; State: string; PostalCode: string; Country: string }`
  - `type GeocodeResult = { Location: Location; Address: Address }`
  - `type LocationError = PermissionDenied | LocationUnavailable | Timeout | GeocodingFailed | PlatformUnsupported`
  - `module LocationError = val jsonKey : LocationError -> string`. `GeocodingFailed` maps to `unknown_error`.
  - `module Output = val human : Location -> Address option -> bool -> string`, `val json : Location -> Address option -> string`, `val jsonError : string -> string`. `json` and `jsonError` end with `\n`.

JSON:
- Use `JsonSerializerOptions(PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower, Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping)` on a private record `{ Latitude; Longitude; Accuracy; Address: Address option }`.
- Human output: `String.Format(CultureInfo.InvariantCulture, …)` or `sprintf`. F#'s `sprintf "%.4f"` is invariant, so either is fine, and `CultureTests` proves it.

- [ ] **Step 1: Scaffold.**
  - Run `dotnet new sln --format slnx`, `dotnet new console -lang "F#"` and `dotnet new xunit -lang "F#"`, then `dotnet sln add` and `dotnet add reference`.
  - In both projects set `TargetFrameworks`; in the app also set `AssemblyName`, `Version` and `InternalsVisibleTo Whereami.Tests`.
  - Pin the test project's packages to the C# test project's versions (xunit 2.9.3, Microsoft.NET.Test.Sdk 17.14.1, xunit.runner.visualstudio 3.1.4), so the two test projects match.
- [ ] **Step 2: Write failing tests.**

```fsharp
// OutputTests
[<Fact>] let humanWithAccuracy () = Assert.Equal("Location: 40.7128, -74.0060\nAccuracy: 0m\n", Output.human loc0 None true)
[<Fact>] let humanWithoutAccuracySkipsEmptyFields () =
    Assert.Equal("Location: 38.8977, -77.0366\nAddress: 1600 Pennsylvania Ave NW, Washington, 20500, US\n",
                 Output.human { Latitude = 38.8977; Longitude = -77.0366; Accuracy = 5. }
                     (Some { Street = "1600 Pennsylvania Ave NW"; City = "Washington"; State = ""; PostalCode = "20500"; Country = "US" }) false)
[<Fact>] let jsonNullAddress ()      // JsonDocument: latitude 40.7128; address ValueKind Null; property names in order [latitude; longitude; accuracy; address]
[<Fact>] let jsonEscapesAddress ()   // street 5 "Q" St\ round-trips; "postal_code" is a key; address is an Object (not {"Value":…}); raw text contains "Montréal"
[<Fact>] let jsonErrorShape () = Assert.Equal("{\"error\":\"timeout\"}\n", Output.jsonError "timeout")
[<Fact>] let jsonKeys ()             // all five LocationError cases → permission_denied, location_unavailable, timeout, unknown_error, platform_unsupported
// CultureTests: a class whose constructor sets CultureInfo.CurrentCulture <- CultureInfo "de-DE" and whose IDisposable restores it
[<Fact>] member _.HumanOutputIsInvariant () = Assert.StartsWith("Location: 40.7128, -74.0060", Output.human loc0 None true)
```
(`loc0 = { Latitude = 40.7128; Longitude = -74.0060; Accuracy = 0. }`.)

- [ ] **Step 3: Run** `dotnet test fsharp`. Expected: build FAIL (`Output` not defined).
- [ ] **Step 4: Implement** `Model.fs` and `Output.fs`.
- [ ] **Step 5: Run** `dotnet test fsharp`. Expected: 7 passed per target framework (14 total).
- [ ] **Step 6: Commit** on the new branch `fsharp-m1-scaffold`: `fsharp: add solution, model, and output formatting`.

---

### Task 2: Platform type and argument parsing with Argu

**Files:**
- Create: `Platform.fs`, `Native.fs` (Unsupported only for now), `CommandLine.fs`, `CommandLineTests.fs`
- Modify: `CultureTests.fs`

**Interfaces:**
- Produces (`Platform.fs`):

```fsharp
type Platform =
    { GetLocation: CancellationToken -> Task<Result<Location, LocationError>>
      ReverseGeocode: float -> float -> CancellationToken -> Task<Address option>
      Geocode: string -> CancellationToken -> Task<Result<GeocodeResult option, LocationError>> }
module Platform =
    val osName : string            // "windows" | "macos" | "linux", via OperatingSystem.IsWindows/IsMacOS
    val requestTimeout : TimeSpan  // 10 s
    val unsupported : Platform     // GetLocation/Geocode → Error PlatformUnsupported; ReverseGeocode → None
```

- Produces (`Native.fs`): `module Native = val platform : unit -> Platform`. It returns `Platform.unsupported` for now.
- Produces (`CommandLine.fs`):
  - `type Mode = Current | Mock of Location | At of Location | Lookup of string`
  - `type Request = { Mode: Mode; Json: bool }`
  - `type Parsed = Run of Request | Info of string | UsageError of string`. The case is named `Run`, not `Ok`, to avoid colliding with `Result.Ok`.
  - `module CommandLine = val parseCoord : string -> Location option` and `val parse : string[] -> Parsed`.

Argu template:

```fsharp
[<HelpFlags("--help", "-h")>]
type Args =
    | Json
    | [<EqualsAssignmentOrSpaced>] Mock of lat_lon: string
    | [<EqualsAssignmentOrSpaced>] At of lat_lon: string
    | [<AltCommandLine("-v")>] Version
    | [<MainCommand>] Address of address: string list
    interface IArgParserTemplate with member a.Usage = (* one line per case *)
```

`parse`, in this order:
1. `ArgumentParser.Create<Args>(programName = "whereami", helpTextMessage = <the description from the C# port>)`, then `Parse(argv, raiseOnUsage = true)`. Catch `ArguParseException`: `ErrorCode.HelpText` gives `Info` (message with `\n` line endings, plus a trailing `\n`); anything else gives `UsageError`.
2. `Version` present gives `Info "whereami 0.4.0 (<os>)\n"`.
3. If the address words are non-empty: first word `completions` is a usage error; any word starting with `-` is `unknown flag '<word>'`.
4. A bad `--mock`/`--at` format, or `--at` out of range, is a usage error.
5. Address words joined with `" "` and `Trim()`-ed to empty is a usage error. An address with `--mock`/`--at`, or `--mock` with `--at`, is a usage error.
6. Otherwise `Run`.

- [ ] **Step 1: Write failing tests** in `CommandLineTests`. `parsed` and the `usage` helpers pattern-match on `Parsed`.

```fsharp
[<Theory; InlineData("40.7128,-74.0060", 40.7128, -74.006); InlineData(" 51.5 , -0.1276", 51.5, -0.1276); InlineData("1e1,+2", 10., 2.)>]
let parsesCoord (text, lat, lon) = Assert.Equal(Some { Latitude = lat; Longitude = lon; Accuracy = 0. }, CommandLine.parseCoord text)
[<Theory; InlineData("40.7128"); InlineData("abc,def"); InlineData("1,2,3"); InlineData("nan,0"); InlineData("NaN,0"); InlineData("0,inf");
  InlineData("Infinity,0"); InlineData("∞,0"); InlineData("0x10,0"); InlineData("1e999,0"); InlineData("\t1,2"); InlineData(",")>]
let rejectsCoord text = Assert.Equal(None, CommandLine.parseCoord text)
[<Fact>] let versionBothSpellings ()       // "--version" and "-v" → Info "whereami 0.4.0 (windows)\n" (Platform.osName)
[<Fact>] let shortHelpIsInfo ()            // "-h" and "--help" → Info, text contains "--mock"
[<Fact>] let unknownFlagIsUsageError ()    // "--bogus" → UsageError; also "Paris" "-x"
[<Fact>] let atSignIsAddress ()            // "@home" → Run (Lookup "@home")
[<Fact>] let multiwordAddressJoined ()     // "350" "5th" "Ave" → Lookup "350 5th Ave"
[<Fact>] let mockNegativeEqualsForm ()     // "--mock=-33.8688,151.2093" → Mock
[<Fact>] let completionsReserved ()        // "completions" "bash" → UsageError containing "completions are not available in this port"
[<Fact>] let conflictsAreUsageErrors ()    // ["--at=1,2";"--mock=1,2"], ["--mock=1,2";"Paris"], ["   "], ["--at=91,0"], ["--at=0,-180.5"] → each UsageError
[<Fact>] let atBoundaryAccepted ()         // "--at=90,-180" → At
// CultureTests (extend)
[<Fact>] member _.ParsingIsInvariant () = Assert.Equal(Some loc0, CommandLine.parseCoord "40.7128,-74.0060")
```

- [ ] **Step 2: Run** `dotnet test fsharp`. Expected: build FAIL.
- [ ] **Step 3: Implement** `Platform.fs`, `Native.fs` and `CommandLine.fs` (added to the `.fsproj` in the order above).
- [ ] **Step 4: Run** `dotnet test fsharp`. Expected: all pass on both target frameworks.
- [ ] **Step 5: Commit** `fsharp: platform type and Argu argument parsing`.

---

### Task 3: App logic, entry point, conformance

**Files:**
- Create: `App.fs`, `AppTests.fs`
- Modify: `Program.fs`, `conformance/ports.json`, `PROGRESS.md`

**Interfaces:**
- Consumes: `Platform`, `Request`, `Mode`, `Output`, `LocationError.jsonKey`.
- Produces:
  - `type Outcome = { Stdout: string; Stderr: string; ExitCode: int }`
  - `module App = val run : Request -> Platform -> TimeSpan -> Task<Outcome>`

`App.run`:
- Builds one `task { }` for all platform calls of the request, matching `rust/src/app.rs` case for case. It passes the token from a single `new CancellationTokenSource(timeout)` and awaits `work.WaitAsync(timeout)`.
- `TimeoutException`, or `OperationCanceledException` from that token, becomes `Timeout`.
- Other exceptions propagate.
- `Mock` returns without touching the platform.

`Program.main`:
- Sets the UTF-8 output encoding, then matches on `CommandLine.parse argv`:
  - `Info`: stdout, exit 0.
  - `UsageError`: stderr `Error: <msg>`, exit 2.
  - `Run`: `App.run req (Native.platform ()) Platform.requestTimeout`, then `.GetAwaiter().GetResult()`, write the output, return the exit code.

- [ ] **Step 1: Write failing tests** in `AppTests`. Fakes are record copies: `{ Platform.unsupported with GetLocation = fun _ -> Task.FromResult(Ok loc) }`.

```fsharp
[<Fact>] let mockSkipsPlatform ()          // platform whose functions all failwith → Mock outcome "Location: 40.7128, -74.0060\nAccuracy: 0m\n", exit 0
[<Fact>] let currentWithAddress ()         // Accuracy + Address lines, exit 0, stderr ""
[<Fact>] let atWithoutAddressWarns ()      // At(90,-180) → stdout "Location: 90.0000, -180.0000\n", stderr "Warning: no address found for these coordinates.\n", exit 0
[<Fact>] let permissionDeniedJson ()       // stdout {"error":"permission_denied"}\n, stderr starts "Error: location permission denied.", exit 1
[<Fact>] let unsupportedLocation ()        // stderr "Error: location is not supported on this platform yet.\n", exit 1
[<Fact>] let lookupUnsupported ()          // json → {"error":"platform_unsupported"}\n, stderr "Error: address lookup is not supported on this platform.\n"
[<Fact>] let lookupNotFound ()             // Ok None → key address_not_found, stderr contains "couldn't find a location for \"Paris\""
[<Fact>] let lookupFoundOmitsAccuracy ()
[<Fact>] let timeoutHoldsWhenPlatformIgnoresCancellation ()  // GetLocation = fun _ -> (TaskCompletionSource()).Task (never completes); run with 200 ms → key timeout, "Error: location request timed out.\n", finishes in < 2 s
[<Fact>] let timeoutCoversWholeRequest ()  // GetLocation returns after 300 ms; ReverseGeocode never completes and ignores ct; run with 500 ms → timeout, total elapsed < 1.5 s (one budget, not 500 ms per call)
[<Fact>] let unexpectedExceptionPropagates ()  // GetLocation raises InvalidOperationException → Assert.ThrowsAnyAsync
```

- [ ] **Step 2: Run** `dotnet test fsharp`. Expected: build FAIL (`App` not defined).
- [ ] **Step 3: Implement** `App.fs` and `Program.fs`.
- [ ] **Step 4: Run** `dotnet test fsharp`. Expected: all pass.
- [ ] **Step 5: Wire up conformance.**
  1. Add an `fsharp` entry to `ports.json`: path `fsharp/out/windows/whereami`, the build command above, features `completions:false`.
  2. Publish, then run `pwsh conformance/run.ps1 -Port fsharp`. Expected: 29 PASS.
  3. Run `pwsh conformance/run.ps1`. Expected: all four ports PASS. Build any port that shows as missing.
  4. Run the runner self-test against `fsharp`. Expected: every case FAIL.
- [ ] **Step 6: Update PROGRESS.** Set the fsharp row to scaffold ✅ and conformance (mock) ✅.
- [ ] **Step 7: Commit and merge.** Commit `fsharp: app logic and entry point; passes conformance`, then merge `fsharp-m1-scaffold` into `main` with `--no-ff`.

---

### Task 4: Windows backend via the WinRT projection (branch `fsharp-m2-windows`)

**Files:**
- Create: `Windows.fs` (whole file inside `#if WINDOWS`), `WindowsTests.fs` (inside `#if WINDOWS`)
- Modify: `Native.fs`, `PROGRESS.md`

**Interfaces:**
- Consumes: `Platform`, `Platform.requestTimeout`.
- Produces: `module Windows = val platform : Platform` and `val internal mapException : exn -> LocationError`.

`GetLocation`:
- A `task { }` that does the same as `csharp/src/Whereami/Platforms/WindowsPlatform.cs`:
  - `Geolocator.RequestAccessAsync().AsTask(ct)`. Any result other than `Allowed` → `Error PermissionDenied`.
  - `Geolocator(DesiredAccuracy = PositionAccuracy.High)`.
  - `GetGeopositionAsync(TimeSpan.Zero, Platform.requestTimeout).AsTask(ct)`.
  - Read `position.Coordinate.Latitude`, `.Longitude` and `.Accuracy`, as the C# port does.
- It catches exceptions inside the `task` and returns `Error (mapException e)`.

`mapException` follows the C# `MapException` exactly:
- `UnauthorizedAccessException`, or HResult `0x80070005` → `PermissionDenied`.
- `OperationCanceledException`, `TimeoutException`, or HResult `0x800705B4` → `Timeout`.
- Anything else → `LocationUnavailable`.

`ReverseGeocode` returns `None`, and `Geocode` returns `Error PlatformUnsupported`. Add a comment that MapLocationFinder needs a Bing Maps key.

`Native.platform ()` uses `#if WINDOWS` plus `OperatingSystem.IsWindows()`, exactly like C#'s `Platforms.Native()`.

- [ ] **Step 1: Write failing tests** in `WindowsTests`, mirroring C#'s `WindowsPlatformTests`:

```fsharp
[<Fact>] let accessDeniedIsPermission ()        // mapException (UnauthorizedAccessException()) = PermissionDenied
[<Fact>] let accessDeniedHResult ()             // COMException("", 0x80070005) → PermissionDenied
[<Fact>] let timeoutHResult ()                  // COMException("", 0x800705B4) → Timeout
[<Fact>] let cancellationIsTimeout ()           // OperationCanceledException() → Timeout
[<Fact>] let otherIsUnavailable ()              // COMException("", 0x80004005) → LocationUnavailable
```
(The `0x8007…` literals need `int 0x80070005u` or `0x80070005` written as an `int32` literal in F#.)

- [ ] **Step 2: Run** `dotnet test fsharp`. Expected: build FAIL on the Windows target (`Windows` not defined).
- [ ] **Step 3: Implement** `Windows.fs` and update `Native.fs`.
- [ ] **Step 4: Run** `dotnet test fsharp`, publish, then `pwsh conformance/run.ps1 -Port fsharp`. Expected: all pass, 29 PASS.
- [ ] **Step 5: Live check.**
  - Run `fsharp/out/windows/whereami.exe` and the same with `--json`. Expected: exit 0, nothing on stderr, the same position as the Rust port. Compare the two without printing either.
  - In PROGRESS, set the Windows live cell to 🟨 with a note asking the user to check with location access off.
- [ ] **Step 6: Commit and merge.** Commit `fsharp: Windows location via WinRT projection`, then merge `fsharp-m2-windows` with `--no-ff`.

---

### Task 5: Anatomy and comparison (branch `fsharp-m3-docs`)

**Files:**
- Create: `fsharp/README.md`
- Modify: `docs/comparison.md` (F# column), `PROGRESS.md` (Anatomy ✅)

The README follows the shape of `csharp/README.md`, and its main thread is **what differs from C# with the same libraries**:
- Compile order in the `.fsproj`.
- Records and DUs vs classes and closed record hierarchies.
- `Result` vs exceptions, with exceptions turned into `Result` at the WinRT boundary.
- A record of functions vs an interface; fakes are `{ x with … }`.
- `task { }` vs `async`/`await`.
- Argu's DU-as-CLI vs System.CommandLine objects, including the two Argu details from the spike (`HelpFlags` and the `MainCommand` catch-all).
- `[<EntryPoint>]` vs top-level statements.
- One timeout budget for the whole request.

It ends with a Zig / Rust / C# / Java / F# row for the Windows call, and a short "Linux and macOS" section: the same libraries as C# (`Tmds.DBus`, `net10.0-macos`).

- [ ] **Step 1: Write** the README and the comparison column. Check every relative link resolves.
- [ ] **Step 2: Commit and merge.** Commit `fsharp: Anatomy walkthrough and comparison column`, then merge `fsharp-m3-docs` with `--no-ff`.
