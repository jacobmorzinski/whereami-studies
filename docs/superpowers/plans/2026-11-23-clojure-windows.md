# Clojure Port (Windows) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Clojure `whereami` that passes `spec/cases.json` on Windows, plus an Anatomy walkthrough of what a small Clojure CLI looks like. It reuses the Java port's OS layer through host interop, and describes its data with `clojure.spec.alpha`.

**Architecture:**
- **The OS layer is reused, not rewritten.** The design left a choice: reuse the Java `platform` library, or call FFM from Clojure through `coffi`. **Ruling: reuse.** Leaning on the host platform is the idiomatic Clojure choice. The Java port's `platform` project was split out for exactly this. And a second by-hand COM layer would teach nothing the Java one doesn't. So the Clojure port depends on `java/platform/build/libs/platform.jar` (`:local/root`), and calls `whereami.platform.Platforms/nativePlatform` and its interface through Java interop.
- **Everything else is data and functions:**
  - Maps for locations, requests and outcomes.
  - Keywords for errors.
  - `clojure.spec.alpha` specs that describe those maps. They are used for validation (`s/valid?`), parsing (`s/conformer`), function contracts (`s/fdef` + `stest/instrument` in tests), and one generative property test.
- **The boundary to Java:** `whereami.platform` converts Java records to maps, and the `LocationException` the Java platform throws to `{:error :permission-denied}`-style data.
- `app/run` is pure and tested with `reify` fakes of the Java `Platform` interface.

**Tech Stack:**
- **Toolchain:** Clojure 1.12.5 (CLI `clojure`), JDK 27.
- **Libraries:** `org.clojure/tools.cli` 1.4.256, `org.clojure/data.json` 2.5.2, `org.clojure/spec.alpha`, which comes with Clojure.
- **Tests:** `org.clojure/test.check` (latest stable; generative test only), and `io.github.cognitect-labs/test-runner` (git tag `v0.5.1`, sha `dfb30dd`).
- **Build:** `io.github.clojure/tools.build` (git tag `v0.10.14`, sha `1176afd`) for the uberjar.
- **Editing aids, installed on this machine:** `clj-paren-repair` fixes unbalanced delimiters and formats; `clj-kondo` lints.

**Spec:** `docs/superpowers/specs/2026-11-23-whereami-ports-design.md`, plus `spec/behavior.md` and `spec/cases.json`. The Java port is the reference for the platform layer, and `rust/src/app.rs` for stderr wording.

**Spike (throwaway, 2026-11-23):** I checked tools.cli 1.4.256, data.json 2.5.2 and `:local/root` on the Java jar before writing this plan.
- **tools.cli:**
  - `--mock=-33.8,151` and `--mock -33.8,151` both give `{:mock "-33.8,151"}`, and `-h` gives `{:help true}`.
  - `--bogus` gives `:errors ["Unknown option: \"--bogus\""]`.
  - `@home` and `"   "` stay arguments.
  - `Paris --json London` gives `:arguments ["Paris" "London"]`: words around a flag are kept, unlike F#'s Argu.
  - `-- -x` gives `:arguments ["-x"]`.
  - `--mock=1,2 --mock=3,4` keeps the last value. `--json=x` gives `{:json true}`. `--mock=` gives `{:mock ""}`.
- **data.json:** `write-str` keeps key order for an `array-map`, writes `nil` as `null` and doubles as `1.0`. `:escape-unicode false` keeps `é`.
- **The Java jar:**
  - `{:local/root "<abs>/java/platform/build/libs/platform.jar"}` works.
  - `(Platforms/nativePlatform)` gives a `whereami.platform.windows.WindowsPlatform`, and `(Platforms/osName)` gives `"windows"`.
  - Non-ASCII on stdout showed as `�` in a captured run, so the output encoding is set explicitly (below).

## Global Constraints

