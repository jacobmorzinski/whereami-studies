# whereami, in several languages

A learning exercise: re-create [`whereami`](https://github.com/georgemandis/whereami)
(George Mandis's Zig CLI that asks the operating system's native location
service where you are) in Rust, C#, Java, and later F# and Clojure. The point is
to see, side by side, **what a simple, idiomatic CLI app looks like in each
language**, including how it calls a native OS service.

## Layout

| Path | What |
|---|---|
| `original/whereami/` | Upstream reference, read-only, not tracked here. Re-create with `git clone https://github.com/georgemandis/whereami original/whereami` (this exercise is based on commit `f4bff19`, version 0.4.0). |
| [`spec/behavior.md`](spec/behavior.md) | The behavior every port implements |
| [`spec/cases.json`](spec/cases.json) | The strict part of the spec, as test cases |
| [`conformance/`](conformance/) | Runner that checks a port's binary against the cases |
| [`PROGRESS.md`](PROGRESS.md) | Which port is where |
| [`docs/comparison.md`](docs/comparison.md) | The ports side by side |
| `rust/`, `csharp/`, `java/`, `fsharp/`, `clojure/` | One standalone project per language; each README has an **Anatomy** walkthrough |
| [`docs/superpowers/`](docs/superpowers/) | Design and implementation plans |

## Checking a port

```powershell
cargo build --release --manifest-path rust/Cargo.toml
pwsh conformance/run.ps1 -Port rust
```

`pwsh conformance/run.ps1` with no `-Port` checks every port listed in
`conformance/ports.json`.

## Credit

`whereami` is © 2026 George Mandis, MIT licensed. These ports follow its
behavior and structure.
