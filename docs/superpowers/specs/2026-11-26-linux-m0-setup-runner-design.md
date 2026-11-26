# Linux milestone 0: toolchains, ports.json, bash conformance runner

A short design (approved 2026-11-26). It extends
[`2026-11-23-whereami-ports-design.md`](2026-11-23-whereami-ports-design.md),
whose "Linux testing (later)" section this starts.

## Intent

Get every port building and passing the `--mock`/usage conformance cases on
Linux (Ubuntu 26.04, GNOME), with no pwsh on the machine, before any GeoClue
backend is written. The Windows workflow must not change.

## Goals

- All five ports build on Linux; all non-live conformance cases pass there
  (`version` reports `linux`).
- `conformance/run.sh`: a bash twin of `run.ps1`.
- .NET builds with the SDK Ubuntu ships (10.0.1xx).

## Non-goals

- GeoClue backends (next milestones, one per port).
- Editing `run.ps1`: there is no pwsh here to test it.

## Decisions

- **`global.json`:** pin `10.0.100` with `rollForward: latestFeature`
  (was `10.0.402`). Linux resolves to 10.0.115, Windows keeps 10.0.402.
- **`EnableWindowsTargeting=true`** in `csharp/src/Whereami/Whereami.csproj`
  and `fsharp/src/Whereami/Whereami.fsproj` (and their test projects if they
  multi-target): without it, restore fails on Linux with NETSDK1100. No effect
  on Windows. Verified on a scratch copy: `dotnet build -f net10.0` 0 errors.
- **`ports.json`:** each port gains an optional `"linux"` object whose keys
  (`path`, `build`, `launcher`, `features`) override the top-level ones when
  running on Linux. `run.ps1` ignores the extra key, so Windows is unaffected.
  (`run.ps1` running under pwsh on Linux would still use the Windows values;
  accepted, noted in the README.)
- **`conformance/run.sh`:** same semantics as `run.ps1` — options
  `--port <name>|all` and `--cases-path <file>`; `{os}` substitution;
  `platforms` and `requires` skipping; checks `exit`, `stdout`
  (`exact`/`json`/`empty`/`nonempty`), `stderr_contains`; JSON compared
  structurally with numbers compared as numbers (`jq`); one PASS/FAIL line
  per case, a summary per port, exit 1 if anything failed or is missing.
  Dependencies: bash, jq (both present).
- **Self-check:** `run.sh --port rust --cases-path conformance/runner-selftest.json`
  must report every case FAIL, as for `run.ps1`.
- **README** gains the Linux commands; **PROGRESS.md** notes the milestone.

## Approaches considered

- Per-OS override block in `ports.json` (**picked**): one file, Windows
  untouched, no untested pwsh edits.
- Separate `ports.linux.json`: simpler to read but duplicates every port.
- Per-OS values for every key (`"path": {"windows": …, "linux": …}`): cleanest
  shape but requires changing `run.ps1`, which can't be tested here.

## Location setup found on this machine (for later milestones)

- GeoClue 2.7.2, static source reading `/etc/geolocation` (dev fixed position).
- Following the original: `whereami.desktop` installed (here, in
  `~/.local/share/applications/`), clients set `DesktopId=whereami`, and GNOME
  decides. A Python probe doing exactly that got the static position back
  with no geoclue.conf entry.

## Left to the builder

Script structure, how jq is used, exact wording of MISSING/UNKNOWN lines
(match `run.ps1`).