- Version output: `whereami 0.4.0 (<os>)` for both `--version` and `-v`. `<os>` comes from `whereami.platform.Platforms/osName`.
- Exit codes: usage errors **2**, runtime errors **1**, success 0. Usage errors go to stderr only. Help goes to stdout with exit 0.
- Human output, exact:
  - `Location: %.4f, %.4f`
  - `Accuracy: %.0fm` (omitted for `--at` and for address lookups)
  - `Address: …` (non-empty fields joined by `, `)
  - Use `(String/format Locale/ROOT …)`, never bare `format`, which uses the default locale. Line endings are `\n`.
- JSON keys: `latitude`, `longitude`, `accuracy`, `address` (`null` or `{street, city, state, postal_code, country}`), in that order. Errors are `{"error":"<key>"}`. Write with `:escape-unicode false`. stderr messages match `rust/src/app.rs` word for word.
- Coordinates:
  - Split at the first comma and trim **spaces only**.
  - Each part must match `[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?` and be finite after `Double/parseDouble`. This is the same rule as the Java port's `Coordinates`, written in Clojure; it isn't called from Java, because `Coordinates` is in the Java *app* project, not `platform`.
  - `--mock` is not range-checked; `--at` must be within −90..90 and −180..180 inclusive.
- `completions` as the first argument is a usage error with the message `completions are not available in this port`.
- Any address word starting with `-` is a usage error. tools.cli already rejects unknown options; this catches the words that follow `--`.
- 10-second limit on the whole request. All platform calls for one request run inside **one** `future`, so on one OS thread. Clojure's `future` pool uses platform threads, which COM needs. `App` waits with `(deref f timeout-ms ::timeout)` and calls `future-cancel` on a timeout. Exceptions other than `LocationException` propagate, unwrapped from the future's `ExecutionException`.
- Output: in `-main`, bind `*out*`/`*err*` to UTF-8 writers over `System/out`/`System/err`, flush both, then `(System/exit code)`. Exiting explicitly also ends an abandoned future thread.
- Native access: the uberjar manifest has `Enable-Native-Access: ALL-UNNAMED`. The JDK honors this attribute for executable jars, so `java -jar` needs no flag and prints no FFM warning.
- `(set! *warn-on-reflection* true)` in every namespace that does interop. A build with no `Reflection warning` lines means every Java call is resolved at compile time.
- Don't modify `original/`, `rust/`, `csharp/` or `fsharp/`. **Don't modify `java/` sources**; this port only consumes `platform.jar`.
- Git is local only. Branches `clojure-m1-scaffold` (Tasks 1–3) and `clojure-m3-docs` (Task 4) are each merged into `main` with `git merge --no-ff` once their checks pass. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
  - **Ruling:** there is no separate Windows milestone. The Windows backend is the Java port's, so the live check happens in Task 3.
- After writing or editing any `.clj` file, run `clj-paren-repair <file>` and then `clj-kondo --lint clojure/src clojure/test`. Expected: 0 errors.

## Review Focus

- **German locale.** Clojure's `format` uses the default locale, so it would print `40,7128`. Output must print `40.7128`. Covered by `locale-test`.
- **`Double/parseDouble` leniency.** `nan`, `Infinity`, `0x1p3`, `1d`, `1e999`, a leading tab, and `""` (from `--mock=`) must all be usage errors. Covered by `rejects-non-decimal` and the CLI test `empty-mock-value`.
- **Exceptions through the future.** A `LocationException` must become an error outcome. Any other exception must reach the caller as itself, not as `ExecutionException`. And a platform that never returns must still give `timeout` within the limit. Covered by `app-test/timeout-holds`, `unexpected-exception-propagates` and `platform-runs-on-one-platform-thread`.
- **Java ↔ Clojure boundary.**
  - Java `Location`/`Address` records become maps with the spec'd keys.
  - `Optional.empty()` becomes `nil`.
  - Each `LocationError` enum value becomes the right keyword and JSON key; `GEOCODING_FAILED` becomes `unknown_error`.
  - Covered by `platform-test`.
