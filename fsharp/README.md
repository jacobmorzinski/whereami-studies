# whereami — F#

An F# port of [whereami](https://github.com/georgemandis/whereami). It asks
the operating system's own location service where you are. Behavior is defined
by [`spec/behavior.md`](../spec/behavior.md).

This port uses **the same .NET libraries as the [C# port](../csharp/README.md)**:
the same SDK, target frameworks, WinRT projection and JSON serializer. Only
the language changes, so this walkthrough is mostly about what F# does
differently with the same pieces.

| | |
|---|---|
| Windows | ✅ `Windows.Devices.Geolocation` via .NET's built-in WinRT projection |
| Linux | stub (planned: GeoClue2 via [`Tmds.DBus`](https://www.nuget.org/packages/Tmds.DBus), as for C#) |
| macOS | stub (planned: CoreLocation via the `net10.0-macos` bindings, as for C#) |

## Build, run, test

```powershell
dotnet publish fsharp/src/Whereami -c Release -f net10.0-windows10.0.19041.0 -o fsharp/out/windows
fsharp/out/windows/whereami.exe --json
dotnet test fsharp
pwsh conformance/run.ps1 -Port fsharp
```

While developing, `dotnet run --project fsharp/src/Whereami -f net10.0-windows10.0.19041.0 -- --json`
builds and runs in one step.

On Windows, live location needs *Settings → Privacy & security → Location*
with *Location services* and *Let desktop apps access your location* on.

## Anatomy

Read the files in this order, which is also the order the compiler reads them.

### 1. [`Whereami.fsproj`](src/Whereami/Whereami.fsproj): the project, and file order

It has the same properties as the C# `.csproj`: two target frameworks,
`AssemblyName`, `Version`, and `InternalsVisibleTo` for the tests. The
`-windows10.0.19041.0` target works exactly as in C#: it adds the WinRT
projection and defines `WINDOWS`.

The one big difference is the **`<Compile Include="…">` list**. A C# project
compiles every `.cs` file in its folder, in no particular order. An F#
project compiles only the listed files, **in that order**, and each file can
use only what's defined above it:

```
Model.fs → Output.fs → Platform.fs → Windows.fs → Native.fs → CommandLine.fs → App.fs → Program.fs
```

So the dependency graph is visible in the project file, and circular
dependencies between files are impossible.

### 2. [`Model.fs`](src/Whereami/Model.fs): records and a discriminated union

- **Records**: `type Location = { Latitude: float; Longitude: float; Accuracy: float }`.
  They are immutable, with value equality. You create one with
  `{ Latitude = 1.; … }` (no constructor) and copy it with
  `{ loc with Accuracy = 0. }`.
- **`LocationError`** is a **discriminated union** (DU): a value is exactly
  one of `PermissionDenied | LocationUnavailable | Timeout | …`. It's the
  counterpart of a Rust `enum`, and more than a C# `enum`: cases can carry
  data (see `Mode` below), and `match` warns if a case is missing.
- `LocationError.jsonKey` is a function in a **module** with the same name as
  the type. That's the F# habit for "methods" on a type you can't extend,
  where C# would use an extension method.

### 3. [`Output.fs`](src/Whereami/Output.fs): formatting, and one JSON surprise

- `sprintf "Location: %.4f, %.4f"` is F#'s typed `printf` formatting: the
  compiler checks that `%.4f` gets a float. It's always culture-invariant, so
  F# doesn't need C#'s `string.Create(CultureInfo.InvariantCulture, …)`.
  [`CultureTests`](tests/Whereami.Tests/CultureTests.fs) runs under `de-DE`
  to prove it.
- The list expression in `human` is a small F# idiom: `[ line1; if cond then line2; match … ]`
  builds the list of lines, with the conditional ones included only when they apply.
- **JSON** uses the same `System.Text.Json` setup as C#, and it understands F#:
  - Records serialize as objects.
  - `Address option` is written as `null` for `None` and as the object
    itself for `Some`.
- **The surprise:** the JSON record `JsonBody` has to be **public**. F# gives a
  private or internal record non-public properties, and the serializer only
  writes public ones, so the first version printed `{}`. An anonymous record
  `{| … |}` would be public, but F# sorts its fields alphabetically. That
  would have put `accuracy` first, which is why `jsonError`'s one-field
  `{| error = key |}` is fine and the main body isn't.

### 4. [`Platform.fs`](src/Whereami/Platform.fs): a record of functions

C# has `interface IPlatform` and a class per OS. F# uses a **record whose
fields are functions**:

```fsharp
type Platform =
    { GetLocation: CancellationToken -> Task<Result<Location, LocationError>>
      ReverseGeocode: float -> float -> CancellationToken -> Task<Address option>
      Geocode: string -> CancellationToken -> Task<Result<GeocodeResult option, LocationError>> }
```

Two things change compared with C#:
- **Failures are values.** `Result<Location, LocationError>` is either
  `Ok location` or `Error PermissionDenied`, the same as Rust's `Result`. The
  type says the call can fail, and `match` forces the caller to handle both.
  C# threw `LocationException` instead.
- **A fake is a record copy.** The tests build fakes with
  `{ Platform.unsupported with GetLocation = fun _ -> Task.FromResult(Ok loc) }`:
  take the stub and replace one function. No fake class is needed.

### 5. [`Windows.fs`](src/Whereami/Windows.fs) and [`Native.fs`](src/Whereami/Native.fs): calling the OS, choosing the OS

The Windows code reads almost line for line like C#'s
[`WindowsPlatform.cs`](../csharp/src/Whereami/Platforms/WindowsPlatform.cs),
because it uses the same projection:

```fsharp
let! access = Geolocator.RequestAccessAsync().AsTask(ct)
let geolocator = Geolocator(DesiredAccuracy = PositionAccuracy.High)
let! position = geolocator.GetGeopositionAsync(maximumAge = TimeSpan.Zero, timeout = Platform.requestTimeout).AsTask(ct)
```

`Geolocator(DesiredAccuracy = …)` is F#'s spelling of C#'s object
initializer `new Geolocator { DesiredAccuracy = … }`. The projection still
**throws** on failure, so `getLocation` catches once with `try … with e -> return Error(mapException e)`.
That's the boundary where .NET exceptions become F# `Result` values.
`mapException` uses `:?` type-test patterns where C# uses a `switch` on types.

`Native.platform ()` makes the same two checks as C#: `#if WINDOWS` (is the
code in this build?) plus `OperatingSystem.IsWindows()` (are we on Windows?).

### 6. [`CommandLine.fs`](src/Whereami/CommandLine.fs): Argu, a union that *is* the CLI

**Argu** declares the command line as a discriminated union:

```fsharp
[<RequireQualifiedAccess>]
[<HelpFlags("--help", "-h")>]
type Args =
    | Json
    | [<EqualsAssignmentOrSpaced>] Mock of ``LAT,LON``: string
    | [<EqualsAssignmentOrSpaced>] At of ``LAT,LON``: string
    | [<AltCommandLine("-v")>] Version
    | [<MainCommand>] Address of address: string list
```

- Each case becomes a flag: `Mock` becomes `--mock`, and `AltCommandLine`
  adds `-v`.
- `IArgParserTemplate.Usage` supplies the help text.
- The field name is the placeholder in the help: ``` ``LAT,LON`` ``` is a
  backtick identifier, so the help shows `--mock=<LAT,LON>`. With `lat_lon`
  it showed `<lat lon>`.

The other `Mode` type is also a DU, with data:
`Current | Mock of Location | At of Location | Lookup of string`. This is the
F# version of C#'s closed record hierarchy, in four lines.

Things needed attention, as they did with System.CommandLine:
1. **`-h`.** Only `--help` is help unless the type says
   `[<HelpFlags("--help", "-h")>]`.
2. **`--bogus`.** The `[<MainCommand>]` list takes every leftover argument,
   unknown flags included, so `validate` rejects any address word that
   starts with `-`.
3. **Name clash.** `Mock` and `At` are cases of both `Args` and `Mode`. F#
   resolves an unqualified name to the type declared last, so
   `[<RequireQualifiedAccess>]` makes `Args` cases always `Args.Mock`, which
   leaves bare `Mock` meaning `Mode.Mock`.
4. **Exit code 2.** Argu raises `ArguParseException`. `parse` catches it,
   and `ErrorCode.HelpText` becomes `Info` (stdout, exit 0); everything else
   becomes `UsageError` (stderr, exit 2).
5. **Numbers.** `Double.TryParse` with only sign, decimal point and exponent
   allowed, invariant culture, then `Double.IsFinite`. Only spaces are
   trimmed. This is stricter than the C# port, which also accepts tabs.

There's no `return` in F#, so the checks in `validate` are a chain of
`if … elif` and `match` expressions, each of which produces a `Parsed` value.
`match … with | Error message, _ | _, Error message -> …` matches either
side of a pair in one case.

### 7. [`App.fs`](src/Whereami/App.fs): the logic, with one time budget

`App.run : Request -> Platform -> TimeSpan -> Task<Outcome>` returns an
`Outcome` instead of printing, exactly like the other ports.
[`AppTests`](tests/Whereami.Tests/AppTests.fs) drives it with record fakes.

- `task { … }` is F#'s computation expression for .NET tasks:
  - `let!` is `await`.
  - `match!` awaits and matches in one step.
  - `return` produces the result.
- **One timeout for the whole request.** All the platform calls run in one
  `work` task with one `CancellationTokenSource(timeout)`, and `run` awaits
  `work.WaitAsync(timeout)` once. The C# port waits separately on each call,
  so location plus reverse geocoding could take 10 s each.
  `timeoutCoversWholeRequest` pins this: location takes 400 ms, then reverse
  geocoding hangs, and with a 500 ms limit the run must end in under 750 ms.
- A detail of `open`: `open System.Threading` brings .NET's `Timeout` class
  into scope, which hides the `Timeout` case of `LocationError`. So `App.fs`
  writes `LocationError.Timeout`.

### 8. [`Program.fs`](src/Whereami/Program.fs): the entry point

`[<EntryPoint>] let main argv = …` is F#'s `Main`. It must be the last
function in the last file, and its return value is the exit code. The body is
a `match` on `CommandLine.parse argv`, so the same three cases as C# appear:
`Info`, `UsageError` and `Run`. A console app can't be async at the top
level, so it waits with `.GetAwaiter().GetResult()`.

| | Zig ([`windows.zig`](../original/whereami/src/platform/windows.zig)) | Rust (`windows` crate) | C# (projection) | Java (FFM) | F# (projection) |
|---|---|---|---|---|---|
| Create | `RoActivateInstance` + `QueryInterface` | `Geolocator::new()?` | `new Geolocator { … }` | `RoActivateInstance` + `Com.queryInterface` | `Geolocator(DesiredAccuracy = …)` |
| Async | poll `GetStatus` | `.join()` | `await …AsTask(ct)` | poll `get_Status` | `let! … = ….AsTask(ct)` in `task { }` |
| Failure | check each `HRESULT` | `?` → `windows::core::Error` | exceptions | `WinRtException` → `LocationException` | exceptions, caught once → `Result` |

## What Linux and macOS will need

The same as for C#, written in F#. **Linux**: `Tmds.DBus` proxies for
GeoClue2 (`Manager.CreateClient`, `Client.Start`, wait for
`LocationUpdated`). **macOS**: the `net10.0-macos` target framework and an
`.app` bundle with `Info.plist`. Each would be another `#if` file in the
compile list and another branch in `Native.platform`.
