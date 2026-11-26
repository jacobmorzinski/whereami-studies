# C# milestone 4: Linux backend over GeoClue2 (Tmds.DBus)

A short design (2026-11-26). Follows the Rust reference
([`rust/src/platform/linux.rs`](../../../rust/src/platform/linux.rs)), the
Java port's Linux backend, and [`spec/behavior.md`](../../../spec/behavior.md)
including "Linux fix selection (extension)".

## Intent

The C# port gets a real location on Linux the way a .NET developer would
write it: `async`/`await` over the classic `Tmds.DBus` library, whose proxies
are generated at run time from annotated interfaces. Behavior matches Rust and
Java exactly. F# follows later with the same library.

## Decisions (user, 2026-11-26)

- **Library:** classic `Tmds.DBus` (0.95.2; reflection + runtime code
  generation; no dependencies on .NET 6+), not `Tmds.DBus.Protocol`.
- **Per-OS selection as today:** `#if WINDOWS` (compile time) plus
  `OperatingSystem.Is…()` (run time).

## Decisions (controller's calls, for approval)

- **Linux code only in the portable build.** `Platforms/LinuxPlatform.cs` is
  wrapped in `#if !WINDOWS`, and `Platforms.Native()` returns it when
  `OperatingSystem.IsLinux()`. The `Tmds.DBus` `PackageReference` is
  conditioned on `'$(TargetFramework)' == 'net10.0'`, so the Windows build
  neither references nor compiles D-Bus code — a small step toward optional
  platforms. (The Windows-target build on Linux keeps working for the
  short-term cross-check.)
- **Conversation, timing and errors as Rust/Java:** `new Connection(Address.System)`
  → `ConnectAsync` → `Manager.CreateClientAsync` → set `DesktopId =
  "whereami"` and `RequestedAccuracyLevel = (uint)8` → watch `LocationUpdated`
  on our client's proxy before `StartAsync` → each update's
  `Latitude`/`Longitude`/`Accuracy` into a `Channel<Location>` (updates whose
  properties can't be read are skipped) → after the first fix wait 250 ms,
  clamped to deadline − 50 ms → smallest accuracy, tie → earlier → `Stop` on
  every path (best effort, not awaited past the deadline) → dispose the
  connection. No fix by the deadline → `Timeout`.
- **Deadline:** `IPlatform` only passes a `CancellationToken`, so
  `LinuxPlatform` takes its time budget in the constructor (`Platforms.Native()`
  passes `App.Timeout`) and computes the deadline when the call starts; it also
  honours the token.
- **Errors:** a `DBusException` whose `ErrorName` contains `AccessDenied` →
  `PermissionDenied`; every other failure (no bus, no GeoClue, other D-Bus
  errors, unexpected exceptions from the library) → `LocationUnavailable`,
  following `WindowsPlatform.MapException`'s pattern. Name → error and the fix
  selection are pure `internal static` functions with xUnit tests, mirroring
  Rust's cases.
- Reverse geocode → `null`; geocode → `PlatformUnsupported` (unchanged).

## Verification

Unit tests; `dotnet test -f net10.0`; release publish to `csharp/out/linux`;
`bash conformance/run.sh --port csharp`; the Windows-target build still
compiles on Linux; stderr empty on a live success; probes for no bus /
malformed bus address / session bus (fast `location_unavailable`, spec-only
stderr); manual live checks (location on, location off, desktop file removed).

## Left to the builder

File split inside `Platforms/`, exact Tmds.DBus interface shapes, channel
type.
