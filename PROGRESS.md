# Progress

| Port | scaffold | conformance (mock) | Windows live | Linux | macOS | Anatomy |
|---|---|---|---|---|---|---|
| rust | ✅ | ✅ | ✅ | ✅ | ⬜ | ✅ |
| csharp | ✅ | ✅ | ✅ | ✅ | ⬜ | ✅ |
| java | ✅ | ✅ | ✅ | ✅ | ⬜ | ✅ |
| fsharp | ✅ | ✅ | ✅ | ✅ | ⬜ | ✅ |
| clojure | ✅ | ✅ | ✅ | ✅ | ⬜ | ✅ |

Legend: ⬜ not started · 🟨 in progress · ✅ done · ➖ n/a

## Notes

<!-- dated notes: manual live checks, blockers -->

- 2026-11-23 rust, Windows live: with location allowed, human and `--json`
  output both correct, exit 0. With *Let desktop apps access your location*
  off: `{"error":"permission_denied"}` + `Error: location permission denied.`,
  exit 1 (checked by hand).
- 2026-11-23 rust: whole Windows request (access check + fix) now bounded by
  the 10 s timeout, matching the original.
- 2026-11-23 csharp, Windows live: with location allowed, human and `--json`
  output both correct, exit 0. With *Let desktop apps access your location*
  off: correct permission-denied error (checked by hand).
- 2026-11-23 csharp: `Console.OutputEncoding = UTF-8` does not leave the
  calling console changed — `cmd /c chcp` still reports 437 after a run
  (checked by hand in Windows Terminal).
- 2026-11-23 java, Windows live: with location allowed, human and `--json`
  output both correct (same position as the Rust port), exit 0, nothing on
  stderr. With *Let desktop apps access your location* off: correct
  permission-denied error (checked by hand). One run instead showed a Windows
  dialog, "Java is requesting permission". Left unanswered, it gave
  `Error: location request timed out.` after 10 s, which is correct: Windows
  asks per app, and the app here is `java.exe`.
- 2026-11-23 fsharp, Windows live: with location allowed, human and `--json`
  output both correct (same position as the Rust port), exit 0, nothing on
  stderr. With *Let desktop apps access your location* off: correct
  permission-denied error (checked by hand).
- 2026-11-24 clojure, Windows live (the Java port's WindowsPlatform, through
  interop): with location allowed, human and `--json` output both correct
  (same position as the Rust port), exit 0, nothing on stderr (no FFM
  warning: the jar manifest enables native access). With *Let desktop apps access your location* off:
  correct permission-denied error (checked by hand).
- 2026-11-26 all ports: build and pass mock/usage conformance on Linux (Ubuntu 26.04) via conformance/run.sh.
- 2026-11-26 rust, Linux live (Ubuntu 26.04 GNOME, GeoClue 2.7.2, static
  source 42.3958, -71.1473): location on gives `Location: 42.3958, -71.1473` /
  `Accuracy: 10m`, exit 0, about 0.26 s; `--json` gives the same position,
  exit 0. With GNOME location services off
  (`org.gnome.system.location enabled false`): `{"error":"permission_denied"}`
  + `Error: location permission denied.`, exit 1, about 7 ms. With
  `~/.local/share/applications/whereami.desktop` removed it still returned the
  location, exit 0, and no GNOME prompt appeared, so on this GNOME setup the
  desktop file isn't required for a terminal-launched app. We still recommend
  installing it, as the original does; other desktops or GeoClue configs may
  need it.
- 2026-11-26 rust: GeoClue sent a coarse BeaconDB/IP fix (about 25 km, ~1 ms)
  and then the static 10 m fix ~5 ms later, hence the 250 ms best-fix window
  (the original takes the first signal). With only the IP source there is one
  update (cold about 0.46 s, warm about 2 ms).
- 2026-11-26 dotnet finding: building `-f net10.0-windows10.0.19041.0` on Linux
  compiles the C#/F# Windows code. Clean code gives 0 errors; a deliberately
  wrong WinRT member (`PositionAccuracy.Highest`, `GetGeopositionAsyncX`) was
  reported with file and line. The `net10.0` build does not compile the
  Windows code. Likewise `cargo check --target x86_64-pc-windows-gnu` on Linux
  checked the Rust Windows code (clean). This cross-check is a short-term aid
  during Linux development; long term, platforms should be optional (building
  on Linux shouldn't need the Windows target).
- 2026-11-26 java, Linux live (Ubuntu 26.04 GNOME, GeoClue 2.7.2, static
  source 42.3958, -71.1473; dbus-java 5.2.2): location on gives
  `Location: 42.3958, -71.1473` / `Accuracy: 10m`, `--json` the same position,
  exit 0, stderr empty, about 0.77 s (mostly JVM start). With GNOME location
  services off: `{"error":"permission_denied"}` + `Error: location permission
  denied.`, exit 1, about 0.36 s. With the desktop file removed it still
  located, exit 0, no GNOME prompt. The Clojure uberjar passed the same three
  checks through the Java platform (about 1.05 s, 0.81 s).
- 2026-11-26 clojure, Linux live (the Java port's LinuxPlatform, through
  interop; Ubuntu 26.04 GNOME, GeoClue 2.7.2, static source 42.3958, -71.1473;
  uberjar): location on gives `Location: 42.3958, -71.1473` / `Accuracy: 10m`,
  `--json` the same, exit 0, stderr empty, about 1.05 s. With GNOME location
  services off: `{"error":"permission_denied"}` + `Error: location permission
  denied.`, exit 1, about 0.81 s. With the desktop file removed it still
  located, no GNOME prompt. Tests 49 pass; conformance 29/0/0. The only
  Linux change in this port is the dbus-java and slf4j-nop entries in deps.edn.
- 2026-11-26 csharp, Linux live (Ubuntu 26.04 GNOME, GeoClue 2.7.2, static
  source 42.3958, -71.1473; Tmds.DBus 0.95.2, `net10.0` build): location on
  gives `Location: 42.3958, -71.1473` / `Accuracy: 10m`, exit 0, about 0.47 s;
  `--json` gives `{"latitude":42.3958,"longitude":-71.1473,"accuracy":10,"address":null}`
  in about 0.51 s; stderr empty. With GNOME location services off:
  `permission_denied`, exit 1, about 0.2 s, spec stderr only. With the desktop
  file removed it still located, same as with it, no GNOME prompt. Tests 53
  pass; conformance 29/0/0. The Windows-target build still compiles on Linux
  (short-term cross-check only).
- 2026-11-26 fsharp, Linux live (Ubuntu 26.04 GNOME, GeoClue 2.7.2, static
  source 42.3958, -71.1473; Tmds.DBus 0.95.2, `net10.0` build): location on
  gives `Location: 42.3958, -71.1473` / `Accuracy: 10m`, exit 0, about 0.72 s;
  `--json` gives `{"latitude":42.3958,"longitude":-71.1473,"accuracy":10,"address":null}`
  in about 0.71 s; stderr empty. With GNOME location services off:
  `permission_denied`, exit 1, about 0.45 s, spec stderr only. With the desktop
  file removed it still located, same as with it, no GNOME prompt. Tests 59
  pass; conformance 29/0/0. The F# interfaces needed no Tmds.DBus workaround.
  The Windows-target build still compiles on Linux (short-term cross-check
  only). All five ports now work on Linux; macOS is still open for all.
