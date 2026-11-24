# whereami — C#

A C# port of [whereami](https://github.com/georgemandis/whereami). It asks
the operating system's own location service where you are. Behavior is defined
by [`spec/behavior.md`](../spec/behavior.md). The [Rust port](../rust/README.md)
has the same structure, so the two can be read side by side.

| | |
|---|---|
| Windows | ✅ `Windows.Devices.Geolocation` via .NET's built-in WinRT projection |
| Linux | stub (planned: GeoClue2 via [`Tmds.DBus`](https://www.nuget.org/packages/Tmds.DBus)) |
| macOS | stub (planned: CoreLocation via the `net10.0-macos` bindings) |

## Build, run, test

```powershell
dotnet publish csharp/src/Whereami -c Release -f net10.0-windows10.0.19041.0 -o csharp/out/windows
csharp/out/windows/whereami.exe --json
dotnet test csharp
pwsh conformance/run.ps1 -Port csharp
```

While developing, `dotnet run --project csharp/src/Whereami -f net10.0-windows10.0.19041.0 -- --json`
builds and runs in one step. Everything after `--` goes to whereami.

On Windows, live location needs *Settings → Privacy & security → Location*
with *Location services* and *Let desktop apps access your location* on.

## Anatomy

Read the files in this order.

### 1. [`global.json`](../global.json): which SDK

This machine has eight .NET SDKs installed. `global.json` (at the repo root,
so it applies to everything below it) pins `10.0.402`, and
`rollForward: latestFeature` allows newer 10.0.x patches. Without it, `dotnet`
picks the newest SDK installed, and that can change under you.

### 2. [`Whereami.slnx`](Whereami.slnx) and the `.csproj` files: the projects

- **`.slnx`** is the solution: a list of projects that build together. It's
  the new XML format; older solutions use `.sln`. `dotnet test csharp` finds
  it and runs every test project in it.
- **[`src/Whereami/Whereami.csproj`](src/Whereami/Whereami.csproj)** is the app.
  It has no list of source files: every `.cs` file under the folder is
  included. `AssemblyName` makes the executable `whereami.exe`.
  `ImplicitUsings` adds common `using`s (`System`, `System.Linq`, …), and
  `Nullable` makes the compiler track which references can be `null`
  (`Address?`).
- **Two target frameworks**: `net10.0;net10.0-windows10.0.19041.0`. A target
  framework is the platform you compile against. Plain `net10.0` runs on any
  OS. The `-windows10.0.19041.0` suffix does two things:
  1. It adds the **WinRT projection**: every `Windows.*` API, generated from
     Windows metadata and exposed as ordinary .NET classes.
  2. It defines the `WINDOWS` compile-time symbol, so `#if WINDOWS` code
     exists only in that build.

  `dotnet build` builds both. `-f` picks one.
- **[`tests/Whereami.Tests/Whereami.Tests.csproj`](tests/Whereami.Tests/Whereami.Tests.csproj)**:
  tests live in a separate project, as is usual in .NET. It references the app
  project and uses xUnit (`[Fact]` is one test, `[Theory]` + `[InlineData]` is
  one test per input row). `InternalsVisibleTo` in the app's csproj lets tests
  call `internal` members such as `WindowsPlatform.MapException`.

### 3. [`Program.cs`](src/Whereami/Program.cs): the entry point

"Top-level statements": the file *is* `Main`, with `args` in scope and
`return` giving the exit code. It's a `switch` over what the parser
returned:
- `Info`: print, exit 0.
- `UsageError`: stderr, exit 2.
- `Ok`: run the app, print its `Outcome`, exit with its code.

`await` works at the top level too.

### 4. [`Model.cs`](src/Whereami/Model.cs): the data

- **`record`** gives a type value equality, `ToString` and `with`-copies for
  free (`record struct` for the small `Location`). That's the counterpart of
  Rust's `#[derive(PartialEq, Debug, Clone)]`.
- **Errors are exceptions.** Rust returns `Result<Location, LocationError>`;
  idiomatic C# throws `LocationException` with a `LocationError` inside. The
  type signature doesn't show that a call can fail, so `IPlatform`'s doc
  comments say so.
- `JsonKey()` is an **extension method**: a static method that reads like an
  instance method (`error.JsonKey()`), because enums can't have methods of
  their own.

### 5. [`CommandLine.cs`](src/Whereami/CommandLine.cs): argument parsing

**`Mode` and `Parsed`** are "closed hierarchies": an abstract record with a
private constructor and nested `sealed record` cases. That's C#'s way of
writing a Rust `enum` with data, and `switch` patterns
(`case Mode.Lookup(var query):`) pull the data back out.

**System.CommandLine** defines the CLI as objects:
`new Option<string>("--mock") { Description = …, HelpName = "LAT,LON" }`,
`new Argument<string[]>("address")`. These are added to a `RootCommand`, and
`root.Parse(args)` returns a `ParseResult` to query (`GetValue(option)`).
It also generates `--help`.

Four things needed attention:
1. **Exit code 2.** System.CommandLine's own error handling exits 1, so
   `Parse` never lets it handle anything. It returns `Parsed.UsageError` and
   `Program` exits 2.
2. **Version.** The built-in `--version` prints the assembly version with
   no `-v`, so it's removed and replaced by a plain `Option<bool>("--version", "-v")`.
3. **Response files.** By default `@something` means "read more arguments
   from the file *something*". `ResponseFileTokenReplacer = null` turns that
   off, so `whereami @home` is an address.
4. **Culture.** `double.TryParse` follows the machine's language settings
   unless told otherwise. On a German machine `"40.7128"` would fail, so it
   parses with `CultureInfo.InvariantCulture`. [`CultureTests`](tests/Whereami.Tests/CultureTests.cs)
   runs under `de-DE` to prove it.

### 6. [`Output.cs`](src/Whereami/Output.cs): formatting

- `string.Create(CultureInfo.InvariantCulture, $"…{lat:F4}…")` formats an
  interpolated string in invariant culture. Plain `$"…"` would print
  `40,7128` on a German machine.
- JSON: `JsonSerializer.Serialize` on a private record, with
  `JsonNamingPolicy.SnakeCaseLower` (so `PostalCode` becomes `"postal_code"`)
  and the relaxed encoder, so `é` isn't written as `é`. A `null`
  `Address` becomes `"address":null` by default.

### 7. [`App.cs`](src/Whereami/App.cs): the logic

`RunAsync(Request, IPlatform, TimeSpan)` returns an `Outcome`
`(Stdout, Stderr, ExitCode)` instead of printing, exactly like Rust's
`app::run`. [`AppTests`](tests/Whereami.Tests/AppTests.cs) drives it with a
`Fake` platform.

**async/await and the timeout** use two separate mechanisms:
- `new CancellationTokenSource(timeout)` makes a token that "fires" after
  10 s. Platforms pass it on, and the WinRT call gets cancelled.
- `.WaitAsync(timeout)` stops *us* waiting even if a platform ignores the
  token. The MapLocationFinder experiment showed a WinRT call can hang
  forever. `TimeoutHoldsWhenPlatformIgnoresCancellation` tests exactly that.

Rust gets the same guarantee with a worker thread and `recv_timeout`.

`catch (Exception e) when (ErrorOf(e) is { } error)` is an **exception
filter**: it catches only exceptions that map to a whereami error and lets
real bugs crash loudly.

### 8. [`Platforms/IPlatform.cs`](src/Whereami/Platforms/IPlatform.cs): choosing the OS

`Native()` checks twice:

```csharp
#if WINDOWS                          // compile time: is the Windows code in this build?
if (OperatingSystem.IsWindows())     // run time: are we on Windows?
    return new WindowsPlatform();
#endif
return new UnsupportedPlatform();
```

Rust's `#[cfg(target_os = "windows")]` covers both, because a Rust binary is
built for exactly one OS. A .NET `net10.0` build is OS-neutral and could run
anywhere, hence the run-time check as well.

### 9. [`Platforms/WindowsPlatform.cs`](src/Whereami/Platforms/WindowsPlatform.cs): calling the OS

```csharp
var access = await Geolocator.RequestAccessAsync().AsTask(ct);
var geolocator = new Geolocator { DesiredAccuracy = PositionAccuracy.High };
var position = await geolocator.GetGeopositionAsync(TimeSpan.Zero, App.Timeout).AsTask(ct);
var c = position.Coordinate;
```

The projection makes WinRT feel native to .NET: `Geolocator` is a class you
`new`, properties are properties, and `AsTask(ct)` turns a WinRT
`IAsyncOperation<T>` into a `Task<T>` you can `await` and cancel.

| | Zig ([`windows.zig`](../original/whereami/src/platform/windows.zig)) | Rust (`windows` crate) | C# (projection) |
|---|---|---|---|
| Get the API | hand-written GUIDs + vtable structs | crate, feature `Devices_Geolocation` | target framework `-windows10.0.19041.0` |
| Create | `RoActivateInstance` + `QueryInterface` | `Geolocator::new()?` | `new Geolocator { … }` |
| Lifetime | `Release` by hand (`defer`) | `Drop` | garbage collector |
| Async | poll `GetStatus` in a loop | `.join()` (blocking) | `await …AsTask(ct)` (non-blocking, cancellable) |
| Failure | check each `HRESULT` | `?` → `windows::core::Error` | exceptions (`UnauthorizedAccessException`, `COMException`) |

`MapException` turns those exceptions into whereami's error kinds: access
denied → permission denied, cancellation or `ERROR_TIMEOUT` → timeout,
anything else → unavailable.

## What Linux and macOS will need

- **Linux**: `Tmds.DBus` generates typed C# proxies for D-Bus interfaces.
  GeoClue2 needs `Manager.CreateClient`, then setting `DesktopId` and
  `RequestedAccuracyLevel`, then `Client.Start` and waiting for
  `LocationUpdated` (see [`linux.zig`](../original/whereami/src/platform/linux.zig)).
  It would be a third target framework or a runtime branch on `OperatingSystem.IsLinux()`.
- **macOS**: the `net10.0-macos` target framework (the `macos` workload)
  binds CoreLocation and `CLGeocoder`. Its build output is an `.app` bundle
  with `Info.plist`, which CoreLocation requires before it will grant
  permission.
