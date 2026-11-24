# whereami — Clojure

A Clojure port of [whereami](https://github.com/georgemandis/whereami). It asks
the operating system's own location service where you are. Behavior is defined
by [`spec/behavior.md`](../spec/behavior.md).

Clojure runs on the JVM, and the idiomatic way to reach the OS is through the
host. So this port **reuses the [Java port](../java/README.md)'s `platform`
library** for the OS calls and calls it through Java interop. Everything above
that layer is Clojure: plain data (maps, keywords), functions, and
`clojure.spec` to describe the data.

| | |
|---|---|
| Windows | ✅ the Java port's `WindowsPlatform` (FFM + WinRT by hand), called through interop |
| Linux | stub; arrives when the Java `platform` project gets a Linux backend |
| macOS | stub; the same, plus an `.app` bundle |

## Build, run, test

```powershell
java/gradlew.bat -p java :platform:jar     # the Java OS layer, as java/platform/build/libs/platform.jar
cd clojure
clojure -X:test                            # tests
clojure -T:build uber                      # target/whereami.jar
java -jar target/whereami.jar --json
cd ..
pwsh conformance/run.ps1 -Port clojure
```

While developing, `clojure -M -m whereami.main --json` (in `clojure/`) runs
it without building the jar. Add `-J--enable-native-access=ALL-UNNAMED` to
skip the JVM's FFM warning.

On Windows, live location needs *Settings → Privacy & security → Location*
with *Location services* and *Let desktop apps access your location* on.
Windows may ask whether "Java" may use your location: to Windows, the app is
`java.exe`.

## Anatomy

Read the files in this order.

### 1. [`deps.edn`](deps.edn): dependencies and aliases

`deps.edn` is data: one map, written in EDN (Clojure's literal syntax).
- `:paths ["src"]` is where the code is.
- `:deps` maps a library name to where to get it. Most come from Maven
  (`{:mvn/version "1.4.256"}`). The Java OS layer is
  `{:local/root "../java/platform/build/libs/platform.jar"}`: a jar on disk,
  built by Gradle in the Java project.
- `:aliases` are named variations of that setup:
  - `:test` adds the `test/` folder, test.check and the test runner (a git dependency, pinned by tag and sha).
  - `:build` adds tools.build.
  - `clojure -X:test` runs a function, `cognitect.test-runner.api/test`.
  - `clojure -T:build uber` runs `build/uber`.

You don't need a build step to run or test the code: `clojure` reads the
sources directly. A build is only needed for a jar.

### 2. [`build.clj`](build.clj): a build is a program

tools.build isn't a configuration format; it's a library of functions, and
`build.clj` is an ordinary Clojure namespace that calls them:
1. Copy `src`.
2. AOT-compile `whereami.main`. Clojure normally compiles as it loads; ahead-of-time compilation turns the entry point, and what it loads, into `.class` files.
3. `b/uber` packs everything, Clojure and the Java jar included, into `target/whereami.jar`, with `Main-Class: whereami.main` in the manifest.

The manifest also says `Enable-Native-Access: ALL-UNNAMED`. The JDK reads that
from an executable jar, so the Java platform's FFM calls run without the
"restricted method" warning, and `java -jar` needs no flag. (The Java port
puts the same setting in its start script as `--enable-native-access`.)

### 3. [`main.clj`](src/whereami/main.clj): the entry point

- `(:gen-class)` in the `ns` form makes AOT compilation emit a real class, `whereami.main`, whose static `main` calls `-main`.
- `-main` parses the arguments, runs the app (or takes the help/usage text), prints, and calls `System/exit`. The explicit exit matters: `future` threads aren't daemons, so a hung OS call would otherwise keep the JVM alive.
- `utf8-writer`: Java writes to the console in its code page (437 here), so `-main` wraps `System/out` and `System/err` in UTF-8 writers. That's the same job as C#'s `Console.OutputEncoding` and the Java port's `-Dstdout.encoding`.

### 4. [`cli.clj`](src/whereami/cli.clj): tools.cli, and checks as data

tools.cli describes options as vectors:

```clojure
[nil "--mock LAT,LON" "Use these coordinates instead of location services (no address lookup)."]
```

That's the short flag, the long flag with a placeholder (which means it takes
a value), and the description. `parse-opts` returns a map:
`{:options {:mock "1,2" :json true} :arguments ["Paris"] :errors […] :summary "…"}`.
It already handles what other libraries needed fixes for:
- `--bogus` is an error.
- `-h` is just an option.
- `@home` is an argument.
- Words on both sides of a flag are kept.
- `--` works.

`parse-args` returns one of three maps:
- `{:request …}`
- `{:exit 0 :out …}` for help and version
- `{:exit 2 :err …}` for usage errors

The checks are a single `cond`: a list of question → answer pairs, tried in
order. Before returning a request, it asserts `(s/valid? ::specs/request request)`.

### 5. [`specs.clj`](src/whereami/specs.clj): `clojure.spec`

A spec is a named description of a value. Specs here are used four ways:
- **Checking values.** `::location` is `(s/keys :req-un [::latitude ::longitude ::accuracy])`, a map with those keys, each a finite double. `::at-location` adds the range check, and `cli` uses `(s/valid? ::specs/at-location loc)` for `--at`.
- **Parsing.** `::coords` is an `s/conformer`: `(s/conform ::specs/coords "40.7,-74.0")` *returns* `{:latitude 40.7 …}`, or `::s/invalid`. The rule lives in [`coords.clj`](src/whereami/coords.clj): a plain-decimal regex, then `Double/parseDouble`, then a finiteness check. It's the same rule as Java's `Coordinates`.
- **Shapes that depend on a value.** `::request` is a `s/multi-spec` on `:mode`. A `:lookup` request needs a non-blank `:query`, an `:at` request needs an in-range `:location`, and so on. Each mode's spec is a `defmethod`.
- **Function contracts.** [`app.clj`](src/whereami/app.clj) ends with `(s/fdef run :args … :ret ::specs/outcome)`. [`app_test`](test/whereami/app_test.clj) calls `(stest/instrument `app/run)`, so every call during the tests has its arguments checked; `instrument-catches-bad-request` shows it rejecting an out-of-range `:at`.

Plus one **generative test**, in [`coords_test`](test/whereami/coords_test.clj): test.check makes up 200 pairs of finite doubles, and each must survive `(str lat "," lon)` → `coords/parse` unchanged.

### 6. [`output.clj`](src/whereami/output.clj): formatting

- `(String/format Locale/ROOT "Location: %.4f, %.4f\n" …)`. Clojure's own `format` uses the machine's locale and prints `40,7128` on a German machine. [`locale_test`](test/whereami/locale_test.clj) proves both: plain `format` gives `1,5` there, and `human` still gives `40.7128`.
- **JSON** with data.json:
  - `:key-fn` turns `:postal-code` into `"postal_code"`.
  - `:escape-unicode false` keeps `é` as `é`.
  - An `array-map` keeps the keys in order.
  - `nil` is written as `null`.

### 7. [`app.clj`](src/whereami/app.clj): the logic, on one thread

`run` takes a request map, a platform and a timeout, and returns
`{:stdout :stderr :exit-code}`. It's data in and data out, with no printing,
like every other port. Errors are data too: the platform layer returns
`{:error :permission-denied}`, and `run` turns that into the message and exit
code.

The 10-second limit:

```clojure
(let [f (future (work request p ms))
      result (deref f ms ::timeout)]   ; wait at most ms, else return ::timeout
  …)
