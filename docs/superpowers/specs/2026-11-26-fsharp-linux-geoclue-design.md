# F# milestone 4: Linux backend over GeoClue2 (Tmds.DBus)

A short design (approved 2026-11-26). Follows the C# port's Linux backend
([`2026-11-26-csharp-linux-geoclue-design.md`](2026-11-26-csharp-linux-geoclue-design.md),
[`csharp/src/Whereami/Platforms/LinuxPlatform.cs`](../../../csharp/src/Whereami/Platforms/LinuxPlatform.cs)),
the Rust reference ([`rust/src/platform/linux.rs`](../../../rust/src/platform/linux.rs))
and [`spec/behavior.md`](../../../spec/behavior.md), including "Linux fix
selection (extension)".

## Intent

The F# port gets a real location on Linux, written the F# way: the same
classic `Tmds.DBus` library as C#, with GeoClue's interfaces declared in F#,
`task { }` code, and failures returned as `Result` values. Behavior matches
the other ports exactly.

## Approaches

1. **GeoClue interfaces declared in F#, classic Tmds.DBus (picked).** Keeps
   the port self-contained and in F#.
2. Reference the C# port's `GeoClue.cs`: couples two ports, not F#.
3. `Tmds.DBus.Protocol`: the user chose the same library as C#.

**Known risk:** classic Tmds.DBus generates proxies at run time from
annotated interfaces; its use from F# interfaces is unproven here. F#
interfaces compile to ordinary .NET interfaces (the signal argument as
`struct (ObjectPath * ObjectPath)` = `ValueTuple<ObjectPath, ObjectPath>`), so
it is expected to work; the plan proves it first and reports to the user if
it does not.

## Decisions

- `Tmds.DBus` 0.95.2, `PackageReference` conditioned on
  `'$(TargetFramework)' == 'net10.0'`; the Windows build has no D-Bus code.
- New `Linux.fs` (`#if !WINDOWS`), compiled after `Windows.fs` and before
  `Native.fs`; public `[<DBusInterface>]` interfaces for GeoClue2's Manager,
  Client and Location; `Linux.platform : Platform`.
- `Native.platform()` returns `Linux.platform` when `OperatingSystem.IsLinux()`
  (inside `#if !WINDOWS`), keeping `#if WINDOWS` + `IsWindows()` as today.
- Conversation as C#: system bus → `CreateClient` → `DesktopId = "whereami"`,
  `RequestedAccuracyLevel = 8u` → watch `LocationUpdated` on our client's
  proxy before `Start` → fixes into a channel (unreadable updates skipped) →
  after the first fix wait 250 ms, clamped to deadline − 50 ms → smallest
  accuracy, tie → earlier → `Stop` on every path (bounded wait) → dispose the
  connection. Deadline from `Platform.requestTimeout`; also honours the token.
- Errors as `Result`: D-Bus error name containing `AccessDenied` →
  `PermissionDenied`; any other failure → `LocationUnavailable`; no fix by
  the deadline → `Timeout`. No exception escapes `GetLocation`.
- Pure, tested helpers (`internal`): error name → `LocationError`, and best
  fix over a `ChannelReader<Location>` with an injected deadline. Tests in
  `LinuxTests.fs` (`#if !WINDOWS`), the same cases as C#.
- Reverse geocode → `None`; geocode → `Error PlatformUnsupported`.

## Verification

Tests (`-f net10.0`); release publish to `fsharp/out/linux`;
`bash conformance/run.sh --port fsharp`; Windows-target build still compiles
on Linux (short-term cross-check); probes for no bus / malformed address /
session bus (fast `location_unavailable`, spec-only stderr); the user's three
live checks (location on, location off, desktop file removed).

## Left to the builder

Whether the interfaces get their own file, helper names, channel details.
