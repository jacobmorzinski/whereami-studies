# Progress

| Port | scaffold | conformance (mock) | Windows live | Linux | macOS | Anatomy |
|---|---|---|---|---|---|---|
| rust | ✅ | ✅ | ✅ | ⬜ | ⬜ | ✅ |
| csharp | ✅ | ✅ | ✅ | ⬜ | ⬜ | ✅ |
| java | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| fsharp | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| clojure | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |

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
