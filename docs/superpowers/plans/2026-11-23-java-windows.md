# Java Port (Windows) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Java `whereami` that passes `spec/cases.json` on Windows and gets live location by calling WinRT **by hand** through the FFM API (`java.lang.foreign`). It also gets an Anatomy walkthrough that can be read next to the Rust and C# versions and the Zig original.

**Architecture:** A Gradle multi-project with two modules:
- `java/platform` is a library holding the model, the `Platform` interface and the OS backends. The Clojure port will reuse it.
- `java/app` is the CLI: picocli, Jackson, `App`.

The internal shape matches the other ports. `App.run(Request, Platform, Duration)` returns an `Outcome`, so all CLI behavior is tested with a fake platform. Expected failures are a **checked** `LocationException`.

The Windows backend mirrors `original/whereami/src/platform/windows.zig`:
- `RoInitialize`, `WindowsCreateString`, `RoGetActivationFactory` and `RoActivateInstance` are reached through `Linker.nativeLinker()` downcalls into `combase.dll`.
- Methods are called through vtable slots on raw COM pointers.
- Async operations are awaited by polling `IAsyncInfo.Status`.

**Tech Stack:**
- JDK 27 (installed), compiling with `--release 25` (the LTS baseline from the design).
- Gradle 9.8.2 or newer via the wrapper, Kotlin DSL, with the version catalog in `gradle/libs.versions.toml`.
- picocli 4.7.x, Jackson 3.x (`tools.jackson.core:jackson-databind`), JUnit Jupiter 6.x. Use the latest stable of each and record it in the catalog.

**Spec:** `docs/superpowers/specs/2026-11-23-whereami-ports-design.md`, plus the strict contract in `spec/behavior.md` and `spec/cases.json`. The Rust port (`rust/src/app.rs`) is the reference for stderr wording.

## Global Constraints

