# The ports side by side

One row per aspect of "a simple CLI app"; one column per port. Filled in as
each port lands. Each port's README has the long version under **Anatomy**.

| Aspect | Rust | C# | Java | F# | Clojure |
|---|---|---|---|---|---|
| Project file | [`Cargo.toml`](../rust/Cargo.toml); OS-only deps via `[target.'cfg(windows)'.dependencies]` | [`Whereami.csproj`](../csharp/src/Whereami/Whereami.csproj) + [`.slnx`](../csharp/Whereami.slnx); two target frameworks, `-windows10.0.19041.0` adds WinRT; SDK pinned in `global.json` | [`settings.gradle.kts`](../java/settings.gradle.kts) + two `build.gradle.kts` (`java-library` + `application`); versions in `libs.versions.toml`; Gradle pinned by the committed wrapper | | |
| Entry point | [`main.rs`](../rust/src/main.rs): parse → `app::run` → print → `process::exit` | [`Program.cs`](../csharp/src/Whereami/Program.cs): top-level statements, `switch` on `Parsed`, `return` = exit code | [`Main.java`](../java/app/src/main/java/whereami/cli/Main.java): `System.exit(commandLine(…).execute(args))` | | |
| Arg parsing | `clap` derive: a struct *is* the CLI; custom `value_parser`; extra rules via `Command::error` (exit 2) | `System.CommandLine`: `Option<T>`/`Argument<T>` objects on a `RootCommand`; own exit-2 handling; response files off | picocli annotations on fields; `@ArgGroup(exclusive)` for `--mock`/`--at`; extra rules throw `ParameterException` (exit 2); `@file` off | | |
| JSON | `serde` `#[derive(Serialize)]` + `serde_json::to_string` | `System.Text.Json` with `SnakeCaseLower` naming + relaxed encoder | Jackson 3 on a private record, `SNAKE_CASE` naming | | |
| Per-OS selection | `#[cfg(target_os = …)]`: compile time, other OSes' code not built | `#if WINDOWS` (compile time) + `OperatingSystem.IsWindows()` (run time) | run time only: `os.name` in `Platforms.nativePlatform()`; every backend compiled everywhere, natives loaded lazily | | |
| Windows binding | `windows` crate (Microsoft-generated WinRT projection), feature `Devices_Geolocation` | built-in WinRT projection: `new Geolocator { … }` | **by hand**: FFM downcalls into `combase.dll`, COM vtable slots, explicit `Release` | | |
| Async wait | `IAsyncOperation::join()` blocks; bounded by a worker thread + `mpsc::recv_timeout` | `await op.AsTask(ct)`; `CancellationTokenSource` + `WaitAsync(timeout)` | poll `IAsyncInfo.Status` every 50 ms; whole request on one daemon platform thread + `CompletableFuture.get(timeout)` | | |
| Error handling | `Result` + `?`; HRESULT → `LocationError` in `map_hresult` | exceptions: `LocationException`; WinRT failures → `MapException` with exception filters | checked `LocationException` (`throws` in signatures); `HRESULT` → `WinRtException` → `HResults.toError` | | |
| Testability | `app::run(&Request, &dyn Platform)` + a `Fake` platform | `App.RunAsync(Request, IPlatform, TimeSpan)` + a `Fake : IPlatform` (xUnit, separate project) | `App.run(Request, Platform, Duration)` + a `Fake`; COM calls tested against a fake vtable of upcall stubs | | |