- **UTF-8 output.** `Montréal` in an address must reach stdout as the UTF-8 bytes `C3 A9`, whatever the console code page. Covered by `main-test/utf8-writer`.

---

## File Structure

```
clojure/deps.edn          :paths ["src"], deps (incl. whereami/platform {:local/root "../java/platform/build/libs/platform.jar"}),
                          aliases :test (test-runner + test.check, :extra-paths ["test"]) and :build (tools.build)
clojure/build.clj         tools.build: clean, uber (compile whereami.main, manifest Enable-Native-Access)
clojure/src/whereami/
  specs.clj               ::location ::at-location ::address ::request (multi-spec on :mode) ::outcome
  coords.clj              parse
  output.clj              human, json, json-error
  cli.clj                 parse-args → {:request …} | {:exit 0 :out …} | {:exit 2 :err …}
  platform.clj            Java interop: native, get-location, reverse-geocode, geocode (→ maps / {:error kw})
  app.clj                 run, s/fdef run
  main.clj                -main (:gen-class), utf8-writer
clojure/test/whereami/    specs_test coords_test output_test locale_test cli_test platform_test app_test main_test
clojure/README.md
```
Add `clojure/target/`, `clojure/.cpcache/`, `clojure/.clj-kondo/.cache/` and `clojure/.nrepl-port` to `.gitignore`.

Build: from `clojure/`, `../java/gradlew.bat -p ../java :platform:jar` then `clojure -T:build uber`. This produces `clojure/target/whereami.jar`. Run it with `java -jar clojure/target/whereami.jar`.

---

### Task 1: Project, specs, coordinates, output (branch `clojure-m1-scaffold`)

**Files:**
- Create: `deps.edn`, `build.clj` (clean only for now), `specs.clj`, `coords.clj`, `output.clj`, and the tests `specs_test`, `coords_test`, `output_test`, `locale_test`
- Modify: `.gitignore`

**Interfaces:**
- Produces:
  - `whereami.coords/parse : String → {:latitude double :longitude double :accuracy 0.0} | nil`
  - `whereami.specs`:
    - `::location` is `(s/keys :req-un [::latitude ::longitude ::accuracy])`, each a finite `double?`.
    - `::at-location` is `(s/and ::location <lat in −90..90> <lon in −180..180>)`.
    - `::address` is `(s/keys :req-un [::street ::city ::state ::postal-code ::country])`, all `string?`.
    - `::coords` is `(s/conformer #(or (coords/parse %) ::s/invalid))`.
    - `::outcome` is `(s/keys :req-un [::stdout ::stderr ::exit-code])`, with exit code in `#{0 1}`.
    - `::request` is a `s/multi-spec` on `:mode`:
      - `:current`: `{:mode :json}`.
      - `:mock` and `:at`: add `:location`, which `:at` checks against `::at-location`.
      - `:lookup`: adds `:query`, a non-blank string.
  - `whereami.output`:
    - `human : location, address-or-nil, show-accuracy? → String`
    - `json : location, address-or-nil → String` (ends `\n`)
    - `json-error : String → String` (ends `\n`)
    - The JSON key `postal_code` comes from the map key `:postal-code`: convert `-` to `_` in a `:key-fn`.

- [ ] **Step 1: Write the project files.** `deps.edn` with the deps and aliases above. Running the tests is `clojure -X:test` from `clojure/`.
- [ ] **Step 2: Write failing tests.**

