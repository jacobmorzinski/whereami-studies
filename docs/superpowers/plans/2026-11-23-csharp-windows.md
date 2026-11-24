# C# Port (Windows) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A C# `whereami` that passes `spec/cases.json` on Windows and reports live location through the built-in WinRT bindings, with an Anatomy walkthrough a reader can compare with the Rust port.

**Architecture:** Same shape as the Rust port, in idiomatic C#: records for the model, `System.CommandLine` for parsing, `System.Text.Json` for output, `async`/`await` with `CancellationToken`s, and errors as exceptions (`LocationException`). `App.RunAsync(Request, IPlatform, TimeSpan)` returns an `Outcome` (text + exit code), so all CLI behavior is tested with a fake platform. The project multi-targets `net10.0` and `net10.0-windows10.0.19041.0`; only the Windows target compiles `WindowsPlatform` (`#if WINDOWS`), and `Platforms.Native()` picks the implementation at runtime with `OperatingSystem.IsWindows()`.

**Tech Stack:** .NET SDK 10.0.402 (pinned with `global.json`), C# 14, System.CommandLine 2.0.x (latest stable), System.Text.Json (in-box), xUnit (`dotnet new xunit`), WinRT projection from the `-windows10.0.19041.0` target framework (`Windows.Devices.Geolocation`).

**Spec:** `docs/superpowers/specs/2026-11-23-whereami-ports-design.md` (and the strict contract `spec/behavior.md` + `spec/cases.json`). The Rust port (`rust/`, plan `docs/superpowers/plans/2026-11-23-m0-shared-and-rust-windows.md`) is the reference for behavior already settled.

## Global Constraints

- Version output: `whereami 0.4.0 (<os>)`, `<os>` ∈ `windows`, `linux`, `macos`, both for `--version` and `-v`.
- Usage errors exit **2**, runtime errors exit **1**, success 0. System.CommandLine's default for parse errors is 1, so the port must handle that itself.
- Usage errors print to stderr only, never JSON on stdout.
- Human output, exact: `Location: {lat:F4}, {lon:F4}` / `Accuracy: {acc:F0}m` (omitted for `--at` and address lookups) / `Address: …` (non-empty fields joined by `, `). LF line endings (`\n`, not `Environment.NewLine`). **Culture-invariant formatting.**
- JSON keys exactly as in the original: `latitude`, `longitude`, `accuracy`, `address` (`null` or an object with `street`, `city`, `state`, `postal_code`, `country`); errors are `{"error":"<key>"}`. The stderr messages match the Rust port's `app.rs` word for word.
- Coordinates: split at the first comma, trim, parse as a finite, culture-invariant decimal. `NaN`/`Infinity`/`∞` are usage errors. `--mock` is not range-checked; `--at` must be within −90..90 and −180..180 inclusive.
- `completions` is reserved: stderr `completions are not available in this port`, exit 2.
- 10-second limit on the whole location request (access check + position fix), and it must hold even if an OS call ignores cancellation.
- Address tokens starting with `-` are unknown flags (exit 2). Response-file expansion (`@file`) is off.
- Don't modify `original/` or `rust/`.
- Git: local only. One branch per milestone off `main`, merged with `git merge --no-ff` once its checks pass. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

- **Machine set to another culture** (e.g. de-DE, where `,` is the decimal separator). Output must still print `40.7128` and parsing must still accept `40.7128`. Covered by `CultureTests`.
- **`--bogus` must not be swallowed** by the `string[]` address argument. It must be an unknown flag, exit 2. Covered by the unit test `UnknownFlagIsUsageError` and the case `unknown-flag`.
- **`@home` as an address:** System.CommandLine would otherwise read it as a response file. It must be treated as an address. Covered by the new case `address-at-sign`.
- **A platform call that ignores cancellation** (the MapLocationFinder spike hung inside `.join()` once). The 10 s limit must still hold. Covered by the `App` test `TimeoutHoldsWhenPlatformIgnoresCancellation`.
- **Address text with quotes or non-ASCII characters in JSON.** The output must be valid JSON that round-trips. Covered by the `Output` test `JsonEscapesAddress` (uses the relaxed encoder, so `é` stays readable).

