# whereami — C#

A C# port of [whereami](https://github.com/georgemandis/whereami). It asks
the operating system's own location service where you are. Behavior is defined
by [`spec/behavior.md`](../spec/behavior.md). The [Rust port](../rust/README.md)
has the same structure, so the two can be read side by side.

| | |
|---|---|
| Windows | ✅ `Windows.Devices.Geolocation` via .NET's built-in WinRT projection |
| Linux | ✅ GeoClue2 over D-Bus via [`Tmds.DBus`](https://www.nuget.org/packages/Tmds.DBus) (portable `net10.0` build) |
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

On Linux:

```sh
dotnet publish csharp/src/Whereami -c Release -f net10.0 -o csharp/out/linux
csharp/out/linux/whereami --json
dotnet test csharp/tests/Whereami.Tests -f net10.0
bash conformance/run.sh --port csharp
```

On Windows, live location needs *Settings → Privacy & security → Location*
with *Location services* and *Let desktop apps access your location* on.

On Linux, the location comes from GeoClue2. On GNOME, turn *Location
Services* on in *Settings → Privacy*. Installing the desktop file is
recommended (the original does the same), though an Ubuntu 26.04 GNOME session
located without it. From the repo root:

```sh
cp assets/whereami.desktop ~/.local/share/applications/
```

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

  `dotnet build` builds both. `-f` picks one. The Linux backend lives in the
  plain `net10.0` build: the `Tmds.DBus` package reference carries
  `Condition="'$(TargetFramework)' == 'net10.0'"`, so the Windows build
  neither references nor compiles any D-Bus code.
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
#else                                // the portable build: Linux's D-Bus code is here
if (OperatingSystem.IsLinux())
    return new LinuxPlatform(App.Timeout);
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

### 10. [`Platforms/LinuxPlatform.cs`](src/Whereami/Platforms/LinuxPlatform.cs): D-Bus through a library

GeoClue2 is a D-Bus service. [`GeoClue.cs`](src/Whereami/Platforms/GeoClue.cs)
declares the three interfaces we use (`Manager`, `Client`, `Location`) as
C# interfaces annotated with `[DBusInterface("org.freedesktop.GeoClue2.…")]`.
`Tmds.DBus` generates the proxy classes from them at run time, which is why
the interfaces are `public`. This is the classic Tmds.DBus API: async/await,
no source generator.

- **Same conversation as Rust.** `CreateClient` on the manager, `Set` the
  `DesktopId` (`whereami`) and `RequestedAccuracyLevel` (`8`, GeoClue's EXACT
  level, sent as a D-Bus `uint32` so it is a C# `uint`), subscribe to
  `LocationUpdated`, then `Start`. Subscribing first means the first fix can't
  slip past. The proxy sits on our client's own path, so only our signals arrive.
- **Reading a fix.** The signal carries *object paths* of `Location` objects,
  not coordinates. Each path goes onto a `Channel`, and one reader loop reads
  `Latitude`, `Longitude` and `Accuracy` in order. A fix whose properties
  can't be read is dropped, not turned into accuracy 0, which would always
  look best.
- **The best-fix window.** GeoClue may send a coarse fix first and a precise
  one moments later. `BestFixAsync` waits for the first fix, keeps listening
  for 250 ms (never past the deadline) and returns the smallest accuracy; on a
  tie the earlier fix wins. The original takes the first signal. It is a pure
  function over a `ChannelReader`, so
  [`LinuxPlatformTests`](tests/Whereami.Tests/LinuxPlatformTests.cs) tests it
  without a bus.
- **Timeout.** `Tmds.DBus` has no connect timeout and its calls take no
  token, so every call is awaited through `.WaitAsync(ct)`, and the token is
  cancelled after the budget (the platform's constructor argument, because
  `IPlatform` only passes a token). `Stop` is sent in a `finally` on every
  path, with its wait bounded to 30 ms.
- **Errors.** A `DBusException` carries the D-Bus error name; one containing
  `AccessDenied` is `permission_denied`, any other failure (including no bus)
  is `location_unavailable`. An exception filter lets `LocationException` and
  cancellation through, so nothing unexpected escapes.
- **No geocoder.** `ReverseGeocodeAsync` returns `null` and `GeocodeAsync`
  throws `platform_unsupported`, as in the other ports. GeoClue has none.
- **The desktop file.** `DesktopId` is `whereami`, which GeoClue matches with
  [`assets/whereami.desktop`](../assets/whereami.desktop), at the repo root
  because every port shares it.

| | Zig ([`linux.zig`](../original/whereami/src/platform/linux.zig)) | Rust | C# |
|---|---|---|---|
| D-Bus | libdbus C calls | `zbus` blocking proxies | `Tmds.DBus` proxies generated from annotated interfaces |
| Subscribing | `dbus_bus_add_match` rule, then pop messages | `receive_location_updated()` iterator on its own thread | `WatchLocationUpdatedAsync` + `Channel` |
| Waiting | `read_write_dispatch` loop until the timeout | `best_fix` with `recv_timeout` | `BestFixAsync` over `WaitToReadAsync` |
| Timeout | loop deadline | worker thread + `recv_timeout` | `CancelAfter` + `WaitAsync` on every call |
| Errors | error name check | error name → `LocationError` | `DBusException.ErrorName` → `LocationError` |

## What macOS will need

The `net10.0-macos` target framework (the `macos` workload)
  binds CoreLocation and `CLGeocoder`. Its build output is an `.app` bundle
  with `Info.plist`, which CoreLocation requires before it will grant
  permission.
