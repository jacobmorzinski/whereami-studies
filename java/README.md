# whereami — Java

A Java port of [whereami](https://github.com/georgemandis/whereami). It asks
the operating system's own location service where you are. Behavior is defined
by [`spec/behavior.md`](../spec/behavior.md). The [Rust](../rust/README.md)
and [C#](../csharp/README.md) ports have the same structure.

This is the port that calls Windows **by hand**. Java has no WinRT bindings,
so it does with the FFM API (`java.lang.foreign`) what the Zig original does
in [`windows.zig`](../original/whereami/src/platform/windows.zig): find
functions in `combase.dll`, call COM methods through vtable slots, release
every object, and poll async operations.

| | |
|---|---|
| Windows | ✅ `Windows.Devices.Geolocation` by hand via FFM |
| Linux | ✅ GeoClue2 over D-Bus via [`dbus-java`](https://github.com/hypfvieh/dbus-java) (pure Java, no FFM) |
| macOS | stub (planned: CoreLocation via FFM + the Objective-C runtime) |

## Build, run, test

```powershell
java/gradlew.bat -p java :app:installDist
java/app/build/install/whereami/bin/whereami.bat --json
java/gradlew.bat -p java test
pwsh conformance/run.ps1 -Port java
```

On Linux:

```sh
java/gradlew -p java :app:installDist
java/app/build/install/whereami/bin/whereami --json
java/gradlew -p java test
bash conformance/run.sh --port java
```

`-p java` points Gradle at the `java/` folder from the repo root. While
developing, `java/gradlew.bat -p java :app:run --args="--json"` builds and
runs in one step.

On Windows, live location needs *Settings → Privacy & security → Location*
with *Location services* and *Let desktop apps access your location* on.

On Linux, the location comes from GeoClue2. On GNOME, turn *Location
Services* on in *Settings → Privacy*. Installing the desktop file is
recommended (the original does the same), though an Ubuntu 26.04 GNOME session
located without it. From the repo root:

```sh
cp assets/whereami.desktop ~/.local/share/applications/
```

## Anatomy

Read the files in this order.

### 1. [`settings.gradle.kts`](settings.gradle.kts), the wrapper, the version catalog

- **Gradle** is the build tool. The build is written in Kotlin (`.kts` files).
- **`settings.gradle.kts`** names the build and lists its projects. There are
  two, `platform` and `app`, so this is a *multi-project build*.
- **The wrapper** ([`gradlew.bat`](gradlew.bat), `gradlew`,
  [`gradle/wrapper/`](gradle/wrapper/gradle-wrapper.properties)) is committed
  to the repo. Gradle itself doesn't need to be installed: the first run
  downloads the exact version named in `gradle-wrapper.properties` (9.9.2).
  That's how `global.json` pins the .NET SDK, except that here the tool
  fetches itself.
- **[`gradle/libs.versions.toml`](gradle/libs.versions.toml)** is the
  *version catalog*: every library version in one place. Build files refer
  to them as `libs.picocli`, `libs.jackson.databind`, and so on.
- **[`gradle.properties`](gradle.properties)** turns on the *configuration
  cache*: Gradle saves the result of configuring the build and reuses it on
  the next run, which makes repeat builds start faster.

### 2. The two `build.gradle.kts` files: library vs application

- **[`platform/build.gradle.kts`](platform/build.gradle.kts)** applies the
  `java-library` plugin. The project holds the model, the `Platform`
  interface and the OS backends, with no CLI code, so the Clojure port can
  depend on it later. It has two kinds of dependency: `implementation` for
  `dbus-java-core` (needed to compile and run, but not part of the library's
  public API) and `runtimeOnly` for the `dbus-java` transport (the code never
  names it; dbus-java finds it when it connects).
- **[`app/build.gradle.kts`](app/build.gradle.kts)** applies `application`.
  It depends on `project(":platform")`, picocli and Jackson. `installDist`
  produces `app/build/install/whereami/`: a `lib/` folder of jars plus the
  start scripts `bin/whereami` and `bin/whereami.bat`. A JVM program has no
  single `.exe`; the start script runs `java` with the right classpath.
- **`options.release = 25`**: the JDK installed here is 27, but the code is
  compiled for Java 25, the current long-term-support release. That allows
  only Java 25 APIs and produces bytecode a Java 25 JVM can run.
- **JVM flags** in `applicationDefaultJvmArgs` go into the start script:
  - `--enable-native-access=ALL-UNNAMED`: FFM calls into native code are
    "restricted". Without this flag the JVM prints a warning on first use.
  - `-Dstdout.encoding=UTF-8`: otherwise Java writes in the console's code
    page (437 here). That's the Java version of C#'s `Console.OutputEncoding`.
- **`runtimeOnly(libs.slf4j.nop)`**: dbus-java logs through SLF4J. With no
  SLF4J provider on the classpath SLF4J prints a warning on stderr, which
  would break the rule that stderr holds only the error text. `slf4j-nop`
  is a provider that discards every log message.
- **No `module-info.java`.** Java modules would need `opens` declarations so
  picocli and Jackson can use reflection, and they teach nothing about OS
  calls. The app runs on the plain classpath.

### 3. [`Main`](app/src/main/java/whereami/cli/Main.java) and [`WhereamiCommand`](app/src/main/java/whereami/cli/WhereamiCommand.java): the entry point and the CLI

`Main` is three lines:

```java
int exitCode = WhereamiCommand.commandLine(Platforms.nativePlatform(), App.TIMEOUT).execute(args);
System.exit(exitCode);
```

**picocli** declares the CLI with annotations on fields, much like clap's
derive in Rust:
- `@Option(names = "--json") boolean json;`
- `@Parameters(arity = "0..*") List<String> address;`
- `@ArgGroup(exclusive = true)` around `--mock` and `--at`, so picocli itself
  rejects using both.
- `converter = Coordinates.Converter.class` turns `"40.7,-74.0"` into a
  `Location` while parsing.
- `usageHelp`/`versionHelp` give `-h` and `-v`.

The command is a `Callable<Integer>`. `execute(args)` parses the arguments,
fills the fields, calls `call()`, and returns its result as the exit code.
Parse errors never reach `call()`: picocli prints them with the usage text
and returns **2**, which is already what the spec wants. Rules annotations
can't express (an address with `--mock`, `--at` out of range, the reserved
word `completions`) are checked in `toRequest()`. They throw
`ParameterException`, and picocli treats that as a usage error too.

Three things needed attention:
1. **`@file` arguments.** By default picocli reads `@something` as "more
   arguments from the file *something*". `setExpandAtFiles(false)` turns that
   off, so `whereami @home` is an address. (C# needed the same fix.)
2. **Number parsing.** `Double.parseDouble` accepts `NaN`, `Infinity`,
   `0x1p3` and `1d`, and ignores surrounding control characters. So
   [`Coordinates`](app/src/main/java/whereami/cli/Coordinates.java) checks the
   text against a decimal pattern first. `parseDouble` itself never depends
   on the locale, unlike C#'s `double.Parse`.
3. **Output streams.** `call()` prints through `spec.commandLine().getOut()`
   and `getErr()`, not `System.out`, so tests can capture them
   ([`WhereamiCommandTest`](app/src/test/java/whereami/cli/WhereamiCommandTest.java)).

### 4. The data: [records](platform/src/main/java/whereami/platform/Location.java) and a [sealed `Mode`](app/src/main/java/whereami/cli/Mode.java)

- **`record Location(double latitude, double longitude, double accuracy)`**
  gets a constructor, accessors (`loc.latitude()`), `equals`, `hashCode` and
  `toString` for free. That's Java's counterpart of C#'s `record` and Rust's
  `#[derive(...)]`.
- **`sealed interface Mode`** with nested `record` cases (`Current`, `Mock`,
  `At`, `Lookup`) is Java's version of a Rust `enum` with data. `sealed`
  means the compiler knows every case. So the pattern-matching `switch` in
  `App` (`case Mode.Mock(Location loc) -> …`) needs no `default`, and adding
  a case breaks the build until it is handled.

### 5. Errors: a checked [`LocationException`](platform/src/main/java/whereami/platform/LocationException.java)

The three ports show three styles:

| | Rust | C# | Java |
|---|---|---|---|
| Failure is… | a return value, `Result<Location, LocationError>` | an exception, invisible in the signature | an exception, **in the signature**: `throws LocationException` |
| The compiler… | forces you to handle the `Result` | doesn't check | forces callers to catch it or declare it |

Checked exceptions sit between the other two. The signature says the call
can fail, as Rust's does, but the failure travels as an exception. Real bugs
(`IllegalStateException`, …) are unchecked and propagate on their own.

### 6. [`Output`](app/src/main/java/whereami/cli/Output.java): formatting

- `String.format(Locale.ROOT, "Location: %.4f, %.4f\n", …)`. **`Locale.ROOT`**
  keeps the `.` decimal point. `String.format` without it uses the machine's
  locale and would print `40,7128` on a German machine.
  [`LocaleTest`](app/src/test/java/whereami/cli/LocaleTest.java) checks this.
- JSON with **Jackson 3** (package `tools.jackson`):
  - Serializes a private record, `Body(latitude, longitude, accuracy, address)`.
  - Records keep their component order, so the keys come out in that order.
  - `PropertyNamingStrategies.SNAKE_CASE` turns `postalCode` into `"postal_code"`.
  - Unlike `System.Text.Json`, Jackson doesn't escape `é` by default.

### 7. [`App`](app/src/main/java/whereami/cli/App.java): the logic, and which thread runs it

`App.run(Request, Platform, Duration)` returns an `Outcome(stdout, stderr,
exitCode)` instead of printing, exactly like Rust's `app::run` and C#'s
`App.RunAsync`. [`AppTest`](app/src/test/java/whereami/cli/AppTest.java)
drives it with a `Fake` platform.

The 10-second limit covers the **whole request**. All platform calls run on
one new thread, and `App` waits on a `CompletableFuture` with a timeout:

```java
Thread.ofPlatform().daemon().start(() -> … result.complete(calls.call()) …);
return result.get(timeout.toMillis(), MILLISECONDS);
```

- **A daemon thread**, so an OS call that never returns can't keep the JVM
  alive after we give up. `timeoutHoldsWhenPlatformIgnoresInterrupt` tests that.
- **A platform thread, not a virtual one.** Since Java 21, virtual threads are
  the cheap default for blocking work. They're the wrong choice here.
  `RoInitialize` sets up COM for *the current OS thread*, and a virtual
  thread can move to a different OS thread whenever it blocks.
  `platformRunsOnOnePlatformThread` checks this.

### 8. [`Platforms`](platform/src/main/java/whereami/platform/Platforms.java): choosing the OS

```java
return switch (osName()) {          // from System.getProperty("os.name")
    case "windows" -> new WindowsPlatform();
    case "linux" -> new LinuxPlatform();
    default -> new UnsupportedPlatform();
};
```

Java has no conditional compilation. Rust's `#[cfg]` and C#'s `#if WINDOWS`
leave other OSes' code out of the build; in Java every backend is compiled
on every OS, and the choice is made only at run time. That works because
nothing native is loaded until it's used: `WinRt` looks up `combase.dll` in a
holder class the first time a function is called, so on Linux it is never
touched. The same goes for `LinuxPlatform`: dbus-java is only used inside
`getLocation`, so Windows never starts it.

### 9. [`windows/`](platform/src/main/java/whereami/platform/windows/WindowsPlatform.java): calling the OS by hand

Five small classes, each one a piece of what the Rust `windows` crate or the
C# projection generates for you:

- **[`WinRt`](platform/src/main/java/whereami/platform/windows/WinRt.java)**: the plain C functions.
  - `SymbolLookup.libraryLookup("combase.dll", …)` finds the library.
  - `Linker.nativeLinker().downcallHandle(symbol, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))`
    turns a symbol plus its C signature into a `MethodHandle` that Java can invoke.
- **[`Guid`](platform/src/main/java/whereami/platform/windows/Guid.java)**: writes the 16-byte GUID
  struct into native memory. The first three parts are little-endian, the
  last eight bytes are as written.
- **[`Com`](platform/src/main/java/whereami/platform/windows/Com.java)**: calls COM methods.
  - An object pointer points to a vtable pointer. `Com.call(obj, slot, fd, args…)`
    reads `vtable[slot]` and makes a downcall to it with `obj` as the first argument.
  - Slots 0–2 are IUnknown (`QueryInterface`, `AddRef`, `Release`), 3–5 are
    IInspectable, and each interface's own methods start at 6.
  - [`ComTest`](platform/src/test/java/whereami/platform/windows/ComTest.java)
    tests this on any OS with a fake object. Its vtable holds *upcall stubs*:
    native function pointers that call back into Java methods.
    [`AwaitAsyncTest`](platform/src/test/java/whereami/platform/windows/AwaitAsyncTest.java)
    uses the same trick to fake an async operation and test the
    error, canceled, deadline and interrupt paths, including that `Cancel`
    and `Release` are called.
- **[`HResults`](platform/src/main/java/whereami/platform/windows/HResults.java)**:
  maps failure codes the same way as Rust's `map_hresult`.
- **[`WindowsPlatform`](platform/src/main/java/whereami/platform/windows/WindowsPlatform.java)**:
  the sequence from `windows.zig`.
  1. `RoInitialize`.
  2. `RequestAccessAsync` on the activation factory (static methods live
     there), then wait for it.
  3. `RoActivateInstance` creates the Geolocator, then `put_DesiredAccuracy`.
  4. `GetGeopositionAsyncWithAgeAndTimeout`, then wait again.
  5. `get_Coordinate`, then `get_Latitude` / `get_Longitude` / `get_Accuracy`.

Memory and lifetimes are explicit, as in Zig:
- **`Arena.ofConfined()`** in try-with-resources owns every buffer: out
  parameters, GUIDs, the UTF-16 class name. Closing the arena frees them all
  at once.
- **COM objects** aren't memory Java owns. Each pointer goes on a list, and
  `finally` calls `Release` on every one in reverse order. That's what
  Zig's `defer release(…)` does.
- **Async**: `awaitAsync` polls `IAsyncInfo.Status` every 50 ms, as
  `windows.zig` does, until it reports completed, error or canceled, or until
  the deadline passes. On the deadline it calls `Cancel`.

| | Zig ([`windows.zig`](../original/whereami/src/platform/windows.zig)) | Rust (`windows` crate) | C# (projection) | Java (FFM, [`windows/`](platform/src/main/java/whereami/platform/windows/WindowsPlatform.java)) |
|---|---|---|---|---|
| Get the API | `extern` decls + vtable structs | crate, feature `Devices_Geolocation` | target framework `-windows10.0.19041.0` | `SymbolLookup` + `downcallHandle`; slot numbers as constants |
| Create | `RoActivateInstance` + `QueryInterface` | `Geolocator::new()?` | `new Geolocator { … }` | `RoActivateInstance` + `Com.queryInterface` |
| Memory | allocator, `defer` | ownership, `Drop` | garbage collector | `Arena` (try-with-resources) |
| Lifetime of COM objects | `Release` by hand (`defer`) | `Drop` | garbage collector | `Release` by hand (`finally`) |
| Async | poll `GetStatus` in a loop | `.join()` (blocking) | `await …AsTask(ct)` | poll `get_Status` in a loop |
| Failure | check each `HRESULT` | `?` → `windows::core::Error` | exceptions | `Com.check(hr)` → `WinRtException` → `LocationException` |

### 10. [`linux/`](platform/src/main/java/whereami/platform/linux/LinuxPlatform.java): D-Bus through a library

Windows needed hand-written FFM; Linux doesn't. GeoClue2 is a service on the
system D-Bus, and [`dbus-java`](https://github.com/hypfvieh/dbus-java) is a
pure-Java client for it (its `native-unixsocket` transport uses the JDK's own
Unix domain sockets). So the backend is two small files and no native code.
It makes the same conversation as
[`linux.zig`](../original/whereami/src/platform/linux.zig) and the Rust
`zbus` code.

- **[`GeoClue`](platform/src/main/java/whereami/platform/linux/GeoClue.java)**
  describes the part of GeoClue2's API we use as Java interfaces.
  - `Manager` and `Client` extend `DBusInterface`. dbus-java builds a *proxy*
    object for an interface at run time: calling `manager.CreateClient()` sends
    a D-Bus method call and returns the reply. Method names are capitalised
    like the D-Bus methods, not like Java.
  - `@DBusInterfaceName` gives the real D-Bus interface name.
  - `@MethodNoReply` on `Stop()` means "don't wait for an answer".
  - `Client.LocationUpdated` is a class extending `DBusSignal`, nested inside
    the interface it belongs to, which is how dbus-java ties a signal to its
    interface. Its two arguments are *object paths* (`DBusPath`) of `Location`
    objects, not coordinates.
- **`Properties`**: `org.freedesktop.dbus.interfaces.Properties` is dbus-java's
  built-in interface for `org.freedesktop.DBus.Properties`. `Set` writes
  `DesktopId` and `RequestedAccuracyLevel`, and `Get` reads a fix's
  `Latitude`, `Longitude` and `Accuracy`. The accuracy level must be sent as a
  D-Bus `uint32`, so it is wrapped in `new UInt32(8)` (GeoClue's EXACT level);
  a plain Java `long` would be sent as a different type.
- **Subscribe, then `Start`.** `addSigHandler` registers a lambda for
  `LocationUpdated` before `Start()` is called, so the first fix can't slip
  past. Passing the client's remote object limits the match to that client's
  path. The handler runs on a dbus-java thread, so it only reads the
  fix (the `Location` properties) and queues it on a `LinkedBlockingQueue`;
  selection happens on the calling thread. A fix whose properties can't be read is dropped, not
  turned into accuracy 0, which would always look best.
- **The queue and the best-fix window.** The calling thread waits on the queue
  with `poll(timeout)`. [`bestFix`](platform/src/main/java/whereami/platform/linux/LinuxPlatform.java)
  takes the first fix, keeps listening for 250 ms more (never past the
  deadline), and returns the smallest `accuracy`, the earlier on a tie. GeoClue
  can send a coarse IP fix and then a precise one a few milliseconds later; the
  original takes whichever comes first. The window is the same extension as
  in the Rust port. `bestFix` is a plain function of a queue, so
  [`LinuxPlatformTest`](platform/src/test/java/whereami/platform/linux/LinuxPlatformTest.java)
  tests it without a bus. `Stop` is sent in a `finally`, on every path.
- **Errors.** dbus-java turns a D-Bus error name such as
  `org.freedesktop.DBus.Error.AccessDenied` into an exception *class*
  (`org.freedesktop.dbus.errors.AccessDenied`, a `DBusExecutionException`).
  The code maps that class name: containing `AccessDenied` is
  `permission_denied`; any other name, or an exception with no class of its
  own, is `location_unavailable`. (`DBusExecutionException.getType()` returns
  the same string but is deprecated.) The more specific catch must come first
  (the compiler enforces it, since `DBusExecutionException` is a
  `RuntimeException`): `DBusExecutionException`, then
  `DBusException | IOException`, then `RuntimeException`. The last one
  catches anything unchecked dbus-java throws, so no stack trace can reach
  stderr.
- **Connecting.** `DBusConnection` is `AutoCloseable`, so try-with-resources
  closes it. By default dbus-java retries a failed connect for 10 s, and a
  machine with no bus took about 9.8 s to fail. `transportConfig().withTimeout(500)`
  cuts that to 500 ms; it bounds only connecting, not method calls. The overall
  deadline still applies to waiting for a fix, with 50 ms held back for `Stop`
  and exit.
- **No geocoder.** `reverseGeocode` returns an empty `Optional` and `geocode`
  throws `platform_unsupported`, as in the other ports. GeoClue has none.
- **The desktop file.** `DesktopId` is `whereami`, which GeoClue matches with
  [`assets/whereami.desktop`](../assets/whereami.desktop). That file lives at
  the repo root, not in this port, because every port shares it.

| | Zig ([`linux.zig`](../original/whereami/src/platform/linux.zig)) | Rust | Java ([`linux/`](platform/src/main/java/whereami/platform/linux/LinuxPlatform.java)) |
|---|---|---|---|
| D-Bus | libdbus C calls | `zbus` blocking proxies | `dbus-java` interface proxies |
| Subscribing | `dbus_bus_add_match` rule, then pop messages | `receive_location_updated()` iterator, read on its own thread | `addSigHandler` + `LinkedBlockingQueue` |
| Waiting | `read_write_dispatch` loop in 1 s steps until the timeout | reader thread feeds an `mpsc` channel; `best_fix` waits with `recv_timeout` | `queue.poll(timeout)` in `bestFix` |
| Failure | check the error after `Start` (`AccessDenied` is permission denied, else unavailable) | D-Bus error name from `zbus::Error`, mapped | exception class name, mapped |
| Cleanup | explicit `Stop` calls + `defer` unref of messages | explicit no-reply `stop()`; `Drop` closes the connection | explicit no-reply `Stop()` in `finally`; try-with-resources closes the connection |

Live check, Ubuntu 26.04 GNOME, GeoClue 2.7.2, static source: location on
gave the position and `Accuracy: 10m`, exit 0, in about 0.77 s (mostly JVM
start); with GNOME location off it gave `permission_denied`, exit 1; with the
desktop file removed it still located.

## What macOS will need

- **macOS**: by hand again. FFM calls into `libobjc` (`objc_getClass`,
  `sel_registerName`, `objc_msgSend`) to drive `CLLocationManager` and
  `CLGeocoder`. The delegate callbacks would be upcall stubs registered as an
  Objective-C class. CoreLocation also only grants permission to an app
  bundle with an `Info.plist`, so the start script would have to live inside
  a `.app`.