---

## File Structure

```
global.json                                  pins SDK 10.0.402 (rollForward latestFeature)
csharp/Whereami.slnx
csharp/README.md                             Anatomy
csharp/src/Whereami/Whereami.csproj          AssemblyName whereami; TargetFrameworks net10.0;net10.0-windows10.0.19041.0
csharp/src/Whereami/Program.cs               Main: parse → App.RunAsync(native) → write → exit code
csharp/src/Whereami/Model.cs                 Location, Address, GeocodeResult, LocationError, LocationException
csharp/src/Whereami/Output.cs                Human / Json / JsonError
csharp/src/Whereami/CommandLine.cs           System.CommandLine setup → Parsed
csharp/src/Whereami/App.cs                   RunAsync(Request, IPlatform, TimeSpan) → Outcome
csharp/src/Whereami/Platforms/IPlatform.cs   interface + Platforms.Native() + Platforms.OsName
csharp/src/Whereami/Platforms/UnsupportedPlatform.cs
csharp/src/Whereami/Platforms/WindowsPlatform.cs   (#if WINDOWS) Task 4
csharp/tests/Whereami.Tests/Whereami.Tests.csproj   same TargetFrameworks; references src project
csharp/tests/Whereami.Tests/{OutputTests,CommandLineTests,CultureTests,AppTests,WindowsPlatformTests}.cs
```
Add `csharp/**/bin/`, `csharp/**/obj/`, `csharp/out/` to `.gitignore`.

Build/publish command used by conformance:
`dotnet publish csharp/src/Whereami -c Release -f net10.0-windows10.0.19041.0 -o csharp/out/windows` → `csharp/out/windows/whereami.exe`.

---

### Task 1: Solution, model, and output (branch `csharp-m1-scaffold`)

**Files:**
- Create: `global.json`, `csharp/Whereami.slnx`, both `.csproj`s, `Program.cs` (temporary `return 0;`), `Model.cs`, `Output.cs`, `tests/…/OutputTests.cs`, `tests/…/CultureTests.cs`; Modify: `.gitignore`

**Interfaces:**
- Produces:
  - `public readonly record struct Location(double Latitude, double Longitude, double Accuracy);`
  - `public sealed record Address(string Street, string City, string State, string PostalCode, string Country);`
  - `public sealed record GeocodeResult(Location Location, Address Address);`
  - `public enum LocationError { PermissionDenied, LocationUnavailable, Timeout, GeocodingFailed, PlatformUnsupported }` with extension `string JsonKey(this LocationError e)` (`GeocodingFailed` → `unknown_error`)
  - `public sealed class LocationException(LocationError error) : Exception` with `LocationError Error { get; }`
  - `public static class Output { string Human(Location loc, Address? addr, bool showAccuracy); string Json(Location loc, Address? addr); string JsonError(string key); }`. `Json` and `JsonError` end with `\n`.

JSON: `JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower, Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping }`, serialize an anonymous or private record `{ Latitude, Longitude, Accuracy, Address }` so `Address = null` writes `"address":null`. Human: `string.Create(CultureInfo.InvariantCulture, $"…{lat:F4}…")`.

- [ ] **Step 1: Scaffold** with `dotnet new sln --format slnx`, `dotnet new console`, `dotnet new xunit`, `dotnet sln add`, `dotnet add reference`; set `TargetFrameworks`, `AssemblyName`, `Nullable enable`, `ImplicitUsings enable` in both projects; `global.json` `{"sdk":{"version":"10.0.402","rollForward":"latestFeature"}}`.

- [ ] **Step 2: Write failing tests**

