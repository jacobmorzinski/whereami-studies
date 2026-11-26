# Rust Linux GeoClue Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `whereami` (Rust) on Linux returns the GeoClue2 position over the system D-Bus, with the original's error mapping and a 10 s bound.

**Architecture:** A new `platform/linux.rs` implements the existing `Platform` trait with zbus's blocking API, running the whole GeoClue conversation inside the existing `with_timeout` helper. A pure function maps D-Bus error names to `LocationError`.

**Tech Stack:** Rust 2024, zbus (blocking API), existing clap/serde.

**Spec:** `docs/superpowers/specs/2026-11-26-rust-linux-geoclue-design.md`

**Branch:** `rust-m4-linux` (exists; spec and this plan uncommitted — Task 1 commits them).

## Global Constraints

- Windows behavior must not change; `windows.rs` untouched; `windows` stays a `cfg(windows)`-only dependency.
- `DesktopId` = `"whereami"`; `RequestedAccuracyLevel` = `8`.
- D-Bus names: service `org.freedesktop.GeoClue2`; manager path `/org/freedesktop/GeoClue2/Manager`; interfaces `org.freedesktop.GeoClue2.Manager`, `org.freedesktop.GeoClue2.Client`, `org.freedesktop.GeoClue2.Location`.
- Whole request bounded by the `timeout` passed to `get_location` (10 s from `app`), via `platform::with_timeout`.
- Error messages/keys come from existing `LocationError` (spec/behavior.md); no new variants.
- Linux release build has no warnings.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` (or the implementing model's own name).

## Review Focus

1. **Signal race:** a `LocationUpdated` that arrives between `Start` and subscribing would be lost and the run would time out. Subscribe (create the signal iterator) *before* calling `Start`. Reviewer checks ordering in `linux.rs`.
2. **GeoClue missing or not activatable** (`org.freedesktop.DBus.Error.ServiceUnknown`, no system bus): must give `location_unavailable`, never a panic. Pinned by `map_dbus_error_name` tests (Task 1 Step 1).
3. **No `unwrap`/`expect` on D-Bus results** in `linux.rs`: any property read or call failure becomes a `LocationError`. Reviewer checks.
4. **GNOME location services off:** expected `permission_denied` (`…AccessDenied` from `Start`). Pinned by the `AccessDenied` mapping test; actual behavior recorded by the controller's live check.
5. **Signals from other clients** must not be taken as ours: the match is restricted to our client's object path. Reviewer checks the proxy is built on the client path returned by `CreateClient`.

---

### Task 1: `linux.rs` backend

**Files:**
- Create: `rust/src/platform/linux.rs`
- Modify: `rust/src/platform/mod.rs` (module selection, `native()`, the `#[allow(dead_code)]` note on `with_timeout` — now used by Windows and Linux; restrict the allow to non-Windows-non-Linux or drop it if no longer needed)
- Modify: `rust/Cargo.toml` — `[target.'cfg(target_os = "linux")'.dependencies] zbus = …` (current major; blocking API; keep features minimal)
- Modify: `rust/src/model.rs` only if a now-stale `#[allow(dead_code)]` comment needs updating (don't change variants)
- Create: `assets/whereami.desktop` — byte-identical copy of `original/whereami/assets/whereami.desktop`
- Commit also: `docs/superpowers/specs/2026-11-26-rust-linux-geoclue-design.md`, `docs/superpowers/plans/2026-11-26-rust-linux-geoclue.md`

**Interfaces:**
- Produces: `pub struct Linux;` implementing `Platform`; `fn map_dbus_error_name(name: &str) -> LocationError` (private, tested in-file); `native()` returns `Box::new(linux::Linux)` on Linux.

- [ ] **Step 1: Write the failing tests** in `linux.rs` `#[cfg(test)] mod tests`:
  - `map_dbus_error_name("org.freedesktop.DBus.Error.AccessDenied") == LocationError::PermissionDenied`
  - `map_dbus_error_name("org.freedesktop.DBus.Error.ServiceUnknown") == LocationError::LocationUnavailable`
  - `map_dbus_error_name("org.freedesktop.DBus.Error.Failed") == LocationError::LocationUnavailable`
  - `map_dbus_error_name("") == LocationError::LocationUnavailable`
- [ ] **Step 2: Run** `cargo test --manifest-path rust/Cargo.toml` → fails to compile (function missing).
- [ ] **Step 3: Implement.** `map_dbus_error_name`: `contains("AccessDenied")` → `PermissionDenied`, else `LocationUnavailable`. A helper turning a `zbus::Error` into `LocationError` (method-error name → `map_dbus_error_name`; anything else → `LocationUnavailable`). `get_location` = `super::with_timeout(timeout, move || query())`, where `query()` runs the conversation from the spec's Decisions, in that order, subscribing before `Start`, and calls `Stop` best-effort before returning a location. `reverse_geocode` → `None`; `geocode` → `Err(PlatformUnsupported)`. Module doc comment pointing at `original/whereami/src/platform/linux.zig`, in the style of `windows.rs`.
- [ ] **Step 4: Run tests** — `cargo test --manifest-path rust/Cargo.toml` → all pass (24 existing + 4 new).
- [ ] **Step 5: Warning-free release build** — `cargo build --release --manifest-path rust/Cargo.toml 2>&1 | grep -c warning` → `0`.
- [ ] **Step 6: Windows still compiles** — `rustup target add x86_64-pc-windows-gnu` then `cargo check --manifest-path rust/Cargo.toml --target x86_64-pc-windows-gnu` → finishes with no errors (no linking needed). Report the outcome either way; if the target can't be installed, say so and continue.
- [ ] **Step 7: Conformance** — `bash conformance/run.sh --port rust` → `29 passed, 0 failed, 0 skipped`.
- [ ] **Step 8: Live smoke (location services are on, desktop file installed)** — `rust/target/release/whereami` → `Location: 42.3958, -71.1473` / `Accuracy: 10m`, exit 0; `--json` → `{"latitude":42.3958,"longitude":-71.1473,"accuracy":10,"address":null}` (key order may follow the existing serializer). Paste both outputs and the wall time (`time`) into the report.
- [ ] **Step 9: Commit** — `rust/`, `assets/whereami.desktop`, the spec and plan; message `rust: Linux backend over GeoClue2 (zbus)`.

### Task 2: Docs

(Runs after the controller's live checks; the dispatch carries their results.)

**Files:**
- Modify: `rust/README.md` — platform table Linux row (GeoClue2 via zbus); new Anatomy section after section 8: `src/platform/linux.rs`, explaining the D-Bus conversation, blocking API, the per-client signal match, the timeout, error mapping, and the desktop file (install: `cp assets/whereami.desktop ~/.local/share/applications/`); replace the Linux bullet in "What Linux and macOS will need" (keep the macOS part).
- Modify: `docs/comparison.md` — add a "Linux binding" row: Rust column filled (`zbus` blocking API, `#[proxy]`… as built), other columns `—` (planned).
- Modify: `README.md` — Layout table row for `assets/` ("`whereami.desktop`, which GeoClue uses to recognize the app on Linux; shared by all ports").
- Modify: `PROGRESS.md` — rust Linux cell ✅; dated notes: the live-check results (from dispatch) and the .NET finding (text in spec "Recorded finding").

- [ ] **Step 1: Write the docs.** Every code reference must match `linux.rs` as built (names, line-level claims).
- [ ] **Step 2: Check links** — every relative link added resolves: `for f in …; do test -e …; done` over the added link targets.
- [ ] **Step 3: Commit** — message `rust: document the Linux backend; progress`.