```clojure
;; coords_test
(deftest parses
  (are [text lat lon] (= {:latitude lat :longitude lon :accuracy 0.0} (coords/parse text))
    "40.7128,-74.0060" 40.7128 -74.006,  " 51.5 , -0.1276" 51.5 -0.1276,  "-33.8688,151.2093" -33.8688 151.2093,  "1e1,+2" 10.0 2.0))
(deftest rejects-non-decimal
  (doseq [t ["40.7128" "abc,def" "1,2,3" "nan,0" "NaN,0" "0,inf" "Infinity,0" "0x1p3,0" "1d,2" "1f,2" "1e999,0" "\t1,2" "," "" "1,"]]
    (is (nil? (coords/parse t)) t)))
(defspec round-trips 200   ; test.check: any finite pair printed with str parses back to itself
  (prop/for-all [lat (gen/double* {:infinite? false :NaN? false}) lon (gen/double* {:infinite? false :NaN? false})]
    (= {:latitude lat :longitude lon :accuracy 0.0} (coords/parse (str lat "," lon)))))
;; specs_test
(deftest at-location-range        ; (s/valid? ::specs/at-location {:latitude 90.0 :longitude -180.0 :accuracy 0.0}) true; 91.0 → false; -180.5 → false
(deftest coords-conformer         ; (s/conform ::specs/coords "1,2") → location map; (s/conform ::specs/coords "x") → ::s/invalid
(deftest request-multi-spec       ; valid: {:mode :current :json false}, {:mode :lookup :query "Paris" :json true}; invalid: {:mode :at :location {:latitude 91.0 …} :json false}, {:mode :lookup :query "  " :json false}
;; output_test
(deftest human-with-accuracy   (is (= "Location: 40.7128, -74.0060\nAccuracy: 0m\n" (output/human loc0 nil true))))
(deftest human-skips-empty-fields)   ; "Location: 38.8977, -77.0366\nAddress: 1600 Pennsylvania Ave NW, Washington, 20500, US\n"
(deftest json-null-address)          ; parse back with data.json: latitude 40.7128, address nil; raw text key order latitude < longitude < accuracy < address
(deftest json-escapes-address)       ; street 5 "Q" St\ round-trips; "postal_code" key; raw text contains "Montréal"
(deftest json-error-shape      (is (= "{\"error\":\"timeout\"}\n" (output/json-error "timeout"))))
;; locale_test — fixture: (Locale/setDefault Locale/GERMANY), restore after
(deftest human-is-locale-independent)  ; starts with "Location: 40.7128, -74.0060"
(deftest parse-is-locale-independent)  ; (:latitude (coords/parse "40.7128,-74.006")) = 40.7128
```

- [ ] **Step 3: Run** `clojure -X:test` (in `clojure/`). Expected: FAIL, namespace `whereami.coords` not found.
- [ ] **Step 4: Implement** `coords.clj`, `specs.clj` and `output.clj`. Run `clj-paren-repair` and `clj-kondo` on them.
- [ ] **Step 5: Run** `clojure -X:test`. Expected: all pass, 0 failures, 0 errors.
- [ ] **Step 6: Commit** on the new branch `clojure-m1-scaffold`: `clojure: project, specs, coordinates, and output`.

---

### Task 2: Argument parsing with tools.cli

**Files:**
- Create: `cli.clj`, `cli_test.clj`

**Interfaces:**
- Consumes: `coords/parse`, `::specs/coords`, `::specs/at-location`, `::specs/request`.
- Produces: `whereami.cli/parse-args : [String] → map`, one of:
  - `{:request {:mode … :json bool …}}`
  - `{:exit 0 :out "<help or version>\n"}`
  - `{:exit 2 :err "<message>\n"}`

  Every `:request` returned satisfies `::specs/request`, asserted with `s/valid?` before returning.

Option spec:

```clojure
[["-h" "--help" "Show this help and exit."]
 ["-v" "--version" "Print the version and exit."]
 [nil "--json" "Print JSON instead of text."]
 [nil "--mock LAT,LON" "Use these coordinates instead of location services (no address lookup)."]
 [nil "--at LAT,LON" "Look up the address at these coordinates."]]
```

