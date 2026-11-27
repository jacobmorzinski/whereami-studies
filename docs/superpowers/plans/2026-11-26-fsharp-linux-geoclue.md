# F# Linux GeoClue Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The F# port returns the GeoClue2 position on Linux, matching the C#, Rust and Java ports.

**Architecture:** `Linux.fs` (portable build only, `#if !WINDOWS`) declares GeoClue2's D-Bus interfaces in F# for classic Tmds.DBus and exposes `Linux.platform : Platform`; two pure `internal` helpers (D-Bus error name → `LocationError`, best-fix selection over a `ChannelReader<Location>`) carry the tested logic.

**Tech Stack:** .NET SDK 10.0.1xx, F#, Tmds.DBus 0.95.2, xUnit (existing test project).

**Spec:** `docs/superpowers/specs/2026-11-26-fsharp-linux-geoclue-design.md` (reference: `csharp/src/Whereami/Platforms/LinuxPlatform.cs` and `GeoClue.cs`; shared rules: `spec/behavior.md`).

**Branch:** `fsharp-m4-linux` (exists; spec and plan uncommitted — Task 1 commits them).

## Global Constraints

- Windows behavior unchanged; `Windows.fs` untouched; Tmds.DBus referenced only for `net10.0`; `-f net10.0-windows10.0.19041.0` still builds on Linux.
- `DesktopId` = `"whereami"`; `RequestedAccuracyLevel` = `8u` (D-Bus `uint32`).
- D-Bus: service `org.freedesktop.GeoClue2`; manager `/org/freedesktop/GeoClue2/Manager`; interfaces `org.freedesktop.GeoClue2.Manager`, `.Client`, `.Location`.
- Best-fix window 250 ms after the first fix, clamped to deadline − 50 ms; smallest accuracy wins, tie → earlier; `Stop` on every path.
- Errors: `AccessDenied` in the D-Bus error name → `PermissionDenied`; everything else → `LocationUnavailable`; no fix by deadline → `Timeout`. No new `LocationError` cases. Failures are `Error` values; no exception escapes `GetLocation`.
- stderr exactly the spec text on every path.
- Commit trailers name the implementing model.

## Review Focus

1. **Tmds.DBus proxy generation from F# interfaces** (generic `GetAsync<'T>`, struct-tuple signal handler): pinned by Step 1's spike; if it fails, stop and report BLOCKED with the exact error — do not work around it with C# code.
2. **Signal race:** the `LocationUpdated` watch awaited before `StartAsync`.
3. **Our client only:** the watch is on the proxy for the path `CreateClient` returned.
4. **No bus / bad address / no GeoClue:** fast `location_unavailable`, spec-only stderr (Step 8 probes).
5. **Late first fix near the deadline** is returned, not lost (deadline clamp test).

---

### Task 1: `Linux.fs`

**Files:**
- Modify: `fsharp/src/Whereami/Whereami.fsproj` (`<PackageReference Include="Tmds.DBus" Version="0.95.2" Condition="'$(TargetFramework)' == 'net10.0'" />` with a comment; `Linux.fs` in the compile list after `Windows.fs`)
- Create: `fsharp/src/Whereami/Linux.fs` (+ a `GeoClue.fs` for the interfaces if cleaner, also before `Native.fs`), under `#if !WINDOWS`
- Modify: `fsharp/src/Whereami/Native.fs` — `Linux.platform` when `OperatingSystem.IsLinux()` in the non-Windows build; update the comment
- Test: `fsharp/tests/Whereami.Tests/LinuxTests.fs` (`#if !WINDOWS`), added to the test project's compile list
- Commit also: spec and plan

**Interfaces:**
- `Linux.platform : Platform`
- `internal mapErrorName : string -> LocationError` (null/empty → `LocationUnavailable`)
- `internal bestFix : ChannelReader<Location> -> grace: TimeSpan -> deadline: int64 (Stopwatch timestamp) -> CancellationToken -> Task<Location option>` — exact shape the builder's call; `None` = no fix by the deadline; writer completed before any fix → `None` or a distinct value mapped to `LocationUnavailable` (document which)

- [ ] **Step 1: Spike the proxies.** Declare the three interfaces (`IManager.CreateClientAsync`, `IClient.StartAsync/StopAsync/SetAsync/WatchLocationUpdatedAsync`, `ILocation.GetAsync<'T>`), and with a throwaway call path run the published binary against the live system bus far enough to get one `LocationUpdated` and read `Latitude`. Record the outcome. If Tmds.DBus rejects the F# interfaces, report BLOCKED with the error text.
- [ ] **Step 2: Failing tests.** Names: `"org.freedesktop.DBus.Error.AccessDenied"` → PermissionDenied; `"org.freedesktop.DBus.Error.ServiceUnknown"`, `"org.freedesktop.DBus.Error.Failed"`, `""`, `null` → LocationUnavailable. Best fix: coarse(25000) then fine(10) in window → fine; single fix → returned, elapsed ≥ grace and < 1 s; equal accuracies → first; fix after window (20 ms window, 200 ms delay) → not chosen; 250 ms grace with deadline 30 ms away → < 150 ms; no fix by a 50 ms deadline → None, elapsed ≥ 40 ms and < 500 ms; writer completed before any fix → per the documented choice.
- [ ] **Step 3: Run** `dotnet test fsharp/tests/Whereami.Tests -f net10.0` → fails.
- [ ] **Step 4: Implement** per the spec's Decisions (`task { }`, `try … with` → `Error`).
- [ ] **Step 5: Tests pass** — same command; paste counts.
- [ ] **Step 6: Builds** — `dotnet publish fsharp/src/Whereami -c Release -f net10.0 -o fsharp/out/linux` (0 warnings) and `dotnet build fsharp/src/Whereami -c Release -f net10.0-windows10.0.19041.0` (0 errors).
- [ ] **Step 7: Conformance** — `bash conformance/run.sh --port fsharp` → 29/0/0.
- [ ] **Step 8: Live + probes** — `time fsharp/out/linux/whereami` → `Location: 42.3958, -71.1473` / `Accuracy: 10m`, exit 0, stderr empty; `--json` same; `DBUS_SYSTEM_BUS_ADDRESS=unix:path=/nonexistent/sock`, `=garbage:foo`, `=unix:path=$XDG_RUNTIME_DIR/bus` → `location_unavailable`, exit 1, < 1 s, stderr exactly the two spec lines.
- [ ] **Step 9: Commit** — `fsharp: Linux backend over GeoClue2 (Tmds.DBus)`.

### Task 2: Docs

(After the controller's live checks.)

- Modify: `fsharp/README.md` (platform table; Linux build/run/test block + GNOME/desktop-file note; Anatomy section for `Linux.fs`, including how the F# interfaces meet Tmds.DBus; replace the Linux part of "What Linux and macOS will need"; mention the conditional package reference), `docs/comparison.md` (F# Linux binding cell and F# Per-OS selection cell), `PROGRESS.md` (fsharp Linux ✅ + dated live-check note).
- [ ] **Step 1: Write**; every claim matches the code. **Step 2:** link check. **Step 3: Commit** — `fsharp: document the Linux backend; progress`.
