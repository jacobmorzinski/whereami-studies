# Linux M0: Toolchains and Bash Conformance Runner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** All five ports build, unit-test and pass the non-live conformance cases on Linux, checked by a new bash runner.

**Architecture:** Two small .NET config changes let Ubuntu's SDK build the
ports; `ports.json` gains per-port `"linux"` override blocks; `conformance/run.sh`
re-implements `run.ps1` in bash + jq with identical semantics.

**Tech Stack:** bash, jq 1.7+, .NET SDK 10.0.115, JDK 25 + Gradle wrapper, Clojure CLI, cargo.

**Spec:** `docs/superpowers/specs/2026-11-26-linux-m0-setup-runner-design.md`
(extends `docs/superpowers/specs/2026-11-23-whereami-ports-design.md`).

**Branch:** `linux-m0-setup-runner` (already created; design doc on it, uncommitted).

## Global Constraints

- Windows behavior must not change: `run.ps1` is not edited; top-level `ports.json` values stay as they are.
- `global.json`: `"version": "10.0.100"`, `"rollForward": "latestFeature"`.
- `run.sh` depends only on bash and jq.
- Same CLI as `run.ps1`: `--port <name>` (default `all`), `--cases-path <file>` (default `spec/cases.json`).
- Output lines match `run.ps1`: `== <name> (<command>)`, `PASS <case>`, `FAIL <case>: <why>`, `<p> passed, <f> failed, <s> skipped`, `MISSING <name>: build with <build>`, `UNKNOWN port: <name>`; exit 1 if any FAIL/MISSING/UNKNOWN.
- On Linux `{os}` is `linux`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Trailing newlines in stdout.** `$(…)` strips them, so `exact` cases (`"whereami 0.4.0 (linux)\n"`) would compare wrongly. Capture stdout/stderr to temp files and compare bytes. Pinned by: the `version` cases pass *and* a selftest-style check that `"exact": "whereami 0.4.0 ({os})"` (no `\n`) FAILs (Task 2, Step 2).
2. **Arguments with spaces and odd characters** (`--mock= 51.5 , -0.1276`, multi-word addresses, `@`). Args must reach the port as separate argv entries, unsplit and unglobbed (read with `jq` NUL-separated into a bash array). Pinned by `mock-spaces`, `address-multiword`, `address-at-sign` passing.
3. **Non-JSON stdout in a `json` case** must give `FAIL …: stdout is not JSON: …`, not abort the script (no `set -e` surprises). Pinned by Task 2, Step 2 extra case.
4. **One missing port must not stop the others:** `MISSING` line, continue, exit 1. Pinned by Task 2, Step 6.
5. **JSON equality:** structural, key-order-insensitive, numbers as numbers (`-74.006` == `-74.0060`), strings case-sensitive. Pinned by `mock-json*` passing and all three `runner-selftest.json` cases failing.

---

### Task 1: Every port builds and unit-tests on Linux

**Files:**
- Modify: `global.json` (version → `10.0.100`)
- Modify: `csharp/src/Whereami/Whereami.csproj`, `csharp/tests/Whereami.Tests/Whereami.Tests.csproj`, `fsharp/src/Whereami/Whereami.fsproj`, `fsharp/tests/Whereami.Tests/Whereami.Tests.fsproj` — add `<EnableWindowsTargeting>true</EnableWindowsTargeting>` next to `<TargetFrameworks>`, with a one-line XML comment: lets non-Windows hosts restore the Windows target; no effect on Windows.
- Modify: `java/gradlew` mode → executable (`git update-index --chmod=+x java/gradlew` and `chmod +x`)

**Interfaces:**
- Produces: these Linux build commands and outputs, used by Task 2's `ports.json`:

| Port | Build command | Output |
|---|---|---|
| rust | `cargo build --release --manifest-path rust/Cargo.toml` | `rust/target/release/whereami` |
| csharp | `dotnet publish csharp/src/Whereami -c Release -f net10.0 -o csharp/out/linux` | `csharp/out/linux/whereami` |
| java | `java/gradlew -p java :app:installDist` | `java/app/build/install/whereami/bin/whereami` |
| fsharp | `dotnet publish fsharp/src/Whereami -c Release -f net10.0 -o fsharp/out/linux` | `fsharp/out/linux/whereami` |
| clojure | `java/gradlew -p java :platform:jar && (cd clojure && clojure -T:build uber)` | `clojure/target/whereami.jar`, launcher `["java","-jar"]` |

- [ ] **Step 1: Confirm the .NET failure** — `dotnet --version` in the repo root. Expected: error "A compatible .NET SDK was not found".
- [ ] **Step 2: Apply the `global.json` and project-file changes.**
- [ ] **Step 3: Build and test .NET** — `dotnet test csharp/tests/Whereami.Tests -f net10.0` and same for `fsharp/tests/Whereami.Tests`. Expected: all tests pass. Then run the two `dotnet publish` commands above; expected: `csharp/out/linux/whereami --version` prints `whereami 0.4.0 (linux)` (same for fsharp).
- [ ] **Step 4: Java** — make `gradlew` executable, then `java/gradlew -p java test :app:installDist`. Expected: BUILD SUCCESSFUL; `java/app/build/install/whereami/bin/whereami --version` → `whereami 0.4.0 (linux)`.
- [ ] **Step 5: Clojure** — `java/gradlew -p java :platform:jar`, then in `clojure/`: `clojure -X:test` (expected: 0 failures) and `clojure -T:build uber`; `java -jar clojure/target/whereami.jar --version` → `whereami 0.4.0 (linux)`.
- [ ] **Step 6: Rust** — `cargo test --manifest-path rust/Cargo.toml` and the release build; `rust/target/release/whereami --version` → `whereami 0.4.0 (linux)`.
- [ ] **Step 7: Check nothing generated is tracked** — `git status --short` shows only the intended files (`out/linux`, `build/`, `target/` ignored; if not, add them to the relevant `.gitignore`).
- [ ] **Step 8: Commit** — `git add global.json csharp fsharp java/gradlew` (plus any `.gitignore`), message `linux: build .NET ports with SDK 10.0.1xx; make gradlew executable`.