```

- `future` runs `work` on a pooled **platform** thread. All of one request's calls run inside it, so COM sees one OS thread from start to finish (`platform-runs-on-one-platform-thread` checks this). A virtual thread would not guarantee that.
- On a timeout, `future-cancel` interrupts the thread, and `-main`'s `System/exit` ends the process anyway.
- `deref` wraps an exception from the future in `ExecutionException`; `run` rethrows the original, so bugs surface as themselves.

### 8. [`platform.clj`](src/whereami/platform.clj): Java interop, and the boundary

The interop syntax:

| Java | Clojure |
|---|---|
| `Platforms.nativePlatform()` | `(Platforms/nativePlatform)` |
| `p.getLocation(Duration.ofMillis(ms))` | `(.getLocation p (Duration/ofMillis ms))` |
| `new Location(1.0, 2.0, 3.0)` | `(Location. 1.0 2.0 3.0)` |
| `class Fake implements Platform { … }` | `(reify Platform (getLocation [_ t] …) …)` |

[`fakes.clj`](test/whereami/fakes.clj) builds test platforms with `reify`.
Checked exceptions don't exist for Clojure: `getLocation` is declared
`throws LocationException`, and Clojure simply lets it be thrown and caught.

This namespace is where Java values become Clojure data:
- `Location` and `Address` records become maps.
- `Optional` becomes the value or `nil`.
- `LocationException` becomes `{:error :permission-denied}`.

**Type hints** (`^Platform p`, `^Location l`) tell the compiler the Java type. Without them, `(.getLocation p …)` would be looked up by reflection on every call. `(set! *warn-on-reflection* true)` makes the compiler warn about any call it couldn't resolve; building the jar prints no such warnings.

| | Zig ([`windows.zig`](../original/whereami/src/platform/windows.zig)) | Rust | C# | Java | F# | Clojure |
|---|---|---|---|---|---|---|
| Windows call | `RoActivateInstance` + vtables | `windows` crate | WinRT projection | FFM by hand | WinRT projection | Java's `WindowsPlatform`, through interop |

## What Linux and macOS will need

Nothing in this folder, beyond rebuilding `platform.jar`. When the Java
`platform` project gets a Linux backend (`dbus-java`) or a macOS one (FFM and
the Objective-C runtime), `Platforms/nativePlatform` returns it and this port
uses it unchanged. On macOS, CoreLocation also needs an `.app` bundle around
the JVM.