- The values stay strings in `:options`. Converting them in `parse-args` with `s/conform ::specs/coords` keeps a single error message per bad value. (`:parse-fn`/`:validate` would work too; this way the spec is the one place the rule lives.)
- Order of checks:
  1. tools.cli `:errors` → exit 2.
  2. `:help` → exit 0 with usage (description line plus `:summary`).
  3. `:version` → exit 0 with the version line.
  4. First argument `completions` → exit 2.
  5. Any argument starting with `-` → `unknown flag`.
  6. A bad `--mock`/`--at` value → exit 2.
  7. `--at` not `::at-location` → exit 2.
  8. Arguments present but blank after `(str/join " ")` + `str/trim` → `the address is empty`.
  9. `--mock` together with `--at` → exit 2. An address with either → exit 2.
- Error text is `Error: <message>\n`. tools.cli's own errors use the same form.

- [ ] **Step 1: Write failing tests** in `cli_test`:

```clojure
(deftest version-both-spellings)   ; "--version" and "-v" → {:exit 0 :out (str "whereami 0.4.0 (" (Platforms/osName) ")\n")}
(deftest help-both-spellings)      ; "-h" and "--help" → exit 0, :out contains "--mock"
(deftest unknown-flag)             ; ["--bogus"] → exit 2; ["--" "-x"] → exit 2
(deftest at-sign-is-address)       ; ["@home"] → [:request :mode] :lookup, :query "@home"
(deftest words-around-a-flag-are-kept) ; ["350" "5th" "--json" "Ave"] → :query "350 5th Ave", :json true; ["--bogus" "--json" "Paris"] → exit 2
(deftest mock-negative-equals-form)    ; ["--mock=-33.8688,151.2093"] → :mode :mock, location -33.8688/151.2093
(deftest completions-reserved)     ; ["completions" "bash"] → exit 2, :err contains "completions are not available in this port"
(deftest empty-mock-value)         ; ["--mock="] → exit 2
(deftest conflicts)                ; ["--at=1,2" "--mock=1,2"], ["--mock=1,2" "Paris"], ["   "], ["--at=91,0"], ["--at=0,-180.5"], ["--json" "--mock=x"] → each exit 2, no :out
(deftest at-boundary)              ; ["--at=90,-180"] → :mode :at
(deftest no-arguments)             ; [] → {:request {:mode :current :json false}}
```

- [ ] **Step 2: Run** `clojure -X:test`. Expected: FAIL (`whereami.cli` not found).
- [ ] **Step 3: Implement** `cli.clj`, then run `clj-paren-repair` and `clj-kondo`.
- [ ] **Step 4: Run** `clojure -X:test`. Expected: all pass.
- [ ] **Step 5: Commit** `clojure: argument parsing with tools.cli and spec`.

---

### Task 3: Java interop, app, entry point, uberjar, conformance, live check

**Files:**
- Create: `platform.clj`, `app.clj`, `main.clj`, `platform_test`, `app_test`, `main_test`
- Modify: `build.clj` (adds `uber`), `conformance/run.ps1`, `conformance/ports.json`, `PROGRESS.md`

**Interfaces:**
- Consumes: the Java `whereami.platform.Platform` interface (`getLocation(Duration)`, `reverseGeocode(double,double)`, `geocode(String)`, all `throws LocationException`), `LocationError`, `Platforms`.
- Produces:
  - `whereami.platform` (all type-hinted, no reflection):
    - `native : → Platform`
    - `get-location : Platform, timeout-ms → location-map | {:error kw}`
    - `reverse-geocode : Platform, lat, lon → address-map | nil`
    - `geocode : Platform, query → {:location … :address …} | nil | {:error kw}`
    - `error-key : LocationError → kw`: `PERMISSION_DENIED` → `:permission-denied`, and so on, with `GEOCODING_FAILED` → `:geocoding-failed`.
    - `json-key : kw → String`, with `:geocoding-failed` → `"unknown_error"`.
  - `whereami.app/run : request, Platform, timeout-ms → outcome`, with `(s/fdef run :args (s/cat :request ::specs/request :platform #(instance? Platform %) :timeout-ms pos-int?) :ret ::specs/outcome)`.
  - `whereami.main`:
    - `-main` (`(:gen-class)`).
    - `utf8-writer : OutputStream → java.io.Writer`, a `PrintWriter` over an `OutputStreamWriter` with UTF-8 and autoflush off; `-main` flushes it.