```csharp
// OutputTests
[Fact] public void HumanWithAccuracy() =>
    Assert.Equal("Location: 40.7128, -74.0060\nAccuracy: 0m\n", Output.Human(new(40.7128, -74.0060, 0), null, true));
[Fact] public void HumanWithoutAccuracySkipsEmptyFields() =>
    Assert.Equal("Location: 38.8977, -77.0366\nAddress: 1600 Pennsylvania Ave NW, Washington, 20500, US\n",
        Output.Human(new(38.8977, -77.0366, 5), new("1600 Pennsylvania Ave NW", "Washington", "", "20500", "US"), false));
[Fact] public void JsonNullAddress() {
    using var d = JsonDocument.Parse(Output.Json(new(40.7128, -74.006, 0), null));
    Assert.Equal(40.7128, d.RootElement.GetProperty("latitude").GetDouble());
    Assert.Equal(JsonValueKind.Null, d.RootElement.GetProperty("address").ValueKind);
    Assert.Equal(new[] { "latitude", "longitude", "accuracy", "address" }, d.RootElement.EnumerateObject().Select(p => p.Name));
}
[Fact] public void JsonEscapesAddress() {
    var json = Output.Json(new(1, 2, 3), new("5 \"Q\" St\\", "Montréal", "", "H2X", "CA"));
    using var d = JsonDocument.Parse(json);
    var a = d.RootElement.GetProperty("address");
    Assert.Equal("5 \"Q\" St\\", a.GetProperty("street").GetString());
    Assert.Equal("H2X", a.GetProperty("postal_code").GetString());
    Assert.Contains("Montréal", json);
}
[Fact] public void JsonErrorShape() => Assert.Equal("{\"error\":\"timeout\"}\n", Output.JsonError("timeout"));

// CultureTests: set CultureInfo.CurrentCulture = new("de-DE") in the constructor, restore in Dispose
[Fact] public void HumanOutputIsInvariant() =>
    Assert.StartsWith("Location: 40.7128, -74.0060", Output.Human(new(40.7128, -74.0060, 0), null, true));
```

- [ ] **Step 3: Run** `dotnet test csharp`. Expected: build FAIL (`Output` not defined).
- [ ] **Step 4: Implement** `Model.cs` and `Output.cs`.
- [ ] **Step 5: Run** `dotnet test csharp`. Expected: 6 passed per target framework (12 total).
- [ ] **Step 6: Commit**

```powershell
git switch -c csharp-m1-scaffold
git add global.json .gitignore csharp
git commit -m "csharp: add solution, model, and output formatting"
```

---

### Task 2: Argument parsing with System.CommandLine

**Files:**
- Create: `CommandLine.cs`, `tests/…/CommandLineTests.cs`; Modify: `CultureTests.cs`

**Interfaces:**
- Consumes: `Location`.
- Produces:
  - `public abstract record Mode { sealed record Current; sealed record Mock(Location Location); sealed record At(Location Location); sealed record Lookup(string Query); }` (nested records deriving from `Mode`)
  - `public sealed record Request(Mode Mode, bool Json);`
  - `public abstract record Parsed { sealed record Ok(Request Request); sealed record Info(string Text); sealed record UsageError(string Message); }`. `Info` is help/version text for stdout, exit 0; `UsageError` goes to stderr, exit 2.
  - `public static class CommandLine { bool TryParseCoord(string s, out double lat, out double lon); Parsed Parse(string[] args); }`
  - `Platforms.OsName` is needed for the version line. Define `public static class Platforms { public static string OsName => OperatingSystem.IsWindows() ? "windows" : OperatingSystem.IsMacOS() ? "macos" : "linux"; }` in `Platforms/IPlatform.cs` now; Task 3 adds the rest of that file.

System.CommandLine setup:
- `RootCommand` with description "Get your current location using native OS location services, or look up an address or a pair of coordinates."
- Options `--json` (`Option<bool>`), `--mock` and `--at` (`Option<string>`, help name `LAT,LON`).
- Remove the default `VersionOption` and add an `Option<bool>("--version", "-v")`. Its value produces `Info($"whereami 0.4.0 ({Platforms.OsName})\n")`.
- Address: `Argument<string[]>("address") { Arity = ZeroOrMore }`.
- `ParserConfiguration { ResponseFileTokenReplacer = null }`.
- Help: when `parseResult.Action` is a `HelpAction`, invoke it with output redirected to a `StringWriter` and return `Info(text)`.
- Any `parseResult.Errors` → `UsageError` (messages joined).
- Then whereami's own rules, in this order, each returning `UsageError`:
  1. a first address token of `completions`
  2. any address token starting with `-` (`unknown flag: <tok>`)
  3. bad `--mock`/`--at` format
  4. `--at` out of range
  5. address that's empty after joining with `' '` and trimming
  6. address with `--at`/`--mock`
  7. `--at` with `--mock`