If any port's own unit tests fail on Linux for a reason other than these build settings (e.g. a path or line-ending assumption), stop and report it rather than fixing it in this task.

### Task 2: `ports.json` Linux blocks and `conformance/run.sh`

**Files:**
- Modify: `conformance/ports.json` — add to each port a `"linux"` object with `path`, `build` (and `launcher` for clojure) from Task 1's table.
- Create: `conformance/run.sh` (mode 755, `#!/usr/bin/env bash`)
- Create (scratch, not committed): a cases file in the session scratchpad for Step 2.
- Modify: `README.md` ("Checking a port": add the Linux commands; note that `run.ps1` uses the Windows entries), `PROGRESS.md` (dated note).

**Interfaces:**
- Consumes: Task 1's build table.
- Produces: `bash conformance/run.sh [--port NAME|all] [--cases-path FILE]`. Effective port config = top-level object with the `linux` object's keys overriding (`jq '. + (.linux // {}) | del(.linux)'`).

Semantics to copy from `run.ps1` (read it in full first):
- Paths in `ports.json` and `--cases-path` default are relative to the repo root (the script's parent dir); `--cases-path` given by the user is relative to the caller's cwd.
- Skip a case (count as skipped) if it has `platforms` not containing `linux`, or if any `requires` key differs from the port's `features` value (missing feature = `false`).
- With `launcher`, run `launcher[0] launcher[1..] <path> <args…>`; the `==` header shows the full command prefix.
- Checks in order: exit code (`exit <got>, expected <want>`); then the one `stdout` kind present — `exact` (with `{os}` → `linux`, byte-exact), `json` (parse failure → `stdout is not JSON: <json-quoted stdout>`; inequality → `stdout JSON <trimmed stdout>, expected <compact json>`), `empty`, `nonempty`; then `stderr_contains` (substring, case-sensitive). First failing check is the reason. Strip `\r` before `\n` in captured output.

- [ ] **Step 1: Add the `linux` blocks to `ports.json`**; `jq . conformance/ports.json` must succeed.
- [ ] **Step 2: Write the runner's failing check first** — a scratch cases file with exactly these cases, each of which must FAIL against rust:
  - `{"name":"no-trailing-newline","args":["--version"],"exit":0,"stdout":{"exact":"whereami 0.4.0 ({os})"}}`
  - `{"name":"not-json","args":["--version"],"exit":0,"stdout":{"json":{}}}`
  - `{"name":"wrong-exit","args":["--version"],"exit":1}`
  - `{"name":"stderr-missing","args":["--version"],"exit":0,"stderr_contains":"nope"}`
- [ ] **Step 3: Implement `conformance/run.sh`.** Run each case with stdout/stderr redirected to temp files (`mktemp`, cleaned up by a `trap`); build argv from `jq -j '.args[]? | ., "\u0000"'` into an array with `readarray -d ''`. Compare `exact` with `cmp`/byte equality against the expected string written with `printf '%s'`; compare JSON with `jq -n --slurpfile a got --argjson b want '$a[0] == $b'`. Don't use `set -e`; use `set -u -o pipefail`.
- [ ] **Step 4: Run the scratch cases** — `bash conformance/run.sh --port rust --cases-path <scratch file>`. Expected: 4 FAIL lines with the reasons above (`stdout "whereami 0.4.0 (linux)\n", expected "whereami 0.4.0 (linux)"`, `stdout is not JSON: …`, `exit 0, expected 1`, `stderr "" lacks "nope"`), exit 1.
- [ ] **Step 5: Runner self-test** — `bash conformance/run.sh --port rust --cases-path conformance/runner-selftest.json`. Expected: `0 passed, 3 failed`, exit 1.
- [ ] **Step 6: Missing port** — temporarily move `rust/target/release/whereami` aside; `bash conformance/run.sh` prints `MISSING rust: build with cargo build --release --manifest-path rust/Cargo.toml` and still runs the other ports; move it back.
- [ ] **Step 7: Full run** — `bash conformance/run.sh`. Expected: every port `N passed, 0 failed, M skipped` (skips are only `completions-reserved`-style `requires` mismatches and non-linux `platforms`), exit 0. Paste the summary lines into the task report.
- [ ] **Step 8: Docs** — README "Checking a port" gains a Linux block (`bash conformance/run.sh --port rust`, self-test command); PROGRESS.md note: `- 2026-11-26 all ports: build and pass mock/usage conformance on Linux (Ubuntu 26.04) via conformance/run.sh.` Don't flip the Linux column (that's for the GeoClue backends).
- [ ] **Step 9: Commit** — `git add conformance/ports.json conformance/run.sh README.md PROGRESS.md docs/superpowers`, message `conformance: bash runner and Linux port entries`.
