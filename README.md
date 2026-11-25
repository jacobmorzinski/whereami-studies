# whereami, in several languages

A learning exercise: re-create [`whereami`](https://github.com/georgemandis/whereami)
(George Mandis's Zig CLI that asks the operating system's native location
service where you are) in Rust, C#, Java, F# and Clojure. The point is
to see, side by side, **what a simple, idiomatic CLI app looks like in each
language**, including how it calls a native OS service.

## Getting the code

The original is a git submodule, so clone with it:

```powershell
git clone --recurse-submodules <this repository's URL>
```

In a clone made without that flag, fetch it afterwards:

```powershell
git submodule update --init
```

## Layout

| Path | What |
|---|---|
| [`original/whereami/`](original/whereami) | The upstream [whereami](https://github.com/georgemandis/whereami), as a submodule pinned to commit `f4bff19` (version 0.4.0), the version these ports are based on. Read-only reference. |
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

To check the runner itself: `pwsh conformance/run.ps1 -Port rust -CasesPath conformance/runner-selftest.json`
must report every case as FAIL (each one encodes a deliberate mistake).

## Credit and license

`whereami` is © 2026 George Mandis, MIT licensed. These ports follow its
behavior, messages and structure; [`NOTICE`](NOTICE) carries its copyright
and license. This repository's own code is MIT licensed too: see
[`LICENSE`](LICENSE).