`TryParseCoord`: split at the first `,`; `double.TryParse(part.Trim(' '), NumberStyles.Float, CultureInfo.InvariantCulture, out v) && double.IsFinite(v)`.

- [ ] **Step 1: Write failing tests**

```csharp
// CommandLineTests
static Request Ok(params string[] a) => Assert.IsType<Parsed.Ok>(CommandLine.Parse(a)).Request;
static void Usage(params string[] a) => Assert.IsType<Parsed.UsageError>(CommandLine.Parse(a));

[Fact] public void CoordTrims() { Assert.True(CommandLine.TryParseCoord(" 51.5 , -0.1276", out var la, out var lo)); Assert.Equal((51.5, -0.1276), (la, lo)); }
[Theory, InlineData("40.7"), InlineData("abc,def"), InlineData("1,2,3"), InlineData("NaN,0"), InlineData("0,Infinity"), InlineData("0,∞"), InlineData("0x10,0")]
public void CoordRejects(string s) => Assert.False(CommandLine.TryParseCoord(s, out _, out _));
[Fact] public void MockNegative() => Assert.Equal(new Mode.Mock(new(-33.8, 151.2, 0)), Ok("--mock=-33.8,151.2").Mode);
[Fact] public void AtBoundaryOk() => Assert.IsType<Mode.At>(Ok("--at=90,-180").Mode);
[Fact] public void AddressJoined() => Assert.Equal(new Mode.Lookup("350 5th Ave"), Ok("350", "5th", "Ave").Mode);
[Fact] public void AtSignIsAnAddress() => Assert.Equal(new Mode.Lookup("@home"), Ok("@home").Mode);
[Fact] public void JsonAnyPosition() => Assert.True(Ok("--json", "--mock=1,2").Json);
[Fact] public void NothingMeansCurrent() => Assert.IsType<Mode.Current>(Ok().Mode);
[Fact] public void UnknownFlagIsUsageError() => Usage("--bogus");
[Theory, InlineData("--at=91,0"), InlineData("--at=0,-180.5"), InlineData("   "), InlineData("--mock=x")]
public void UsageErrors(string a) => Usage(a);
[Fact] public void Conflicts() { Usage("--at=1,2", "--mock=1,2"); Usage("--mock=1,2", "Paris"); Usage("completions", "bash"); }
[Fact] public void VersionLine() => Assert.Equal($"whereami 0.4.0 ({Platforms.OsName})\n", Assert.IsType<Parsed.Info>(CommandLine.Parse(["-v"])).Text);
[Fact] public void HelpIsInfo() => Assert.Contains("--mock", Assert.IsType<Parsed.Info>(CommandLine.Parse(["--help"])).Text);

// CultureTests (de-DE)
[Fact] public void ParsingIsInvariant() => Assert.Equal(new Mode.Mock(new(40.7128, -74.006, 0)),
    Assert.IsType<Parsed.Ok>(CommandLine.Parse(["--mock=40.7128,-74.006"])).Request.Mode);
```

- [ ] **Step 2: Run** `dotnet test csharp`. Expected: build FAIL (`CommandLine` not defined).
- [ ] **Step 3: Implement** `CommandLine.cs` and the `Platforms.OsName` stub.
- [ ] **Step 4: Run** `dotnet test csharp`. Expected: all pass on both target frameworks.
- [ ] **Step 5: Commit** `git add csharp; git commit -m "csharp: parse arguments with System.CommandLine"`

---

### Task 3: App logic, platform interface, stub, Program — conformance passes

