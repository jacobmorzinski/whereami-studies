# macOS: platform-layer design notes (pre-planning)

An assessment written on Windows (2026-11-26), before any macOS plan exists.
It is input to the macOS design, not an approved design. It extends
[`2026-11-23-whereami-ports-design.md`](2026-11-23-whereami-ports-design.md).

## Readiness

All five ports already have the same seam: a narrow `Platform` interface
(get location, reverse geocode, geocode), one selection point, and an
"unsupported" stand-in where macOS goes. Mock conformance runs anywhere, and
most macOS code can be type-checked off a Mac; live checks need a Mac.

State on 2026-11-26: all ports rebuilt on Windows after the Linux merges pass
conformance (29/0/0 each), their unit tests, and a live `--json` run.

## What macOS changes for every port

1. **First platform with a geocoder.** `reverse_geocode`/`geocode` and the
   `Address:` output have only run through fakes or the unsupported path.
   Plan conformance cases for address output, and decide how to test them
   without the network (fakes in unit tests; live checks by hand).
2. **CoreLocation needs a run loop and a delegate.** The `CLLocationManager`,
   its delegate and the run loop must all be on one thread. Our timeout
   wrappers (Rust `with_timeout`, .NET cancellation) run work on a worker
   thread, so create the manager there and run the loop there. The original
   does it all on the calling thread with `CFRunLoopRunInMode`
   (`original/whereami/src/platform/macos.zig`).
3. **Permission (verify on the Mac).** A terminal-launched CLI is likely
   attributed to the terminal app by TCC, and authorization requests may need
   `NSLocationWhenInUseUsageDescription` (for a bare binary, an Info.plist
   embedded with `-sectcreate __TEXT __info_plist`). The original embeds no
   Info.plist, so treat this as an open question, not a requirement.
4. **Runner.** `conformance/ports.json` has no `macos` entries, and `run.sh`
   assumes GNU `sed`/coreutils (macOS ships BSD ones). Either select the entry
   by `uname` and make the script portable, or document
   `brew install gnu-sed coreutils`.

## Per language

### Rust: idiomatic as is

Compile-time selection with `#[cfg]` plus
`[target.'cfg(...)'.dependencies]` is the standard Rust approach.

- macOS: `objc2` + `objc2-core-location`; declare the delegate with
  `define_class!` rather than raw `objc_msgSend`.
- Off-Mac check: `cargo check --target aarch64-apple-darwin` should
  type-check the backend.
- Optional: since `#[cfg]` already picks one backend, a `pub use imp::Native`
  alias would drop the `Box<dyn Platform>`. The trait object is fine because
  tests use fakes.

### C#: revisit the selection model before a third OS

Today `#if WINDOWS / #else` does double duty: Windows is the `-windows` target
framework, everything else is `net10.0`.

- Recommendation: put macOS in plain `net10.0`, calling the Objective-C
  runtime through `[LibraryImport]`, marked `[SupportedOSPlatform("macos")]`,
  so the platform-compatibility analyzer (CA1416) enforces
  `OperatingSystem.IsMacOS()` guards.
- Avoid `net10.0-macos`: it targets `.app` bundles and needs Xcode and the
  workload.
- Rule: a target framework only where an OS SDK projection is needed
  (Windows' WinRT); run-time checks plus the analyzer otherwise. Linux's
  D-Bus code already fits.

### F#: idiomatic as is

A record of functions with `Result` errors is the F# way, and record-copy
fakes are good tests. The target-framework choice is the same as C#. CA1416
is a C#/VB analyzer and does not check F#, so keep every `IsMacOS()` guard in
`Native.fs`.

### Java: run-time dispatch is right; make backends optional

Java has no conditional compilation, so choosing from `os.name` is correct.
The issue is that every backend's dependencies ship to every OS (dbus-java is
on the Windows classpath today).

- Recommendation: `ServiceLoader`. Split `platform` into `platform-api` plus
  `platform-windows`, `platform-linux`, `platform-macos`; each provider
  declares `isSupported()`; the app picks the supported one on its classpath.
  This also meets the "platforms should be optional" goal in PROGRESS.md
  (2026-11-26 dotnet note).
- Do it before the macOS backend, since it is structural.
- macOS: FFM to `objc_msgSend`, as the Windows backend does for WinRT; FFM
  upcall stubs can supply the delegate methods. Building an Objective-C class
  at run time is the fiddliest part of any port.

### Clojure: interop is fine; fix the dependency mirroring

Reusing the Java platform is legitimate (a native version would be a protocol
chosen by a `case` or multimethod on the OS). The weak spot is `deps.edn`
listing the Java platform's dependencies by hand ("keep in step").

- Recommendation: have the Java side publish with `maven-publish` /
  `publishToMavenLocal`, and depend on `whereami/platform {:mvn/version ...}`
  so dependencies come from its pom. This matters more if Java gains per-OS
  modules.

## General principles

- Narrow interface; data types live in the general code. Backends only turn
  OS answers into shared types and errors.
- Shared rules stay in the general code, not each backend: the 10 s timeout,
  the 250 ms best-fix window, error mapping.
- One selection point.
- Exclude code at compile time only when it cannot compile or link
  elsewhere; otherwise run-time checks, enforced by an analyzer where one
  exists.
- OS-specific dependencies are conditional too (done in Rust and .NET; not
  yet in Java; by hand in Clojure).
- Always an "unsupported" backend and a mock, so the general code is
  testable on every OS.

## Decisions to settle before writing plans

1. C#/F#: macOS in plain `net10.0` with run-time checks, or its own target
   framework.
2. Java/Clojure: per-OS modules via `ServiceLoader`, and a Maven-style
   dependency from Clojure on the Java platform.