`app/run` matches `rust/src/app.rs` case for case:
- `:mock` returns without the platform.
- Everything else runs as one `(future …)`, waited on with `(deref f timeout-ms ::timeout)`.
- On a timeout, `future-cancel` and return the timeout outcome.
- An `ExecutionException` is unwrapped, and its cause rethrown.

`-main` does `parse-args`, prints `:out`/`:err` or runs the app, then flushes and calls `System/exit`.

`build.clj` `uber`:
- `b/copy-dir` of `src`.
- `b/compile-clj` with `:ns-compile '[whereami.main]`.
- `b/uber` with `:main 'whereami.main` and `:manifest {"Enable-Native-Access" "ALL-UNNAMED"}`.
- The jar is `target/whereami.jar`.

Conformance runner: add optional `"launcher"` support to `run.ps1`. When a port's config has `"launcher": ["java", "-jar"]`:
- The process is `launcher[0]`, resolved with `Get-Command`.
- Its arguments are `launcher[1..]`, then the port's absolute `path`, then the case args.
- The MISSING check still tests `path`.

Add the `clojure` entry to `ports.json`:
- `path`: `clojure/target/whereami.jar`
- `launcher`: `["java", "-jar"]`
- `build`: `java/gradlew.bat -p java :platform:jar; Push-Location clojure; clojure -T:build uber; Pop-Location`
- features: `completions:false`

- [ ] **Step 1: Write failing tests.**