**Files:**
- Create: `App.cs`, `Platforms/UnsupportedPlatform.cs`, `tests/…/AppTests.cs`
- Modify: `Platforms/IPlatform.cs`, `Program.cs`, `conformance/ports.json`, `spec/cases.json`, `PROGRESS.md`

**Interfaces:**
- Consumes: `CommandLine.Parse`, `Parsed`, `Request`, `Mode`, `Output`, model types.
- Produces:
  - `public interface IPlatform { Task<Location> GetLocationAsync(CancellationToken ct); Task<Address?> ReverseGeocodeAsync(double lat, double lon, CancellationToken ct); Task<GeocodeResult?> GeocodeAsync(string query, CancellationToken ct); }`. Failures throw `LocationException`; `GeocodeAsync` returns `null` for no match and throws `LocationException(PlatformUnsupported)` where there's no geocoder.
  - `Platforms.Native() -> IPlatform`: `UnsupportedPlatform` for now; Task 4 adds Windows.
  - `UnsupportedPlatform`: `GetLocationAsync` and `GeocodeAsync` throw `LocationException(PlatformUnsupported)`; `ReverseGeocodeAsync` returns `null`.
  - `public sealed record Outcome(string Stdout, string Stderr, int ExitCode);`
  - `public static class App { public static readonly TimeSpan Timeout = TimeSpan.FromSeconds(10); Task<Outcome> RunAsync(Request req, IPlatform p, TimeSpan timeout); }`

`RunAsync` behaves exactly like the Rust `app::run` (rust/src/app.rs), with the same stderr strings. In C#, the timeout works like this:
- Create a `CancellationTokenSource(timeout)`.
- Call `await p.GetLocationAsync(cts.Token).WaitAsync(timeout)`. `WaitAsync` enforces the limit even if the platform ignores the token.
- Catch `TimeoutException` and `OperationCanceledException` as `Timeout`, and `LocationException e` as `e.Error`.

Program:
```csharp
switch (CommandLine.Parse(args)) {
  case Parsed.Info i: Console.Out.Write(i.Text); return 0;
  case Parsed.UsageError u: Console.Error.Write($"Error: {u.Message}\n"); return 2;
  case Parsed.Ok ok: var o = await App.RunAsync(ok.Request, Platforms.Native(), App.Timeout);
                     Console.Out.Write(o.Stdout); Console.Error.Write(o.Stderr); return o.ExitCode;
}
```
Set `Console.OutputEncoding = new UTF8Encoding(false)` first.

- [ ] **Step 1: Write failing tests** in `AppTests` with a `Fake : IPlatform` (configurable `Func<CancellationToken, Task<Location>>`, `Address?`, `Func<Task<GeocodeResult?>>`), mirroring the Rust app tests:
  - `CurrentOkHuman`: 47.6062, −122.3321, accuracy 12.4 gives stdout `"Location: 47.6062, -122.3321\nAccuracy: 12m\n"`, exit 0.
  - `PermissionDeniedJson`: stdout `{"error":"permission_denied"}\n`, stderr starts with `Error: location permission denied.`, exit 1.
  - `TimeoutHuman`: a fake that throws `LocationException(Timeout)` gives stderr `"Error: location request timed out.\n"`, empty stdout, exit 1.
  - `TimeoutHoldsWhenPlatformIgnoresCancellation`: the fake returns `new TaskCompletionSource<Location>().Task` (never completes, ignores the token). With a 100 ms timeout, the outcome has exit 1 and the timeout message, and it returns in under 2 s (assert with a `Stopwatch`).
  - `CancellationIsTimeout`: the fake awaits `Task.Delay(Timeout.Infinite, ct)`. With 100 ms the result is the timeout message, exit 1.
  - `UnsupportedCurrent`: stderr `"Error: location is not supported on this platform yet.\n"`, exit 1.
  - `MockSkipsReverseGeocode`, `AtNoAddressWarns` (stdout `"Location: 1.0000, 2.0000\n"`, stderr `"Warning: no address found for these coordinates.\n"`, exit 0), `AddressNotFoundJson` (stdout `{"error":"address_not_found"}\n`, stderr contains `couldn't find a location for "Nowhere"`, exit 1), `AddressFoundNoAccuracy`, `AddressUnsupported` (stderr `"Error: address lookup is not supported on this platform.\n"`, exit 1).
