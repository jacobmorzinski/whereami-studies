# Java Linux GeoClue Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Java port (and, through it, Clojure) returns the GeoClue2 position on Linux, matching the Rust port's behavior.

**Architecture:** A new `whereami.platform.linux` package implements `Platform` with dbus-java 5.2.2 (native-unixsocket transport). Two pure helpers — D-Bus error name → `LocationError`, and best-fix selection over a `BlockingQueue` — carry the tested logic; the GeoClue conversation wires them to the bus.

**Tech Stack:** JDK 25 (built by the Gradle wrapper), dbus-java 5.2.2, slf4j-nop 2.0.x, JUnit 6; Clojure CLI for the dependency fix.

**Spec:** `docs/superpowers/specs/2026-11-26-java-linux-geoclue-design.md` (reference implementation: `rust/src/platform/linux.rs`; shared rules: `spec/behavior.md`).

**Branch:** `java-m4-linux` (exists; spec and plan uncommitted — Task 1 commits them).

## Global Constraints

- Windows behavior unchanged; `windows/` package untouched.
- `DesktopId` = `"whereami"`; `RequestedAccuracyLevel` = `8` as D-Bus `uint32` (`org.freedesktop.dbus.types.UInt32`).
- D-Bus: service `org.freedesktop.GeoClue2`; manager `/org/freedesktop/GeoClue2/Manager`; interfaces `org.freedesktop.GeoClue2.Manager`, `.Client`, `.Location`.
- Best-fix window 250 ms after the first fix, clamped to deadline − 50 ms; smallest accuracy wins, tie → earlier; `Stop` sent on every path.
- Errors: name contains `AccessDenied` → `PERMISSION_DENIED`; everything else → `LOCATION_UNAVAILABLE`; no fix by deadline → `TIMEOUT`. No new `LocationError` values.
- stderr is exactly what the spec prescribes (nothing from SLF4J or dbus-java).
- Commit trailers name the implementing model, e.g. `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **SLF4J/dbus-java noise on stderr** (warnings, log lines, stack traces) on success or error paths. Pinned by Task 1 Step 7 (stderr must be empty on a live success and on `--mock`).
2. **Signal race:** handler registered before `Start`. Reviewer checks ordering.
3. **Signals from other clients:** handler bound to our client's remote object/path only. Reviewer checks.
4. **Process exit:** dbus-java starts non-daemon threads; the JVM must still exit promptly after output on success, timeout and error. Pinned by Task 1 Step 7 wall times (< 2 s on success) and Step 8 (`permission_denied` returns fast).
5. **Clojure on Linux** must not hit `NoClassDefFoundError` once `Platforms` returns `LinuxPlatform`. Pinned by Task 2.

---

### Task 1: Java `linux` backend

**Files:**
- Modify: `java/gradle/libs.versions.toml` (dbus-java 5.2.2: `dbus-java-core`, `dbus-java-transport-native-unixsocket`; `slf4j-nop` matching dbus-java's slf4j 2.0.17)
- Modify: `java/platform/build.gradle.kts` (dbus-java deps), `java/app/build.gradle.kts` (`runtimeOnly` slf4j-nop)
- Create: `java/platform/src/main/java/whereami/platform/linux/` — `LinuxPlatform` plus GeoClue interface declarations and helpers (names left to the builder; keep files focused)
- Modify: `java/platform/src/main/java/whereami/platform/Platforms.java` (`"linux"` → `new LinuxPlatform()`; update the class comment)
- Test: `java/platform/src/test/java/whereami/platform/linux/` — tests for the two pure helpers
- Commit also: the spec and plan files

**Interfaces:**
- Produces: `whereami.platform.linux.LinuxPlatform implements Platform` (public no-arg constructor); `reverseGeocode` → `Optional.empty()`; `geocode` throws `LocationException(PLATFORM_UNSUPPORTED)`.
- Pure helpers (package-private is fine): `static LocationError fromDbusErrorName(String name)` and a best-fix function taking `BlockingQueue<Location>`, a grace `Duration`, and an absolute deadline (`System.nanoTime()` based), returning `Optional<Location>` (empty = no fix by deadline).

- [ ] **Step 1: Write the failing tests.**
  - Error names: `"org.freedesktop.DBus.Error.AccessDenied"` → `PERMISSION_DENIED`; `"org.freedesktop.DBus.Error.ServiceUnknown"` → `LOCATION_UNAVAILABLE`; `"org.freedesktop.DBus.Error.Failed"` → `LOCATION_UNAVAILABLE`; `""` and `null` → `LOCATION_UNAVAILABLE`.
  - Best fix (small grace values, e.g. 50 ms): coarse (acc 25000) then fine (acc 10) within the window → fine; a single fix → that fix, elapsed ≥ grace and < 1 s; two equal accuracies → the first; a fix sent after the window (20 ms window, 200 ms sleep) → not chosen; grace 250 ms with deadline 30 ms away → returned in < 150 ms; no fix before a 50 ms deadline → empty, elapsed ≥ 40 ms and < 500 ms.
- [ ] **Step 2: Run** `java/gradlew -p java :platform:test` → compile failure / failing tests.
- [ ] **Step 3: Implement** the helpers, the GeoClue declarations (dbus-java `@DBusInterfaceName` interfaces; `LocationUpdated` as a `DBusSignal` with two object paths; property access via `org.freedesktop.dbus.interfaces.Properties`), and `LinuxPlatform.getLocation(timeout)` doing the conversation in the spec's Decisions. Map dbus-java exceptions (`DBusException`, `DBusExecutionException` and subclasses) through `fromDbusErrorName` using the exception's D-Bus error name; anything without a name → `LOCATION_UNAVAILABLE`. Close the connection on every path. Class-level Javadoc pointing at `original/whereami/src/platform/linux.zig` and `rust/src/platform/linux.rs`.
- [ ] **Step 4: Run tests** — `java/gradlew -p java test` → BUILD SUCCESSFUL (paste counts).
- [ ] **Step 5: Install** — `java/gradlew -p java :app:installDist`.
- [ ] **Step 6: Conformance** — `bash conformance/run.sh --port java` → `29 passed, 0 failed, 0 skipped`.
- [ ] **Step 7: Live smoke + clean stderr** — `time java/app/build/install/whereami/bin/whereami 2>err.txt` → `Location: 42.3958, -71.1473` / `Accuracy: 10m`, exit 0, `err.txt` empty, wall < 2 s; same with `--json` (`{"latitude":42.3958,"longitude":-71.1473,"accuracy":10…,"address":null}`); `whereami --mock=1,2 2>err.txt` → `err.txt` empty. (Put `err.txt` in the session scratchpad.)
- [ ] **Step 8: Commit** — message `java: Linux backend over GeoClue2 (dbus-java)`.

### Task 2: Clojure gets the dbus-java runtime

**Files:**
- Modify: `clojure/deps.edn` — add `com.github.hypfvieh/dbus-java-core` and `com.github.hypfvieh/dbus-java-transport-native-unixsocket` `{:mvn/version "5.2.2"}` and `org.slf4j/slf4j-nop` (same version as Task 1), with a comment: the platform jar is a bare `:local/root` jar, so its Maven dependencies must be listed here.

**Interfaces:**
- Consumes: Task 1's `platform.jar` (rebuild with `java/gradlew -p java :platform:jar`).

- [ ] **Step 1: Show the failure first** — with Task 1's jar but before editing `deps.edn`: rebuild the uberjar (`cd clojure && clojure -T:build uber`) and run `java -jar clojure/target/whereami.jar` → expect a `NoClassDefFoundError`-style failure (or `unknown_error`); paste it.
- [ ] **Step 2: Edit `deps.edn`**, rebuild the uberjar.
- [ ] **Step 3: Tests** — in `clojure/`: `clojure -X:test` → 0 failures.
- [ ] **Step 4: Conformance** — `bash conformance/run.sh --port clojure` → `29 passed, 0 failed, 0 skipped`.
- [ ] **Step 5: Live smoke** — `time java -jar clojure/target/whereami.jar 2>err.txt` → the static position, exit 0, `err.txt` empty.
- [ ] **Step 6: Commit** — message `clojure: add dbus-java runtime for the Java platform jar`.

### Task 3: Docs

(Runs after the controller's live checks; the dispatch carries their results.)

**Files:**
- Modify: `java/README.md` — platform table Linux row; Linux build/run commands beside the Windows ones; new Anatomy section for `linux/` (dbus-java interfaces and signal class, Properties, the queue + best-fix window, error mapping, slf4j-nop and why, the desktop file from the repo root); replace the Linux bullet in "What Linux and macOS will need"; update the `Platforms` section's comment about backends.
- Modify: `docs/comparison.md` — Java column of the "Linux binding" row.
- Modify: `clojure/README.md` — one note on why `deps.edn` lists dbus-java.
- Modify: `PROGRESS.md` — java Linux ✅ and a dated note with the live-check results.

- [ ] **Step 1: Write the docs**; every code reference matches the code as built.
- [ ] **Step 2: Link check** for every added relative link.
- [ ] **Step 3: Commit** — message `java: document the Linux backend; progress`.
