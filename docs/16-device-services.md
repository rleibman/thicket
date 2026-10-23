# 16 — Device services: a plan for review

**Status (2026-10-22): decided; storage and SQLite built on GTK, Android, macOS and iOS.** The
owner's answers are in §16.9. Built: app directories, preferences and SQLite through JDBC
(§16.5, §16.6), measured on GTK, the Android emulator, macOS and the iOS simulator (Apple:
#98). The iOS HTTP transport is
#90 (Mac). Covers location, motion, camera, files, storage and the rest:
what a real app is likely to need, what existing libraries already cover, and what Thicket
itself has to provide.

This is not a widget question. `docs/02` puts storage, networking and permissions among the
"batteries in the box" a framework is judged by; `docs/05` §5.7 puts maps, push plumbing
beyond a hook, and background services out of v1; `docs/11` §11.9 keeps the HTTP client in
the app. The plan below is consistent with all three.

## 16.1 The principle: provide only what the platform owns

Most of this list is already solved in the Scala ecosystem, on every runtime Thicket runs on:
the JVM (Android's ART) and Scala Native (GTK, AppKit, UIKit). Thicket should not wrap what
already works. What no library can provide is anything that needs the **host**: the
`Activity`, the app bundle, a delegate object in the Swift shim, the main loop. That is the
whole of Thicket's job here:

| Thicket provides | Because |
|---|---|
| **Where**: the app's own directories | Android `Context.getFilesDir`, Apple `FileManager` containers and XDG directories are host knowledge |
| **Asking**: runtime permissions | Android routes the answer through the `Activity`; iOS needs `Info.plist` strings or it terminates the app |
| **Hardware streams**: location, motion | delegate and listener objects that live in the host and call back on its thread |
| **System UI**: pickers, camera capture, share | presented from the host's window or `Activity`, with the result arriving there |
| **Lifecycle**: foreground, background, terminating | sensors must stop in the background; the host is the only one told |

Everything else — reading a file, a SQL query, an HTTP call — is an existing library, and the
plan's job is to make sure it works on all four platforms and to fill the gaps where it does not.

## 16.2 What existing libraries already cover (checked 2026-10-21)

Resolved with `cs complete-dep` on 2026-10-21; versions are the newest published.

| Need | Use | JVM / Android | Scala Native | Notes |
|---|---|---|---|---|
| File I/O | `java.nio.file` | yes (Android API 26+, our `minSdk`) | yes (javalib) | Already in use cross-platform: `Gallery.logoBytes` reads with `Files.readAllBytes` on GTK, Android and iOS |
| File I/O, ZIO-shaped | `ZIO.attemptBlocking` over `java.nio.file` | yes | yes | **zio-nio is JVM-only** (2.0.2; no `native0.5` artefact), so it cannot be the cross-platform API |
| File I/O, convenience | os-lib | 0.11.8 | 0.11.8 | Optional; nothing requires it |
| Streams | zio-streams | 2.1.27 | 2.1.27 | The shape for sensor streams in the ZIO bridge |
| HTTP | sttp client4 | 4.1.0, `HttpURLConnectionBackend` works on Android | 4.1.0, **only `CurlBackend`** (synchronous) | See §16.4: iOS has no libcurl |
| GraphQL | caliban-client | 3.2.0 | 3.2.0 | Phase 6's meal-planner client; rides on sttp |
| JSON | zio-json / upickle | 1.1.1 / 4.4.3 | same | |
| HTTP, ZIO | zio-http | yes | **no Native artefact** | Not usable as the client on GTK/Apple |
| SQLite, through JDBC | `java.sql` | in the platform; **no maintained driver** | `scala-native-jdbc(-sqlite)` 0.0.6 | See §16.5. (`sqlite4s` stops at SN 0.4) |
| Time | `java.time` | yes | scala-java-time 2.8.0 | |

## 16.3 The likely list, tiered

**Tier 1: a real app needs these before it needs sensors.** Phase 6, the meal-planner, needs
most of them, which is why they come first.

| Service | Shape (proposal) | Android | iOS | macOS | GTK |
|---|---|---|---|---|---|
| **HTTP transport** | an sttp `Backend` per platform; the app keeps sttp/caliban | `HttpURLConnectionBackend` + `INTERNET` permission | **new**: an sttp backend over `URLSession`, through the shim | `CurlBackend` (libcurl believed to be in the SDK; to confirm on the Mac) | `CurlBackend` |
| **App directories** | `Storage.dirs: AppDirs(data, cache, temp)` as `java.nio.file.Path` | `getFilesDir`, `getCacheDir` | Application Support, Caches, the temporary directory, each under the bundle id (built, #98) | same (built, #98) | `$XDG_DATA_HOME/<app>`, `$XDG_CACHE_HOME/<app>` |
| **Preferences** (small key–value) | `Preferences`: get/set strings, numbers, booleans | `SharedPreferences` | ~~`UserDefaults`~~ a file under the data dir, as on GTK (built, #98; `docs/decisions.md` 2026-10-22) | ~~`UserDefaults`~~ the same | a file under the data dir (GSettings needs an installed schema) |
| **Secure storage** (tokens) | `Secrets`: get/set/delete a string | Android Keystore-wrapped value | Keychain | Keychain | libsecret (Secret Service), optional dependency |
| **SQLite** | plain JDBC; `Sqlite.open(name): java.sql.Connection` | Thicket's JDBC driver over `android.database.sqlite` | `scala-native-jdbc-sqlite` | same | same |
| **Lifecycle** | `AppLifecycle.state: Signal[Foreground \| Background]` and an `onTerminate` hook | `Activity` callbacks (already in `ThicketActivity`) | scene notifications | `NSApplication` notifications | window `is-active`, `GApplication` shutdown |
| **Permissions** | `Permissions.request(Permission.Location)` → `Granted \| Denied \| Restricted` | `requestPermissions` + manifest | usage strings in `Info.plist` | entitlements (sandboxed apps) | mostly n/a; xdg-desktop-portal asks for location and camera |
| **Open URL, share, clipboard** | `Link` exists; add `Share.text/file`, `Clipboard.get/set` | `ACTION_SEND`, `ClipboardManager` | `UIActivityViewController`, `UIPasteboard` | `NSSharingServicePicker`, `NSPasteboard` | `GdkClipboard`; share is n/a |

**Tier 2: the hardware.**

| Service | Shape (proposal) | Android | iOS | macOS | GTK |
|---|---|---|---|---|---|
| **Location** | `Location.updates(accuracy): Signal[Option[Fix]]`, scoped to an `Owner`; `Location.current` one-shot | `LocationManager` (framework, not Play Services; §16.9) | `CLLocationManager` | `CLLocationManager` | GeoClue2 over D-Bus via GIO, where installed; otherwise `Unavailable` |
| **Motion** | `Motion.acceleration`, `.rotationRate`, `.attitude`: `Signal[Option[Vec3]]` at a requested rate | `SensorManager` | `CMMotionManager` | none (no motion hardware): `Unavailable` | iio-sensor-proxy over D-Bus on convertibles, else `Unavailable` |
| **Camera: capture** | `Camera.capturePhoto(): Future/ZIO[Option[Path]]`, the platform's own camera UI | `ACTION_IMAGE_CAPTURE` intent writing to a `MediaStore` URI (`FileProvider` is AndroidX) | `UIImagePickerController` (camera) | n/a (no system camera UI): `Unavailable` | n/a: `Unavailable` (portal camera is a PipeWire stream, i.e. preview, not capture) |
| **Photo picker** | `Photos.pick(limit): …[Seq[Path]]` | Android photo picker (`ACTION_PICK_IMAGES`, API 33+; `ACTION_GET_CONTENT` below) | `PHPickerViewController` | `NSOpenPanel` filtered to images | `GtkFileDialog` filtered to images |
| **Document picker / save** | `Files.open(types)`, `Files.save(name, bytes)` | Storage Access Framework | `UIDocumentPickerViewController` | `NSOpenPanel` / `NSSavePanel` | `GtkFileDialog` (uses the portal when sandboxed) |
| **Haptics** | `Haptics.impact(light \| medium \| heavy)`, `.selection` | `View.performHapticFeedback` | `UIImpactFeedbackGenerator` | `NSHapticFeedbackManager` (trackpads) | no-op |

**Tier 3: later, each its own design.**
- **Live camera preview** is a *widget*, not a service. Camera2 on Android (CameraX is
  AndroidX), `AVCaptureVideoPreviewLayer` on Apple, a PipeWire stream on GTK. Under the
  gallery rule it ships on every platform at once, so it is a large piece of work.
- **Local notifications**, background location, audio recording and playback, biometrics,
  Bluetooth, NFC, contacts and calendar.
- **Out of v1 already** (`docs/05` §5.7): maps, push beyond a hook, background services.

## 16.4 The finding that affects phase 6 now: HTTP on iOS

sttp's only Scala Native backend is `CurlBackend`. Linux ships libcurl, and macOS is believed to (to confirm on the Mac). **The iOS SDK
does not** offer it as a public library, so caliban-client, which runs over sttp, has no transport on the iOS target that
phase 6 requires. Two ways out:
1. **An sttp backend over `URLSession`**, implemented in the Swift shim and exposed through
   the generated ABI. It is small (a request in, status, headers and body out, on a callback),
   uses the platform's TLS, proxy and App Transport Security settings, and benefits macOS
   too. **Recommended.**
2. Cross-compile and bundle libcurl with a TLS library for iOS. That adds a C dependency to
   build, update for CVEs, and keep in the app size budget (N-03).

**Decided: URLSession if iOS has no libcurl, #90.** The Mac measures first.

Android needs no new code, only `HttpURLConnectionBackend` (sttp's default JVM backend is
`java.net.http`, which Android does not have) and the `INTERNET` permission. That is
unverified on a device until someone runs it.

## 16.5 SQLite: plain JDBC (decided)

The owner chose **straight JDBC** over a framework query layer. `java.sql` is the API, the
same on every platform, and Thicket supplies drivers where none exist.

| Runtime | `java.sql` | Driver |
|---|---|---|
| JVM (unit tests on Linux) | yes | `org.xerial:sqlite-jdbc` 3.53.4.0 |
| Android | **yes, in the platform** (`java.sql.Connection`, `ResultSetMetaData` and the rest are in `android.jar`) | **none maintained**: xerial's jar has no Android native libraries (it ships FreeBSD, Linux, Mac and Windows only), and SQLDroid's last release is 1.1.0-rc1 from 2018. **Thicket writes one: `thicket-sqlite-android`, a JDBC driver over `android.database.sqlite`** |
| Scala Native (GTK, macOS, iOS) | from `com.github.lolgab::scala-native-jdbc` 0.0.6 (2026-06) | `com.github.lolgab::scala-native-jdbc-sqlite` 0.0.6, which bundles the SQLite 3.53.2 amalgamation |

**Measured on Scala Native, 2026-10-21** (Scala 3.9.0, Scala Native 0.5.12, scala-cli on this
Linux box; a spike, not in the repo):
- **Works:** `DriverManager`, `PreparedStatement` with strings, doubles and `NULL`, Unicode
  text, `commit` and `rollback` (the rolled-back row is absent), `ResultSet` by name and by
  index. A `NOT NULL` violation through a `PreparedStatement` raises `SQLException`.
- **The driver must be registered by hand,** with
  `DriverManager.registerDriver(new SQLiteDriver())`: Scala Native has no `ServiceLoader`
  discovery, so `getConnection` otherwise fails with "No suitable driver".
- **Defect: a plain `Statement` swallows errors.** `executeUpdate` on invalid SQL, a syntax
  error or a constraint violation returns 0 (once 1) instead of throwing, and `executeQuery`
  on invalid SQL returns a result set. Prepared statements are fine. This needs fixing
  upstream before apps rely on it; until then, prepared statements only.
- **Subset:** no `ResultSetMetaData` or `DatabaseMetaData`, so libraries that introspect
  columns will not work on Native.
- **Size:** the spike binary is 5.26 MB against 3.79 MB for a file-I/O-only program, so the
  bundled SQLite adds about **1.47 MB** (default build mode, not `releaseFast` + LTO). The
  iOS hello-world is 5.75 MB against a 6 MB budget (N-03), so on Apple the driver should link
  the system `libsqlite3`, which iOS and macOS ship, instead of the amalgamation. To measure
  on the Mac. *Measured 2026-10-22 (#98), below: Apple links the system library.*

What Thicket provides, then, is small, and none of it is a query language:
- **`thicket-sqlite-android`**: the JDBC driver over `android.database.sqlite`, implementing
  at least the subset the Native driver does, so the same JDBC code runs everywhere.
- **`thicket-sqlite`**: `Sqlite.open(name): java.sql.Connection`. It registers the
  platform's driver and puts the file in the app's data directory (§16.3, app directories).
  On Native it may also carry the fix above until upstream has it.
- **One JDBC conformance suite**, shared, run against all three drivers: xerial on the JVM,
  the Native driver, and the Android driver on the emulator. It is what would have caught the
  swallowed errors, and it decides when "the same JDBC code runs everywhere" is true.
- **Tests need no fakes:** `jdbc:sqlite::memory:` works on every driver.

**Built, 2026-10-21** (`modules/sqlite`, `modules/sqlite-android`):
- `JdbcConformance` has 17 checks. xerial passes all 17 on the JVM. Thicket's Android driver
  passes all 17 on the `s2test` emulator. The Native driver fails exactly 5:
  - Three are one upstream bug. Errors from a plain `executeUpdate` (invalid SQL, a violated
    constraint) and from a bind to a parameter index that does not exist are not reported:
    `SQLiteOps` builds the `SQLException` and does not `throw` it. A failed prepare is not
    thrown either, but surfaces at the first `next()`, so that check passes.
  - Two are unimplemented (`???`): JDBC batches (`addBatch` / `executeBatch`) and
    `setFetchSize`.
- **The Android driver** implements the subset, plus batches, `getObject` and `setObject`; the
  rest of `java.sql` throws
  `SQLFeatureNotSupportedException`, generated from the JDK's signatures by
  `modules/sqlite-android/gen-unsupported.py`. Query parameters keep their types through a
  `CursorFactory`, since `rawQuery` binds strings only. Its jar is 101 KB.
- **Falsified:** with the Android driver made to swallow `executeUpdate` errors as the Native
  one does, the gallery's conformance check fails on "a constraint violated by a plain
  executeUpdate throws".

**On macOS and iOS (#98, 2026-10-22):** the same driver, over the system `libsqlite3`.
- **Size,** the iOS gallery in `releaseFast` + `LTO.full`, stripped, simulator arm64:
  6 985 272 B with no SQLite code reachable, **7 433 528 B** with the driver, conformance and
  Magnum checks over `libsqlite3`, **8 576 040 B** with the same over the amalgamation. The
  amalgamation alone is **1 142 512 B (1.09 MiB)**, 19 % of N-03's 6 MB, against nothing for
  a library the OS already has. So Apple apps exclude
  `scala-native-sqlite-amalgamation` and link `-lsqlite3` (`build.sbt`'s `appleSystemSqlite`;
  `modules/sqlite/README.md`). GTK keeps the amalgamation: a Linux distribution's SQLite is
  not guaranteed, and size is not budgeted there.
- **Conformance:** `sqliteNative/testOnly *` on macOS passes with either library (the 5 known
  failures, exactly); on the iOS simulator the gallery's self-test runs `JdbcConformance` and
  fails the same 5; Magnum passes 5 of 7 on both, as on GTK.
- **The gallery itself is over 6 MB** even with no SQLite (6.99 MB): it is the conformance
  surface, every widget and every check, not a shipping app. The budget question is the
  delta, and the hello-world figure above.

**Query libraries are the app's choice, and one exists on both runtimes.** The author of
the Native driver also publishes Scala Native builds of Magnum
(`com.github.lolgab::magnum` 1.3.1, the same version as the official
`com.augustnagro::magnum` 1.3.1 on the JVM) and of ScalaSql (`com.github.lolgab::scalasql`
0.1.15, well behind the official 0.3.x). Quill has none.

**Measured, 2026-10-21:** a scala-cli spike on Scala 3.9.0 and Scala Native 0.5.12, with
`scala-native-jdbc-sqlite` 0.0.6. These work:
- `sql"…"` with bound parameters;
- a `derives DbCodec` case class read back with `query[Meal]`;
- `transact`;
- `Repo.count` and `findById`.

It logs every query at TRACE by default; pass `SqlLogger.NoOp` (or your own) to a `Transactor`.
`Sqlite.dataSource(dirs, name)` gives Magnum the `javax.sql.DataSource` it takes, on every
platform.

**On the GTK gallery** (`MagnumChecks`, 7 checks; 2026-10-21): the Scala Native build passes
five. The two that fail are the Native driver's gaps above: `Repo.insertAll` (a JDBC batch)
and `query.iterator` (`setFetchSize`).

**On Android, the official Magnum 1.3.1 does not run** (measured on the `s2test` emulator,
2026-10-21).
- **The cause:** it creates a `System.Logger` in a top-level `private val` in `util.scala`,
  the file that also holds `connect` and `transact`, and Android has no `System.getLogger`
  (absent from `api-versions.xml`; ART's `core-oj.jar` has no such method).
- **The build:** R8 refuses the APK with "Missing class java.lang.System$Logger".
- **At run time:** with that warning suppressed, the first call fails with
  `NoSuchMethodError: No static method getLogger`, and every later one with
  `NoClassDefFoundError`.
- **The fix is one line.** Built from the v1.3.1 tag with that `val` made a `lazy val`, as the
  Native build already has it, and run with `SqlLogger.NoOp` so the logger is never created,
  all seven `MagnumChecks` pass on Android over Thicket's driver, batches and the iterator
  included.

So Magnum is a typed layer with the same API everywhere, once that line is changed upstream.
Until then the gallery runs `MagnumChecks` on Native only, and the Android build does not
depend on Magnum.

## 16.6 The shape of the API

- **Signals first, scoped to an `Owner`.** A sensor subscription is a resource: it starts
  when the signal is first observed inside an `Owner` and stops when that `Owner` is disposed,
  and while the app is in the background. A screen showing your position needs no stop call,
  and battery use follows what is on screen. This is how `Effect` already behaves.
- **ZIO in the bridge, not the core.** `signals` and `core` stay dependency-free (S5); the
  `effect-zio` module adds `ZStream` and `ZIO` forms (`Location.stream`, `Database.layer`). A
  cats-effect bridge would add the same.
- **Capabilities are values.** Every service answers `availability: Available | Unavailable |
  NeedsPermission`, so "no motion sensor on a Mac" is a branch in the app, not an exception.
- **Results on the UI thread.** Callbacks are delivered through `Host` / `UiThread`, as
  widget events are now.
- **Fakes for tests.** A `thicket.device.testing` package with scripted fakes (a location
  track, a motion trace, an in-memory database), as `TestRenderer` is for widgets.

### Where the code lives (decided: one module per service, per platform)

**One API module per service, and one implementation module per service and platform**, so
an app depends on exactly what it uses. On Android this decides which permissions end up in
the app: a library's manifest permissions are merged into the app's. The owner accepted that
this means many modules. Names follow the published ones:

| Service | API (cross: JVM, JS, Native) | Implementations |
|---|---|---|
| shared plumbing: `Availability`, `Permissions`, lifecycle, fakes | `thicket-device` | `thicket-device-android`, `-apple`, `-gtk` |
| HTTP | none: sttp is the API | `thicket-http-apple` (#90); Android and GTK use sttp's own backends |
| storage: directories, preferences | `thicket-storage` | `-android`, `-apple`, `-gtk` |
| secrets | `thicket-secrets` | `-android` (Keystore), `-apple` (Keychain), `-gtk` (libsecret) |
| SQLite | `thicket-sqlite` (`Sqlite.open`; `java.sql` is the API) | `thicket-sqlite-android` (a JDBC driver); Native uses `scala-native-jdbc-sqlite` |
| location | `thicket-location` | `-android`, `-apple`; GTK and macOS report `Unavailable` |
| motion | `thicket-motion` | `-android`, `-ios` |
| pickers, camera capture, share, clipboard, haptics | one API each | per platform, as built |

The implementations need the host (`ThicketActivity`, `GtkApp`, a Swift library), so each
platform module depends on its renderer. Each Apple module carries its own small Swift
library, like `renderer-apple` does since #41, so nothing links into an app that did not ask
for it.

## 16.7 What it costs on each host

- **Android:** `ThicketActivity` has to forward `onRequestPermissionsResult` and
  `onActivityResult` (camera, pickers) to whichever service asked. Permissions are declared in
  the app's manifest. Today that's the example's Gradle project; later `sbt-thicket` could
  check that a service's permission is declared.
- **Apple:** each service is shim functions plus a delegate object (`CLLocationManagerDelegate`,
  `PHPickerViewControllerDelegate`), all described in `tools/shim-gen`'s `Abi` and therefore
  checked. A missing `Info.plist` usage string makes iOS **terminate the app** when the
  permission is requested; `build-app.sh`, and later `sbt-thicket`, must write the strings
  for the services an app uses. All Apple work is the Mac's, as issues.
- **GTK:** file dialogs exist already in GTK 4.10+ (`GtkFileDialog`). Location and motion go
  over D-Bus through GIO, both optional at run time: absent service, `Unavailable`.

## 16.8 How each is verified, not asserted

Every service gets a gallery section and self-test checks on every host where it is
`Available`, the extension of the gallery rule (`docs/12` §12.10), with injected inputs:

| Service | Android emulator | iOS simulator | GTK / macOS |
|---|---|---|---|
| Location | `adb emu geo fix <lon> <lat>` | `xcrun simctl location <udid> set <lat>,<lon>` | GeoClue absent on CI: check it reports `Unavailable` |
| Motion | `adb emu sensor set acceleration x:y:z` | no simulator motion: unit tests with fakes | `Unavailable` |
| Files, prefs, secrets, SQLite | write, restart the app, read back | same | same |
| HTTP | a local test server (`adb reverse` for the emulator) | same | same |
| Pickers, camera, share | system UI cannot be driven from the app: unit tests with fakes, plus one manual screenshot per platform, recorded | | |

## 16.9 Decisions (owner, 2026-10-21)

1. **Order:** the iOS HTTP transport first (#90, Mac).
2. **Packaging:** separate modules, one per service and platform (§16.6).
3. **Android location:** the framework's `LocationManager`. No Play Services.
4. **iOS HTTP:** URLSession, if iOS has no libcurl. The Mac measures first (#90).
5. **SQLite: plain JDBC** (§16.5). Quill was asked about first and does not fit: ProtoQuill
   (`quill-sql_3` 4.8.6) has no Scala Native artefact, and its JVM runtime is heavy for an
   APK (Typesafe Config, Caffeine, JNA, jline, the Scala 2.13 compiler). A framework query
   DSL was rejected by the owner.
6. **Secure storage on GTK:** libsecret.
7. **Desktop hardware:** location, motion and camera report `Unavailable` on GTK and macOS.

### The questions as they were asked

1. **Scope and order:** tier 1 before tier 2, with the iOS HTTP transport first because
   phase 6 needs it on iOS. Or the hardware first, because it is what you raised?
2. **Packaging:** a `device` API module plus per-host implementation modules (recommended,
   §16.6), or implementations inside the renderer modules?
3. **Android location: the framework's `LocationManager`, or Google Play Services' fused
   provider?** Fused is more accurate and uses less battery, but it is a proprietary
   dependency that not every device has (S-05: no runtime commercial dependency). The project
   already avoided AndroidX for Fragments. Recommended: `LocationManager`, with fused as an
   app-level option later.
4. **iOS HTTP: a `URLSession` sttp backend in the shim (recommended) or bundled libcurl?**
5. **SQLite API level:** a thin SQL API (recommended), or also a typed query layer?
6. **Secure storage on GTK:** depend on libsecret, or an encrypted file, or `Unavailable`?
7. **Desktop:** treat location, motion and camera as `Unavailable` on GTK and macOS at first
   (recommended), or do the D-Bus portals and macOS CoreLocation in the same pass?

## 16.10 Order of work (once decided)

1. **HTTP transport:** Android `HttpURLConnectionBackend` verified on the emulator against a
   local server; the iOS `URLSession` backend (Mac issue); GTK/macOS `CurlBackend` verified.
   Unblocks phase 6.
2. **Storage:** app directories, preferences, secrets; SQLite as the Android JDBC driver,
   `Sqlite.open`, the conformance suite, and the upstream fix for the Native driver's
   swallowed errors. *Done on GTK and Android, 2026-10-21, and on macOS and iOS, 2026-10-22
   (#98), except secrets and the upstream fix.*
3. **Lifecycle and permissions:** the host plumbing every later service sits on.
4. **Location,** then **motion**, each with emulator-injected self-tests.
5. **Pickers, camera capture, share, clipboard, haptics.**
6. **Tier 3** as separate designs, live camera preview first if wanted.