- [ ] **Step 2: Run** `dotnet test csharp`. Expected: build FAIL (`App` not defined).
- [ ] **Step 3: Implement** `IPlatform.cs`, `UnsupportedPlatform.cs`, `App.cs`, `Program.cs`.
- [ ] **Step 4: Run** `dotnet test csharp`. Expected: all pass.
- [ ] **Step 5: Add the shared case and port entry.** Append to `spec/cases.json`: `{ "name": "address-at-sign", "platforms": ["windows","linux"], "args": ["@home"], "exit": 1, "stderr_contains": "address lookup is not supported" }`. Add to `conformance/ports.json`: `"csharp": { "path": "csharp/out/windows/whereami", "build": "dotnet publish csharp/src/Whereami -c Release -f net10.0-windows10.0.19041.0 -o csharp/out/windows", "features": { "completions": false } }`.
- [ ] **Step 6: Run conformance for both ports**

```powershell
dotnet publish csharp/src/Whereami -c Release -f net10.0-windows10.0.19041.0 -o csharp/out/windows
cargo build --release --manifest-path rust/Cargo.toml
pwsh conformance/run.ps1
```
Expected: `29 passed, 0 failed, 0 skipped` for both `rust` and `csharp`. Rust needs no change, because clap doesn't expand `@`.
- [ ] **Step 7: Update `PROGRESS.md`**: csharp scaffold ✅, conformance (mock) ✅. Commit and merge:

```powershell
git add csharp spec/cases.json conformance/ports.json PROGRESS.md
git commit -m "csharp: app logic, platform interface, stub; passes conformance"
git switch main; git merge --no-ff csharp-m1-scaffold
```

---

### Task 4: Windows backend (branch `csharp-m2-windows`)

**Files:**
- Create: `Platforms/WindowsPlatform.cs` (wrapped in `#if WINDOWS`), `tests/…/WindowsPlatformTests.cs` (`#if WINDOWS`)
- Modify: `Platforms/IPlatform.cs` (`Native()`: `#if WINDOWS if (OperatingSystem.IsWindows()) return new WindowsPlatform(); #endif`)

**Interfaces:**
- Consumes: `IPlatform`, `LocationException`.
- Produces: `WindowsPlatform : IPlatform`; `internal static LocationError MapException(Exception e)` (make it visible to tests with `[assembly: InternalsVisibleTo("Whereami.Tests")]` or a csproj `<InternalsVisibleTo>` item).

Behavior (same as `rust/src/platform/windows.rs`):
- `await Geolocator.RequestAccessAsync().AsTask(ct)`. Anything other than `GeolocationAccessStatus.Allowed` throws `PermissionDenied`.
- Then `new Geolocator { DesiredAccuracy = PositionAccuracy.High }`, followed by `await geolocator.GetGeopositionAsync(TimeSpan.Zero, App.Timeout).AsTask(ct)`.
- Read `pos.Coordinate.Latitude`, `.Longitude` and `.Accuracy`.
- Wrap the body in `try/catch (Exception e) when (e is not LocationException)` and throw `new LocationException(MapException(e))`.
- `ReverseGeocodeAsync` returns `null`, and `GeocodeAsync` throws `PlatformUnsupported`. Add a comment that MapLocationFinder needs a Bing Maps key and returns `InvalidCredentials` without one (checked 2026-11-23).

`MapException` rules:
- `UnauthorizedAccessException` or HResult `0x80070005` → `PermissionDenied`
- `OperationCanceledException`, `TimeoutException` or HResult `0x800705B4` → `Timeout`
- anything else → `LocationUnavailable`

- [ ] **Step 1: Write failing tests**

