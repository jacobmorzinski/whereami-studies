# Java milestone 4: Linux backend over GeoClue2 (dbus-java)

A short design (approved 2026-11-26). Follows the Rust reference
([`2026-11-26-rust-linux-geoclue-design.md`](2026-11-26-rust-linux-geoclue-design.md),
[`rust/src/platform/linux.rs`](../../../rust/src/platform/linux.rs)) and
[`spec/behavior.md`](../../../spec/behavior.md), including "Linux fix
selection (extension)".

## Intent

The Java port gets a real location on Linux, the way a Java developer would
write it: through the `dbus-java` library, in contrast to the Windows backend,
which calls WinRT by hand with FFM. Behavior matches the Rust port exactly.

## Approaches (user picked 1)

1. **`dbus-java` 5.2.2, `native-unixsocket` transport (picked).** Pure Java
   (the JDK's own Unix domain sockets), GeoClue as annotated interfaces. As
   promised in the ports design and `java/README.md`.
2. FFM into `libdbus-1` by hand: mirrors `linux.zig`, much more code.
3. GLib/GIO bindings: large native dependency for one conversation.

## Decisions

- Structure unchanged (user, 2026-11-26): every backend compiled everywhere,
  chosen at run time. Optional platforms are a later restructuring.
- New package `whereami.platform.linux` in the `platform` project;
  `Platforms.nativePlatform()` returns `LinuxPlatform` for `"linux"`.
- Dependencies (in `gradle/libs.versions.toml`): `platform` gets
  `com.github.hypfvieh:dbus-java-core` and
  `com.github.hypfvieh:dbus-java-transport-native-unixsocket` 5.2.2 as `api`/
  `implementation`; `app` gets `org.slf4j:slf4j-nop` (matching dbus-java's
  slf4j 2.0.x) at runtime, so SLF4J prints no "no providers" warning. stderr
  must stay exactly as the spec says.
- Conversation, timing and errors as the Rust port: system bus →
  `Manager.CreateClient` → `DesktopId = "whereami"`, `RequestedAccuracyLevel
  = 8` (a D-Bus `uint32`) → subscribe to `LocationUpdated` on our client's
  object only → `Start` → each update's `Latitude`/`Longitude`/`Accuracy`
  into a queue (updates whose properties can't be read are skipped) → after
  the first fix wait 250 ms (clamped to deadline − 50 ms), return smallest
  accuracy (tie → earlier) → `Stop` on every path, including timeout → close
  the connection. No fix by the deadline → `TIMEOUT`.
- Errors: any D-Bus error whose name contains `AccessDenied` →
  `PERMISSION_DENIED`; every other failure (no bus, no GeoClue, other D-Bus
  errors) → `LOCATION_UNAVAILABLE`. Name → error is a pure, tested function.
- The fix selection is a pure, tested function over a `BlockingQueue`
  (Rust's `best_fix` shape), with the same five test cases plus deadline clamp
  and timeout.
- The existing whole-request timeout in `App` stays the outer bound.
- **Clojure (controller's call):** Clojure loads `platform.jar` as a bare jar
  (`:local/root`), so dbus-java isn't pulled in transitively. Without it the
  Clojure port would fail on Linux once `Platforms` returns `LinuxPlatform`.
  This milestone adds dbus-java and slf4j-nop to `clojure/deps.edn` so `main`
  stays green; the Clojure Linux milestone (live checks, docs) comes after.

## Verification

- Unit tests for the error mapping and fix selection; existing Java and
  Clojure tests; `bash conformance/run.sh` for java and clojure; stderr empty
  on a successful live run.
- Manual live checks, as for Rust: (1) location on → 42.3958, -71.1473 ±10 m,
  human + JSON; (2) GNOME location off → `permission_denied`; (3) desktop
  file removed → record behavior.

## Left to the builder

Class names inside `whereami.platform.linux`, how signals are delivered to
the queue, connection lifecycle details.