- Version output: `whereami 0.4.0 (<os>)`, where `<os>` is one of `windows`, `linux`, `macos`, for both `--version` and `-v`.
- Exit codes: usage errors **2** (picocli's default `ExitCode.USAGE`), runtime errors **1**, success 0. Usage errors go to stderr only, never JSON on stdout.
- Human output, exact:
  - `Location: %.4f, %.4f`
  - `Accuracy: %.0fm` (omitted for `--at` and for address lookups)
  - `Address: …` (non-empty fields joined by `, `)
  - Formatted with `Locale.ROOT`, `\n` line endings.
- JSON keys: `latitude`, `longitude`, `accuracy`, `address` (`null` or `{street, city, state, postal_code, country}`). Errors are `{"error":"<key>"}`. stderr messages match `rust/src/app.rs` word for word.
- Coordinates:
  - Split at the first comma and trim **spaces**.
  - Each part must match `[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?` and parse to a finite `double`.
  - `Double.parseDouble` alone is too lenient: it accepts `NaN`, `Infinity`, `0x1p3` and `1d`, and it overflows to `Infinity`.
  - `--mock` is not range-checked; `--at` must be within −90..90 and −180..180 inclusive.
- `completions` as the first positional argument is a usage error with the message `completions are not available in this port`.
- Address tokens starting with `-` are unknown options (picocli's default). `@file` expansion is **off** (`setExpandAtFiles(false)`).
- 10-second limit on the whole request (location plus reverse geocode). It must hold even if an OS call never returns.
- COM work runs on one **platform** (non-virtual) daemon thread. A virtual thread can change carrier threads between calls, and COM initialization belongs to one OS thread.
- JVM flags in the start script and for tests: `--enable-native-access=ALL-UNNAMED`, `-Dstdout.encoding=UTF-8`, `-Dstderr.encoding=UTF-8`. There must be no FFM warning on stderr.
- No `module-info.java`. The app runs on the classpath (ruling: JPMS adds `opens` for picocli and Jackson reflection and teaches nothing about OS calls).
- Don't modify `original/`, `rust/` or `csharp/`.
- Git is local only. Branches `java-m1-scaffold` (Tasks 1–3), `java-m2-windows` (Task 4) and `java-m3-docs` (Task 5) are each merged into `main` with `git merge --no-ff` once their checks pass. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

- **German locale** (`Locale.setDefault(Locale.GERMANY)`). Output must still print `40.7128`, and parsing must still accept it. Covered by `LocaleTest`.
- **`Double.parseDouble` leniency.** `nan`, `NaN`, `Infinity`, `0x1p3`, `1d`, `1f`, `1e999` and `1,2` with a tab must all be usage errors. Covered by `CoordinatesTest.rejectsNonDecimal`.
- **`@home`**: picocli expands `@file` arguments by default. It must be an address. Covered by the case `address-at-sign`.
- **Hung OS call, or COM on the wrong kind of thread.**
  - The 10 s limit must hold when a platform call ignores interrupts.
  - The location call must run start to finish on one non-virtual thread.
  - Covered by `AppTest.timeoutHoldsWhenPlatformIgnoresInterrupt` and `AppTest.platformRunsOnOnePlatformThread`.
- **Arguments passing through `whereami.bat` (cmd.exe).** Spaces, `=`, `,`, leading `-` and an all-spaces argument must arrive unchanged, and stderr must carry no JVM warnings. Covered by the cases `mock-spaces`, `mock-negative`, `empty-address` run through the `.bat`, plus Task 3 Step 6.

---

## File Structure

```
java/settings.gradle.kts                 rootProject "whereami"; include("platform", "app")
java/gradle/libs.versions.toml           picocli, jackson, junit versions
java/gradlew, gradlew.bat, gradle/wrapper/*   generated once, committed
java/platform/build.gradle.kts           java-library; release 25; JUnit
java/platform/src/main/java/whereami/platform/
    Location.java Address.java GeocodeResult.java LocationError.java LocationException.java
    Platform.java Platforms.java UnsupportedPlatform.java
    windows/Guid.java windows/Com.java windows/WinRt.java windows/HResults.java windows/WindowsPlatform.java
java/platform/src/test/java/whereami/platform/...   ModelTest, windows/{GuidTest,ComTest,HResultsTest,WinRtTest}
java/app/build.gradle.kts                application; mainClass whereami.cli.Main; applicationName whereami
java/app/src/main/java/whereami/cli/
    Main.java WhereamiCommand.java VersionProvider.java Coordinates.java
    Mode.java Request.java Output.java App.java Outcome.java
java/app/src/test/java/whereami/cli/     OutputTest, LocaleTest, CoordinatesTest, WhereamiCommandTest, AppTest
java/README.md                           Anatomy
```
Add `java/**/build/` and `java/.gradle/` to `.gitignore`.

Conformance build: `java/gradlew.bat -p java :app:installDist`. This produces `java/app/build/install/whereami/bin/whereami.bat`.

---

### Task 1: Gradle wrapper, model, and output (branch `java-m1-scaffold`)

**Files:**
- Create: the Gradle files above, the model records in `platform`, `Output.java`, `OutputTest`, `LocaleTest`, `ModelTest`
- Modify: `.gitignore`

**Interfaces:**
- Produces (package `whereami.platform`):
  - `public record Location(double latitude, double longitude, double accuracy)`
  - `public record Address(String street, String city, String state, String postalCode, String country)`
  - `public record GeocodeResult(Location location, Address address)`
  - `public enum LocationError { PERMISSION_DENIED, LOCATION_UNAVAILABLE, TIMEOUT, GEOCODING_FAILED, PLATFORM_UNSUPPORTED; public String jsonKey() }`. `GEOCODING_FAILED` maps to `unknown_error`.
  - `public final class LocationException extends Exception { LocationException(LocationError error); LocationError error(); }` is a checked exception.
- Produces (package `whereami.cli`): `final class Output { static String human(Location, Address /*nullable*/, boolean showAccuracy); static String json(Location, Address); static String jsonError(String key); }`. `json` and `jsonError` end with `\n`.

Jackson setup:
- `JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()`.
- Serialize a private record `Body(double latitude, double longitude, double accuracy, Address address)`; record component order gives the key order.
- `null` is written as `"address":null` by default.
- Non-ASCII is not escaped by default.

- [ ] **Step 1: Bootstrap the wrapper.**
  1. Read the current version from `https://services.gradle.org/versions/current`. It must be ≥ 9.8.2, the first release that runs on JDK 27.
  2. Download `gradle-<ver>-bin.zip` from `https://services.gradle.org/distributions/` into the scratchpad. **This needs the user's OK.** Their reply to the plan counts.
  3. Unzip it and run `<unzipped>/bin/gradle.bat wrapper --gradle-version <ver> --distribution-type bin` inside `java/`.
  4. Delete the unzipped copy afterwards.
- [ ] **Step 2: Write the build files.**
  - `settings.gradle.kts`.
  - `libs.versions.toml`.
  - `platform/build.gradle.kts`: `java-library`, `tasks.withType<JavaCompile> { options.release = 25 }`, `testImplementation(platform(libs.junit.bom))`, `testImplementation(libs.junit.jupiter)` + `testRuntimeOnly(libs.junit.launcher)`, and `tasks.test { useJUnitPlatform(); jvmArgs("--enable-native-access=ALL-UNNAMED") }`.
  - `app/build.gradle.kts`: the same, plus `application`, `implementation(project(":platform"))`, picocli and jackson-databind, and `applicationDefaultJvmArgs` holding the three flags from Global Constraints.
- [ ] **Step 3: Write failing tests.**

```java
// app: OutputTest
@Test void humanWithAccuracy() { assertEquals("Location: 40.7128, -74.0060\nAccuracy: 0m\n", Output.human(new Location(40.7128, -74.0060, 0), null, true)); }
@Test void humanWithoutAccuracySkipsEmptyFields() { assertEquals("Location: 38.8977, -77.0366\nAddress: 1600 Pennsylvania Ave NW, Washington, 20500, US\n",
    Output.human(new Location(38.8977, -77.0366, 5), new Address("1600 Pennsylvania Ave NW", "Washington", "", "20500", "US"), false)); }
@Test void jsonNullAddressAndKeyOrder()   // parse with JsonMapper: latitude 40.7128, address isNull(), field names in order [latitude, longitude, accuracy, address]
@Test void jsonEscapesAddress()           // street 5 "Q" St\ round-trips; key "postal_code"; raw text contains "Montréal"
@Test void jsonErrorShape() { assertEquals("{\"error\":\"timeout\"}\n", Output.jsonError("timeout")); }
// app: LocaleTest — @BeforeEach Locale.setDefault(Locale.GERMANY), @AfterEach restore
@Test void humanOutputIsLocaleIndependent() { assertTrue(Output.human(new Location(40.7128, -74.0060, 0), null, true).startsWith("Location: 40.7128, -74.0060")); }
// platform: ModelTest
@Test void jsonKeys()  // PERMISSION_DENIED→permission_denied, LOCATION_UNAVAILABLE→location_unavailable, TIMEOUT→timeout, GEOCODING_FAILED→unknown_error, PLATFORM_UNSUPPORTED→platform_unsupported
```

- [ ] **Step 4: Run** `java/gradlew.bat -p java test`. Expected: compile FAILURE (`Output` / `Location` not found).
- [ ] **Step 5: Implement** the model records, `LocationError`, `LocationException` and `Output`.
- [ ] **Step 6: Run** `java/gradlew.bat -p java test`. Expected: BUILD SUCCESSFUL, 7 tests.
- [ ] **Step 7: Commit** on the new branch `java-m1-scaffold`: `java: Gradle multi-project, model, and output formatting`.

---

### Task 2: Platform interface, stub, and App logic

**Files:**
- Create: `Platform.java`, `Platforms.java`, `UnsupportedPlatform.java` (platform module); `Mode.java`, `Request.java`, `Outcome.java`, `App.java`, `AppTest.java` (app module)

**Interfaces:**
- Consumes: Task 1's model and `Output`.
- Produces (`whereami.platform`):
  - `public interface Platform { Location getLocation(Duration timeout) throws LocationException; Optional<Address> reverseGeocode(double lat, double lon) throws LocationException; Optional<GeocodeResult> geocode(String query) throws LocationException; }`
  - `public final class Platforms { static String osName(); static Platform nativePlatform(); }`.
    - `osName()` reads `System.getProperty("os.name")`: values starting with `Windows` give `windows`, values starting with `Mac` give `macos`, anything else gives `linux`.
    - `nativePlatform()` returns `UnsupportedPlatform` for now; Task 4 adds Windows.
  - `UnsupportedPlatform`:
    - `getLocation` and `geocode` throw `PLATFORM_UNSUPPORTED`.
    - `reverseGeocode` returns `Optional.empty()`.
- Produces (`whereami.cli`):
  - `sealed interface Mode { record Current() implements Mode; record Mock(Location location) implements Mode; record At(Location location) implements Mode; record Lookup(String query) implements Mode; }`
  - `record Request(Mode mode, boolean json)`
  - `record Outcome(String stdout, String stderr, int exitCode)`
  - `final class App { static final Duration TIMEOUT = Duration.ofSeconds(10); static Outcome run(Request, Platform, Duration timeout); }`

`App.run`:
- Matches `rust/src/app.rs` case for case (same messages, same `--at` warning, `Lookup` → `address_not_found` or `platform_unsupported`), using a pattern-matching `switch` over `Mode`.
- All platform calls for one request (`getLocation` + `reverseGeocode`, or `geocode`) run as **one** task on a new daemon platform thread (`Thread.ofPlatform().daemon()`), completing a `CompletableFuture`. `App` waits with `future.get(timeout, MILLISECONDS)`.
  - On `TimeoutException` → timeout error, and interrupt the thread.
  - On `ExecutionException`, a `LocationException` cause becomes that error. Any other cause is rethrown, so it isn't hidden.
- `Mock` makes no platform calls, so it needs no thread.

- [ ] **Step 1: Write failing tests** in `AppTest`, using a `Fake implements Platform` built from lambdas or fields:

```java
@Test void mockSkipsPlatform()              // Mock(40.7128,-74.006) → exit 0, stdout "Location: 40.7128, -74.0060\nAccuracy: 0m\n"; fake getLocation throws if called
@Test void currentWithAddress()             // fake loc (1,2,3) + address → human has Accuracy and Address lines, exit 0
@Test void atWithoutAddressWarns()          // At(90,-180), no address → stdout "Location: 90.0000, -180.0000\n", stderr "Warning: no address found for these coordinates.\n", exit 0
@Test void permissionDeniedJson()           // json=true → stdout {"error":"permission_denied"}\n, stderr starts "Error: location permission denied.", exit 1
@Test void unsupportedLocation()            // stderr "Error: location is not supported on this platform yet.\n", exit 1
@Test void lookupUnsupported()              // stderr "Error: address lookup is not supported on this platform.\n", json key platform_unsupported
@Test void lookupNotFound()                 // Optional.empty() → key address_not_found, stderr contains "couldn't find a location for \"Paris\""
@Test void lookupFoundOmitsAccuracy()       // no "Accuracy:" line
@Test void timeoutHoldsWhenPlatformIgnoresInterrupt()  // getLocation loops forever, swallowing InterruptedException; run(..., Duration.ofMillis(200)) returns key timeout, "Error: location request timed out.\n", within 2 s (assertTimeoutPreemptively)
@Test void platformRunsOnOnePlatformThread()  // fake records Thread.currentThread() in getLocation and reverseGeocode: same thread, isVirtual() false, not the test thread
@Test void unexpectedExceptionIsNotSwallowed()  // getLocation throws IllegalStateException → assertThrows from App.run
```

- [ ] **Step 2: Run** `java/gradlew.bat -p java test`. Expected: compile FAILURE (`App` not found).
- [ ] **Step 3: Implement** the platform interface, `Platforms`, `UnsupportedPlatform`, `Mode`, `Request`, `Outcome` and `App`.
- [ ] **Step 4: Run** `java/gradlew.bat -p java test`. Expected: BUILD SUCCESSFUL, 18 tests.
- [ ] **Step 5: Commit** `java: platform interface, stub, and app logic`.

---

### Task 3: picocli command, entry point, conformance

**Files:**
- Create: `Coordinates.java`, `VersionProvider.java`, `WhereamiCommand.java`, `Main.java`, `CoordinatesTest`, `WhereamiCommandTest`
- Modify: `conformance/ports.json`, `conformance/run.ps1`, `PROGRESS.md`

**Interfaces:**
- Consumes: `App`, `Request`, `Mode`, `Platform`, `Platforms.osName()`.
- Produces:
  - `final class Coordinates { static Location parse(String text) /* throws IllegalArgumentException with a message */; static final class Converter implements CommandLine.ITypeConverter<Location> }`. The converter rethrows as `CommandLine.TypeConversionException`.
  - `final class VersionProvider implements CommandLine.IVersionProvider` returns `"whereami 0.4.0 (" + Platforms.osName() + ")"`.
  - `@Command(name = "whereami", versionProvider = VersionProvider.class, description = "Get your current location using native OS location services, or look up an address or a pair of coordinates.") final class WhereamiCommand implements Callable<Integer>`:
    - Constructor `(Platform platform, Duration timeout)`.
    - Options: `-h/--help` (`usageHelp`), `-v/--version` (`versionHelp`), `--json`.
    - An `@ArgGroup(exclusive = true)` holding `--mock` and `--at` (both `Location`, `paramLabel = "LAT,LON"`, `converter = Coordinates.Converter.class`).
    - `@Parameters(arity = "0..*", paramLabel = "ADDRESS") List<String> address`.
    - `Request toRequest()` throws `CommandLine.ParameterException` for:
      - `completions`
      - an empty address after joining and trimming
      - address together with `--mock` or `--at`
      - `--at` out of range
    - `call()` runs `App.run` and writes stdout and stderr through `spec.commandLine().getOut()` / `getErr()`, then returns the exit code.
    - `static CommandLine commandLine(Platform, Duration)` builds a `CommandLine` with `setExpandAtFiles(false)`.
  - `Main.main` calls `System.exit(WhereamiCommand.commandLine(Platforms.nativePlatform(), App.TIMEOUT).execute(args))`.

- [ ] **Step 1: Write failing tests.**

```java
// CoordinatesTest
@ParameterizedTest @CsvSource(delimiter='|', value={"40.7128,-74.0060|40.7128|-74.006", " 51.5 , -0.1276|51.5|-0.1276", "-33.8688,151.2093|-33.8688|151.2093", "1e1,+2|10|2"})
void parses(String text, double lat, double lon)
@ParameterizedTest @ValueSource(strings={"40.7128", "abc,def", "1,2,3", "nan,0", "NaN,0", "0,inf", "Infinity,0", "0x1p3,0", "1d,2", "1f,2", "1e999,0", "\t1,2", ",", ""})
void rejectsNonDecimal(String text)   // assertThrows IllegalArgumentException
// LocaleTest (extend): parsing "40.7128,-74.006" under Locale.GERMANY gives 40.7128
// WhereamiCommandTest — helper run(String... args) → (exit, out, err) using commandLine(fake, 1 s) with setOut/setErr on StringWriters
@Test void versionBothSpellings()   // "--version" and "-v" → exit 0, out.strip() equals "whereami 0.4.0 (" + Platforms.osName() + ")"
@Test void unknownFlag()            // "--bogus" → exit 2, out empty
@Test void atSignIsAddress()        // "@home" → fake geocode receives "@home"
@Test void multiwordAddressJoined() // "350","5th","Ave" → geocode receives "350 5th Ave"
@Test void completionsReserved()    // "completions","bash" → exit 2, err contains "completions are not available in this port", out empty
@Test void atOutOfRange()           // "--at=91,0" → exit 2
@Test void atAndMockConflict()      // "--at=1,2","--mock=1,2" → exit 2
@Test void addressAndMockConflict() // "--mock=1,2","Paris" → exit 2
@Test void emptyAddress()           // "   " → exit 2
@Test void jsonUsageErrorPrintsNothing() // "--json","--mock=x" → exit 2, out empty
```

- [ ] **Step 2: Run** `java/gradlew.bat -p java test`. Expected: compile FAILURE.
- [ ] **Step 3: Implement** `Coordinates`, `VersionProvider`, `WhereamiCommand` and `Main`.
- [ ] **Step 4: Run** `java/gradlew.bat -p java test`. Expected: BUILD SUCCESSFUL, all tests pass.
- [ ] **Step 5: Wire up conformance.**
  1. Add a `java` entry to `ports.json`: path `java/app/build/install/whereami/bin/whereami`, build `java/gradlew.bat -p java :app:installDist`, features `completions:false`.
  2. In `run.ps1`, after the `.exe` check, add the same check for `"$exe.bat"`.
  3. Run `java/gradlew.bat -p java :app:installDist`, then `pwsh conformance/run.ps1 -Port java`.
  - Expected: all 29 PASS.
  - Also run `pwsh conformance/run.ps1` (all ports). Expected: rust and csharp still all PASS.
  - Also run the runner self-test with `-Port java`. Expected: every case FAIL.
- [ ] **Step 6: Check the start script by hand.** Run `java/app/build/install/whereami/bin/whereami.bat --mock=1,2 2>err.txt`. Expected: `err.txt` is empty (no `WARNING:` lines).
- [ ] **Step 7: Update PROGRESS.** Set the java row to scaffold ✅ and conformance (mock) ✅.
- [ ] **Step 8: Commit and merge.** Commit `java: picocli command and entry point; passes conformance`, then `git switch main && git merge --no-ff java-m1-scaffold`.

---

### Task 4: Windows backend by hand with FFM (branch `java-m2-windows`)

**Files:**
- Create: `windows/Guid.java`, `Com.java`, `WinRt.java`, `HResults.java`, `WindowsPlatform.java`, plus tests `GuidTest`, `ComTest`, `HResultsTest`, `WinRtTest`
- Modify: `Platforms.java`, `PROGRESS.md`

**Interfaces:**
- Consumes: `Platform`, `Location`, `LocationError`, `LocationException`.
- Produces (`whereami.platform.windows`, all package-private except `WindowsPlatform`):
  - `final class Guid { static MemorySegment allocate(SegmentAllocator a, String text); }` takes text like `"A9C3BF62-4524-4989-8AA9-DE019D2E551F"`. It lays out 16 bytes: data1 as a little-endian int, data2 and data3 as little-endian shorts, then 8 bytes as written.
  - `final class WinRtException extends RuntimeException { int hresult(); }` (inside `Com.java`).
  - `final class Com`:
    - `static int call(MemorySegment obj, int slot, FunctionDescriptor fd, Object... argsAfterThis)` reads the vtable at `obj[0]` and the function pointer at `vtable[slot]`, builds a downcall from `fd` (whose first argument is the `this` `ADDRESS` and whose return is `JAVA_INT`), and invokes it with `obj` first.
    - `static void check(int hr)` throws `WinRtException` if `hr < 0`.
    - `static MemorySegment queryInterface(MemorySegment obj, MemorySegment iid, Arena a)` calls slot 0.
    - `static void release(MemorySegment obj)` calls slot 2, skipping `NULL`.
  - `final class WinRt` holds lazily created downcalls into `combase.dll`: `roInitialize(int)`, `roUninitialize()`, `roGetActivationFactory(MemorySegment hstring, MemorySegment iid, MemorySegment out)`, `roActivateInstance(MemorySegment hstring, MemorySegment out)`, `windowsCreateString(String, Arena) → MemorySegment` (UTF-16LE, length in chars) and `windowsDeleteString(MemorySegment)`. They are loaded through a holder class so nothing touches `combase` until it is first used.
  - `final class HResults { static LocationError toError(int hr); }`: `0x80070005` gives `PERMISSION_DENIED`, `0x800705B4` gives `TIMEOUT`, anything else gives `LOCATION_UNAVAILABLE`.
  - `public final class WindowsPlatform implements Platform`.

Values copied from `windows.zig`:
- IIDs: `IGeolocatorStatics 9A8E7571-2DF5-4591-9F87-EB5FD894E9B7`, `IGeolocator A9C3BF62-4524-4989-8AA9-DE019D2E551F`, `IAsyncInfo 00000036-0000-0000-C000-000000000046`, `IAsyncOperation<GeolocationAccessStatus> DE2B24D0-B726-57B1-A7C5-E5A13932B7DE`, `IAsyncOperation<Geoposition> EE73ECF0-099D-57E5-8407-5B32E5AF1CC4`.
- Class name: `Windows.Devices.Geolocation.Geolocator`.
- Vtable slots (IUnknown 0–2, IInspectable 3–5):
  - IGeolocatorStatics: `RequestAccessAsync` 6
  - IGeolocator: `put_DesiredAccuracy` 7 (High = 0), `GetGeopositionAsyncWithAgeAndTimeout` 14, taking two `JAVA_LONG` TimeSpans in 100 ns ticks, the same call Rust and C# use
  - IAsyncInfo: `get_Status` 7, `get_ErrorCode` 8, `Cancel` 9
  - IAsyncOperation: `GetResults` 8
  - IGeoposition: `get_Coordinate` 6
  - IGeocoordinate: `get_Latitude` 6, `get_Longitude` 7, `get_Accuracy` 9
- `RoInitialize(1)` (multithreaded): treat both `S_OK` and `S_FALSE` as success.
- Access status values: 1 = Allowed. Anything else → `PERMISSION_DENIED`.

`WindowsPlatform.getLocation(timeout)`:
- Follow the order in `windows.zig`'s `getLocation`, with all allocations in one `Arena.ofConfined()` (try-with-resources), and every COM pointer released and the HSTRING deleted in `finally`.
- `GetResults` on `IAsyncOperation<Geoposition>` returns `IGeoposition` directly, and `get_Coordinate` returns `IGeocoordinate`. These are the classes' default interfaces, so no `QueryInterface` is needed.
- The await helper `awaitAsync(op, deadline)`:
  - Queries `IAsyncInfo` and polls `get_Status` every 50 ms.
  - Completed → return.
  - Error → `get_ErrorCode` → `WinRtException`.
  - Canceled → `TIMEOUT`.
  - Past the deadline → `Cancel`, then `TIMEOUT`.
- A `WinRtException` anywhere becomes `new LocationException(HResults.toError(e.hresult()))`.
- `reverseGeocode` returns `Optional.empty()` and `geocode` throws `PLATFORM_UNSUPPORTED`. Add a comment that `MapLocationFinder` needs a Bing Maps key.

- [ ] **Step 1: Write failing tests.**

```java
// GuidTest
@Test void layout()  // allocate(arena, "00000036-0000-0000-C000-000000000046").toArray(JAVA_BYTE) == {0x36,0,0,0, 0,0, 0,0, (byte)0xC0,0,0,0,0,0,0,0x46}
@Test void layoutMixed() // "A9C3BF62-4524-4989-8AA9-DE019D2E551F" → 62 BF C3 A9 24 45 89 49 8A A9 DE 01 9D 2E 55 1F
// ComTest — a fake COM object made with Linker.upcallStub: a 7-slot vtable whose slot 2 counts Release calls and slot 6 is
//   int answer(MemorySegment self, MemorySegment outInt) { outInt.reinterpret(4).set(JAVA_INT, 0, 42); return 0; }
@Test void callsThroughVtableSlot()   // Com.call(fake, 6, fd, out) == 0 and out holds 42
@Test void releaseCallsSlotTwo()      // Com.release(fake) → counter 1; Com.release(MemorySegment.NULL) → no call
@Test void checkThrowsOnFailure()     // Com.check(0x80070005) throws WinRtException with hresult 0x80070005; Com.check(1) (S_FALSE) does not
// HResultsTest
@Test void mapping()   // 0x80070005→PERMISSION_DENIED, 0x800705B4→TIMEOUT, 0x80004005→LOCATION_UNAVAILABLE
// WinRtTest — @EnabledOnOs(OS.WINDOWS); needs no location permission
@Test void getsGeolocatorActivationFactory()  // roInitialize(1) ≥ 0; create HSTRING for the class name; roGetActivationFactory with IGeolocatorStatics IID → hr 0, non-NULL; release; delete string; roUninitialize
```

- [ ] **Step 2: Run** `java/gradlew.bat -p java test`. Expected: compile FAILURE (`Guid`, `Com`… not found).
- [ ] **Step 3: Implement** `Guid`, `Com`, `HResults` and `WinRt`.
- [ ] **Step 4: Run tests.** Expected: the new tests pass.
- [ ] **Step 5: Implement** `WindowsPlatform` and make `Platforms.nativePlatform()` return it when `osName()` is `windows`.
- [ ] **Step 6: Run** `java/gradlew.bat -p java test`, then `:app:installDist`, then `pwsh conformance/run.ps1 -Port java`. Expected: BUILD SUCCESSFUL, all 29 PASS.
- [ ] **Step 7: Live check.**
  - Run `java/app/build/install/whereami/bin/whereami.bat` and the same with `--json`. Expected: exit 0, plausible coordinates, no stderr. Report the coordinates masked.
  - Record this in PROGRESS: the Windows live cell becomes 🟨, with a note asking the user to check with *Let desktop apps access your location* off. Expected then: `{"error":"permission_denied"}` plus the denied message, exit 1.
- [ ] **Step 8: Commit and merge.** Commit `java: Windows location by hand via FFM and WinRT`, then merge `java-m2-windows` with `--no-ff`.

---

### Task 5: Anatomy and comparison (branch `java-m3-docs`)

**Files:**
- Create: `java/README.md`
- Modify: `docs/comparison.md` (Java column), `PROGRESS.md` (Anatomy ✅)

The README follows the shape of `csharp/README.md`:
- A support table.
- Build, run and test commands.
- **Anatomy**, read in order:
  1. `settings.gradle.kts`, the wrapper and the version catalog
  2. the two `build.gradle.kts` files (`java-library` vs `application`, `--release 25`, JVM flags)
  3. `Main` and `WhereamiCommand` (picocli annotations, `Callable<Integer>`, `execute` = exit code)
  4. the records and the sealed `Mode`, with pattern `switch`
  5. checked `LocationException` vs Rust `Result` vs C# exceptions
  6. `Output` with Jackson
  7. `App`: why it uses a platform daemon thread and not a virtual thread
  8. `Platforms`: a run-time `os.name` check only; all code is compiled everywhere
  9. FFM by hand: `Linker`, `SymbolLookup`, `Arena`, `MemorySegment`, downcalls, vtable slots, upcalls in `ComTest`. This section mirrors `windows.zig` side by side, with a Zig / Rust / C# / Java table in the same form as the C# README's.
- What Linux (`dbus-java`) and macOS (FFM plus the Objective-C runtime, `.app` bundle) will need.

- [ ] **Step 1: Write** the README and the comparison column. Every file link must resolve; check with `ls` on each linked path.
- [ ] **Step 2: Commit and merge.** Commit `java: Anatomy walkthrough and comparison column`, then merge `java-m3-docs` with `--no-ff`.