```csharp
[Fact] public void AccessDenied() => Assert.Equal(LocationError.PermissionDenied, WindowsPlatform.MapException(new UnauthorizedAccessException()));
[Fact] public void AccessDeniedHResult() => Assert.Equal(LocationError.PermissionDenied, WindowsPlatform.MapException(new COMException("x", unchecked((int)0x80070005))));
[Fact] public void TimeoutHResult() => Assert.Equal(LocationError.Timeout, WindowsPlatform.MapException(new COMException("x", unchecked((int)0x800705B4))));
[Fact] public void Canceled() => Assert.Equal(LocationError.Timeout, WindowsPlatform.MapException(new TaskCanceledException()));
[Fact] public void Other() => Assert.Equal(LocationError.LocationUnavailable, WindowsPlatform.MapException(new COMException("x", unchecked((int)0x80004005))));
```
- [ ] **Step 2: Run** `dotnet test csharp`. Expected: build FAIL on the Windows target (`WindowsPlatform` not defined).
- [ ] **Step 3: Implement** `WindowsPlatform.cs` and the `Native()` switch.
- [ ] **Step 4: Run** `dotnet test csharp`, then republish and run `pwsh conformance/run.ps1`. Expected: everything passes.
- [ ] **Step 5: Live check.** With location allowed, `csharp/out/windows/whereami.exe` and `--json` must give exit 0 and `Location:`/`Accuracy:` lines (the agent can run this; mask the digits in anything it reports). The permission-denied check needs the human: with *Let desktop apps access your location* off, expect `{"error":"permission_denied"}` with `--json`, the denied message, and exit 1. If it says "unavailable" instead, record the exception type and HResult and add a `MapException` test for it. Record the result in `PROGRESS.md`. Windows live is 🟨 until the human check is done.
- [ ] **Step 6: Commit and merge**

```powershell
git add csharp PROGRESS.md
git commit -m "csharp: Windows location via WinRT projection"
git switch main; git merge --no-ff csharp-m2-windows
```

---

### Task 5: Anatomy and comparison (branch `csharp-m3-docs`)

**Files:**
- Create: `csharp/README.md`; Modify: `docs/comparison.md` (C# column), `PROGRESS.md`

- [ ] **Step 1: Write `csharp/README.md`.** Start with build/run/test commands. Then an **Anatomy** section that walks the files in reading order:
  - `global.json`: why pin an SDK when 8 are installed.
  - `Whereami.slnx` and both `.csproj` files: what a target framework is, why there are two, and how `-windows10.0.19041.0` switches on the WinRT projection and the `WINDOWS` symbol.
  - `Program.cs`: top-level `Main` returning the exit code.
  - `Model.cs`: records versus the Rust structs, and an exception versus `Result`.
  - `CommandLine.cs`: how System.CommandLine defines options and arguments, why the default version option is replaced, why exit code 2 is handled by hand, and the response-file and `-`-token pitfalls.
  - `Output.cs`: culture invariance and the snake_case naming policy.
  - `App.cs`: `async`/`await`, `CancellationTokenSource`, and why `WaitAsync` as well.
  - `Platforms/`: `#if WINDOWS` at compile time plus `OperatingSystem.IsWindows()` at run time, compared with Rust's `#[cfg]`.
  - `WindowsPlatform.cs`: the projection makes `Geolocator` an ordinary class, `IAsyncOperation` becomes awaitable via `AsTask`, and HRESULTs arrive as exceptions. Include a table comparing it with `windows.zig` and with the Rust `windows` crate.
  - Finish with a short note on what Linux (`Tmds.DBus`) and macOS (`net10.0-macos` + app bundle) will need.
- [ ] **Step 2: Fill in the C# column** of `docs/comparison.md`, one short cell per row, including the Testability row.
- [ ] **Step 3: Verify** that every relative link in both docs resolves to an existing file (same check as the Rust plan's Task 6).
- [ ] **Step 4: Update `PROGRESS.md`**: csharp Anatomy ✅. Commit and merge:

```powershell
git add csharp/README.md docs/comparison.md PROGRESS.md
git commit -m "csharp: Anatomy walkthrough and comparison column"
git switch main; git merge --no-ff csharp-m3-docs
```
