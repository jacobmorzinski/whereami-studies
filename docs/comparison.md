# The ports side by side

One row per aspect of "a simple CLI app"; one column per port. Filled in as
each port lands. Each port's README has the long version under **Anatomy**.

| Aspect | Rust | C# | Java | F# | Clojure |
|---|---|---|---|---|---|
| Project file | [`Cargo.toml`](../rust/Cargo.toml); OS-only deps via `[target.'cfg(windows)'.dependencies]` | [`Whereami.csproj`](../csharp/src/Whereami/Whereami.csproj) + [`.slnx`](../csharp/Whereami.slnx); two target frameworks, `-windows10.0.19041.0` adds WinRT; SDK pinned in `global.json` | | | |
| Entry point | [`main.rs`](../rust/src/main.rs): parse → `app::run` → print → `process::exit` | [`Program.cs`](../csharp/src/Whereami/Program.cs): top-level statements, `switch` on `Parsed`, `return` = exit code | | | |
| Arg parsing | `clap` derive: a struct *is* the CLI; custom `value_parser`; extra rules via `Command::error` (exit 2) | `System.CommandLine`: `Option<T>`/`Argument<T>` objects on a `RootCommand`; own exit-2 handling; response files off | | | |
| JSON | `serde` `#[derive(Serialize)]` + `serde_json::to_string` | `System.Text.Json` with `SnakeCaseLower` naming + relaxed encoder | | | |
| Per-OS selection | `#[cfg(target_os = …)]`: compile time, other OSes' code not built | `#if WINDOWS` (compile time) + `OperatingSystem.IsWindows()` (run time) | | | |
| Windows binding | `windows` crate (Microsoft-generated WinRT projection), feature `Devices_Geolocation` | built-in WinRT projection: `new Geolocator { … }` | | | |
| Async wait | `IAsyncOperation::join()` blocks; bounded by a worker thread + `mpsc::recv_timeout` | `await op.AsTask(ct)`; `CancellationTokenSource` + `WaitAsync(timeout)` | | | |
| Error handling | `Result` + `?`; HRESULT → `LocationError` in `map_hresult` | exceptions: `LocationException`; WinRT failures → `MapException` with exception filters | | | |
| Testability | `app::run(&Request, &dyn Platform)` + a `Fake` platform | `App.RunAsync(Request, IPlatform, TimeSpan)` + a `Fake : IPlatform` (xUnit, separate project) | | | |
