# C# Linux GeoClue Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The C# port returns the GeoClue2 position on Linux, matching the Rust and Java ports.

**Architecture:** `Platforms/LinuxPlatform.cs` (portable build only, `#if !WINDOWS`) implements `IPlatform` with classic Tmds.DBus proxies; two pure `internal static` helpers (D-Bus error name → `LocationError`, best-fix selection over a `ChannelReader<Location>`) carry the tested logic.

**Tech Stack:** .NET SDK 10.0.1xx, C#, Tmds.DBus 0.95.2, xUnit (existing test project).

**Spec:** `docs/superpowers/specs/2026-11-26-csharp-linux-geoclue-design.md` (reference: `rust/src/platform/linux.rs`, `java/platform/src/main/java/whereami/platform/linux/LinuxPlatform.java`; shared rules: `spec/behavior.md`).

**Branch:** `csharp-m4-linux` (exists; spec and plan uncommitted — Task 1 commits them).

## Global Constraints

- Windows behavior unchanged; `WindowsPlatform.cs` untouched; Tmds.DBus referenced only for `net10.0`; `-f net10.0-windows10.0.19041.0` still builds on Linux.
- `DesktopId` = `"whereami"`; `RequestedAccuracyLevel` = `(uint)8`.
- D-Bus: service `org.freedesktop.GeoClue2`; manager `/org/freedesktop/GeoClue2/Manager`; interfaces `org.freedesktop.GeoClue2.Manager`, `.Client`, `.Location`.
- Best-fix window 250 ms after the first fix, clamped to deadline − 50 ms; smallest accuracy wins, tie → earlier; `Stop` on every path.
- Errors: `AccessDenied` in the D-Bus error name → `PermissionDenied`; everything else → `LocationUnavailable`; no fix by deadline → `Timeout`. No new `LocationError` values.
- stderr exactly the spec text on every path.
- Commit trailers name the implementing model.

## Review Focus

1. **Signal race:** `WatchLocationUpdatedAsync` (or equivalent) awaited before `StartAsync`.
2. **Our client only:** the watch is on the proxy created for the path `CreateClient` returned.
3. **No bus / bad address / no GeoClue:** fast `location_unavailable`, spec-only stderr (Task 1 Step 7 probes).
4. **Late first fix near the deadline** is returned, not lost (deadline clamp test).
5. **Unhandled exceptions:** nothing from Tmds.DBus escapes as an unexpected-error message or stack trace.

---

### Task 1: `LinuxPlatform`

**Files:**
- Modify: `csharp/src/Whereami/Whereami.csproj` (`<PackageReference Include="Tmds.DBus" Version="0.95.2" Condition="'$(TargetFramework)' == 'net10.0'" />`, with a comment)
- Create: `csharp/src/Whereami/Platforms/LinuxPlatform.cs` (+ a GeoClue interfaces file if cleaner), all under `#if !WINDOWS`
- Modify: `csharp/src/Whereami/Platforms/IPlatform.cs` — `Native()` returns `new LinuxPlatform(App.Timeout)` when `OperatingSystem.IsLinux()` (inside `#if !WINDOWS`); update the comment
- Test: `csharp/tests/Whereami.Tests/LinuxPlatformTests.cs` (`#if !WINDOWS`)
- Commit also: spec and plan

**Interfaces:**
- `public sealed class LinuxPlatform(TimeSpan budget) : IPlatform`
- `internal static LocationError FromDBusErrorName(string? name)`
- `internal static Task<Location?> BestFixAsync(ChannelReader<Location> fixes, TimeSpan grace, DateTime/long deadline, CancellationToken ct)` — null = no fix by deadline (exact deadline type is the builder's call; monotonic preferred, e.g. `Stopwatch` timestamps)

- [ ] **Step 1: Failing tests.** Names: `"org.freedesktop.DBus.Error.AccessDenied"` → PermissionDenied; `"org.freedesktop.DBus.Error.ServiceUnknown"`, `"org.freedesktop.DBus.Error.Failed"`, `""`, `null` → LocationUnavailable. Best fix (small values): coarse(25000) then fine(10) in window → fine; single fix → returned, elapsed ≥ grace and < 1 s; equal accuracies → first; fix after window (20 ms window, 200 ms delay) → not chosen; 250 ms grace with deadline 30 ms away → < 150 ms; no fix by a 50 ms deadline → null, elapsed ≥ 40 ms and < 500 ms; writer completed before any fix → null (or a distinct result mapped to LocationUnavailable — builder's call, document it).
- [ ] **Step 2: Run** `dotnet test csharp/tests/Whereami.Tests -f net10.0` → fails.
- [ ] **Step 3: Implement** per the spec's Decisions.
- [ ] **Step 4: Tests pass** — same command; paste counts.
- [ ] **Step 5: Builds** — `dotnet publish csharp/src/Whereami -c Release -f net10.0 -o csharp/out/linux` (0 warnings) and `dotnet build csharp/src/Whereami -c Release -f net10.0-windows10.0.19041.0` (0 errors).
- [ ] **Step 6: Conformance** — `bash conformance/run.sh --port csharp` → 29/0/0.
- [ ] **Step 7: Live + probes** — `time csharp/out/linux/whereami` → `Location: 42.3958, -71.1473` / `Accuracy: 10m`, exit 0, stderr empty; `--json` same; `DBUS_SYSTEM_BUS_ADDRESS=unix:path=/nonexistent/sock`, `=garbage:foo`, and `=unix:path=$XDG_RUNTIME_DIR/bus` → `location_unavailable`, exit 1, < 1 s, stderr exactly the two spec lines.
- [ ] **Step 8: Commit** — `csharp: Linux backend over GeoClue2 (Tmds.DBus)`.

### Task 2: Docs

(After the controller's live checks.)

- Modify: `csharp/README.md` (platform table; Linux build/run/test block + GNOME/desktop-file note; Anatomy section for `LinuxPlatform.cs`; replace the Linux bullet in any "will need" section; mention the conditional package reference), `docs/comparison.md` (C# Linux cell), `PROGRESS.md` (csharp Linux ✅ + dated live-check note).
- [ ] **Step 1: Write**; every claim matches the code. **Step 2:** link check. **Step 3: Commit** — `csharp: document the Linux backend; progress`.
