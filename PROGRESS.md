# Progress

| Port | scaffold | conformance (mock) | Windows live | Linux | macOS | Anatomy |
|---|---|---|---|---|---|---|
| rust | ✅ | ✅ | ✅ | ⬜ | ⬜ | ✅ |
| csharp | ✅ | ✅ | ✅ | ⬜ | ⬜ | ✅ |
| java | ✅ | ✅ | ✅ | ⬜ | ⬜ | ✅ |
| fsharp | ✅ | ✅ | ✅ | ⬜ | ⬜ | ✅ |
| clojure | ✅ | ✅ | 🟨 | ⬜ | ⬜ | ⬜ |

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
  warning: the jar manifest enables native access). **Still to check by
  hand:** with *Let desktop apps access your location* off,
  `java -jar clojure/target/whereami.jar --json` should print
  `{"error":"permission_denied"}` and the denied message, exit 1. Windows
  may first show a "Java is requesting permission" dialog, as for the Java
  port. Then mark ✅.
