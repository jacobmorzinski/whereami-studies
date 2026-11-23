# Progress

| Port | scaffold | conformance (mock) | Windows live | Linux | macOS | Anatomy |
|---|---|---|---|---|---|---|
| rust | ✅ | ✅ | 🟨 | ⬜ | ⬜ | ✅ |
| csharp | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| java | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| fsharp | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| clojure | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |

Legend: ⬜ not started · 🟨 in progress · ✅ done · ➖ n/a

## Notes

<!-- dated notes: manual live checks, blockers -->

- 2026-11-23 rust, Windows live: with location allowed, human and `--json`
  output both correct, exit 0. **Still to check by hand:** turn off
  *Let desktop apps access your location*, run `rust/target/release/whereami.exe`,
  expect exit 1 and `Error: location permission denied.` Then mark ✅.