```clojure
;; platform_test — a reify of whereami.platform.Platform returning Java records / throwing LocationException
(deftest location-record-to-map)   ; getLocation → (Location. 1.0 2.0 3.0) ⇒ {:latitude 1.0 :longitude 2.0 :accuracy 3.0}
(deftest error-to-keyword)         ; throws (LocationException. LocationError/PERMISSION_DENIED) ⇒ {:error :permission-denied}
(deftest empty-optional-is-nil)    ; reverseGeocode → (Optional/empty) ⇒ nil; (Optional/of (Address. "s" "c" "" "p" "CC")) ⇒ {:street "s" … :postal-code "p" :country "CC"}
(deftest json-keys)                ; all five LocationError values → permission_denied, location_unavailable, timeout, unknown_error, platform_unsupported
;; app_test — use-fixtures :once with (stest/instrument `app/run) / unstrument
(deftest mock-skips-platform)      ; reify whose methods throw → mock outcome "Location: 40.7128, -74.0060\nAccuracy: 0m\n", exit 0
(deftest current-with-address)
(deftest at-without-address-warns) ; stdout "Location: 90.0000, -180.0000\n", stderr "Warning: no address found for these coordinates.\n", exit 0
(deftest permission-denied-json)   ; {"error":"permission_denied"}\n, stderr starts "Error: location permission denied.", exit 1
(deftest unsupported-location)     ; UnsupportedPlatform from the jar → "Error: location is not supported on this platform yet.\n"
(deftest lookup-unsupported)       ; {"error":"platform_unsupported"}\n + "Error: address lookup is not supported on this platform.\n"
(deftest lookup-not-found)         ; geocode → Optional/empty ⇒ address_not_found, "couldn't find a location for \"Paris\""
(deftest lookup-found-omits-accuracy)
(deftest timeout-holds)            ; getLocation sleeps in a loop swallowing InterruptedException; run with 200 ms ⇒ timeout outcome in < 2 s
(deftest platform-runs-on-one-platform-thread) ; getLocation and reverseGeocode record Thread/currentThread: same thread, (.isVirtual t) false, not the test thread
(deftest unexpected-exception-propagates)      ; getLocation throws IllegalStateException ⇒ (thrown? IllegalStateException …)
(deftest instrument-catches-bad-request)       ; (app/run {:mode :at :location {:latitude 91.0 …} :json false} p 100) throws (spec instrumentation)
;; main_test
(deftest utf8-writer)              ; write "é" through (main/utf8-writer baos), flush ⇒ bytes [0xC3 0xA9]
```

- [ ] **Step 2: Run** `clojure -X:test`. Expected: FAIL (`whereami.platform` not found). If `platform.jar` is missing, build it first with `java/gradlew.bat -p java :platform:jar`.
- [ ] **Step 3: Implement** `platform.clj`, `app.clj`, `main.clj` and `build.clj` `uber`, then run `clj-paren-repair` and `clj-kondo`.
- [ ] **Step 4: Run** `clojure -X:test`. Expected: all pass. Then run `clojure -T:build uber`. Expected: `target/whereami.jar`, and no `Reflection warning` in the output.
- [ ] **Step 5: Check by hand.** Run `java -jar clojure/target/whereami.jar --mock=1,2`. Expected: the mock output, exit 0, and **stderr empty** (no FFM warning).
- [ ] **Step 6: Run conformance.**
  1. Make the `run.ps1` launcher change and add the `ports.json` entry.
  2. Run `pwsh conformance/run.ps1 -Port clojure`. Expected: 29 PASS.
  3. Run `pwsh conformance/run.ps1`. Expected: all five ports PASS.
  4. Run the self-test with `-Port clojure`. Expected: every case FAIL.
- [ ] **Step 7: Live check.**
  - Run `java -jar clojure/target/whereami.jar` and the same with `--json`. Expected: exit 0, nothing on stderr, the same position as Rust. Compare without printing.
  - Update PROGRESS:
    - The clojure row becomes scaffold ✅, conformance ✅, Windows live 🟨.
    - Add a note asking the user to check with location access off. Windows may show a "Java is requesting permission" dialog, as it did for Java.
- [ ] **Step 8: Commit and merge.** Commit `clojure: Java interop, app, uberjar; passes conformance`, then merge `clojure-m1-scaffold` into `main` with `--no-ff`.

---

### Task 4: Anatomy and comparison (branch `clojure-m3-docs`)

**Files:**
- Create: `clojure/README.md`
- Modify: `docs/comparison.md` (Clojure column), `PROGRESS.md` (Anatomy ✅)

The README follows the shape of the other READMEs. Anatomy, in reading order:
1. `deps.edn`: deps, aliases, `:local/root` on a jar; no build file needed to run or test.
2. `build.clj`: tools.build as a program, not a config; AOT for `-main` only; the manifest attribute.
3. `main.clj`: `-main`, `:gen-class`, why `System/exit` is explicit, UTF-8 writers.
4. `cli.clj`: tools.cli's option vectors and `parse-opts` result map; why checks are plain `cond` over data.
5. `specs.clj`: specs as named predicates on maps; `s/conformer` for parsing; the multi-spec for request modes; `s/fdef` + `instrument` in tests; the generative property in `coords_test`.
6. `output.clj`: `String/format` with `Locale/ROOT`, data.json `:key-fn`, `:escape-unicode false`.
7. `app.clj`: data in, data out; `future` + `deref` timeout; why that gives one platform thread.
8. `platform.clj`: Java interop syntax (`(.getLocation p d)`, `(Platforms/nativePlatform)`, `(Location. …)`), `reify` for fakes, type hints and `*warn-on-reflection*`, and converting at the boundary (records → maps, `LocationException` → data, `Optional` → `nil`).

The README ends with a Zig / Rust / C# / Java / F# / Clojure row, where Clojure's cell reads "Java's `WindowsPlatform`, called through interop", and with how Linux and macOS come for free once the Java `platform` project has them.

- [ ] **Step 1: Write** the README and the comparison column. Edit `docs/comparison.md` with exact-string edits (the Edit tool), **not** `sed`: the F# port's sed edit corrupted that table. Check every relative link resolves, and that every table row has the same number of cells.
- [ ] **Step 2: Commit and merge.** Commit `clojure: Anatomy walkthrough and comparison column`, then merge `clojure-m3-docs` with `--no-ff`.
