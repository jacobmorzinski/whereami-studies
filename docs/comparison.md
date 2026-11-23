# The ports side by side

One row per aspect of "a simple CLI app"; one column per port. Filled in as
each port lands. Each port's README has the long version under **Anatomy**.

| Aspect | Rust | C# | Java | F# | Clojure |
|---|---|---|---|---|---|
| Project file | [`Cargo.toml`](../rust/Cargo.toml); OS-only deps via `[target.'cfg(windows)'.dependencies]` | | | | |
| Entry point | [`main.rs`](../rust/src/main.rs): parse → `app::run` → print → `process::exit` | | | | |
| Arg parsing | `clap` derive: a struct *is* the CLI; custom `value_parser`; extra rules via `Command::error` (exit 2) | | | | |
| JSON | `serde` `#[derive(Serialize)]` + `serde_json::to_string` | | | | |
| Per-OS selection | `#[cfg(target_os = …)]`: compile time, other OSes' code not built | | | | |
| Windows binding | `windows` crate (Microsoft-generated WinRT projection), feature `Devices_Geolocation` | | | | |
| Async wait | `IAsyncOperation::join()` blocks | | | | |
| Error handling | `Result` + `?`; HRESULT → `LocationError` in `map_hresult` | | | | |
| Testability | `app::run(&Request, &dyn Platform)` + a `Fake` platform | | | | |
