# 16 — Device services: a plan for review

**Status (2026-10-22): decided; storage and SQLite built on GTK, Android, macOS and iOS, and the
iOS HTTP transport built (#90, §16.4).** The owner's answers are in §16.9. Built: app
directories, preferences and SQLite through JDBC (§16.5, §16.6), measured on GTK, the Android
emulator, macOS and the iOS simulator (Apple: #98). Covers location, motion, camera, files,
storage and the rest:
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
| HTTP | sttp client4 | 4.1.0, `HttpURLConnectionBackend` works on Android, with two gaps Thicket wraps | 4.1.0, **only `CurlBackend`** (synchronous), which Thicket replaces | See §16.4: iOS has no libcurl, and `CurlBackend` fails half the checks |
| GraphQL | caliban-client | 3.2.0 | 3.2.0 | Phase 6's meal-planner client; rides on sttp |
| JSON | zio-json / upickle | 1.1.1 / 4.4.3 | same | |
| HTTP, ZIO | zio-http | yes | **no Native artefact** | Not usable as the client on GTK/Apple |
| SQLite, through JDBC | `java.sql` | in the platform; **no maintained driver** | `scala-native-jdbc(-sqlite)` 0.0.7 | See §16.5. (`sqlite4s` stops at SN 0.4) |
| Time | `java.time` | yes | scala-java-time 2.8.0 | |

## 16.3 The likely list, tiered

**Tier 1: a real app needs these before it needs sensors.** Phase 6, the meal-planner, needs
most of them, which is why they come first.

| Service | Shape (proposal) | Android | iOS | macOS | GTK |
|---|---|---|---|---|---|
| **HTTP transport** | an sttp `Backend` per platform; the app keeps sttp/caliban | **built**: `AndroidHttpBackend` in `thicket-http-android`, over `HttpURLConnectionBackend`; `INTERNET` permission | **built** (#90): `URLSessionBackend` in `thicket-http-apple` | `URLSessionBackend` (sttp's `CurlBackend` links, but has the defects in §16.4) | **built**: `LibcurlBackend` in `thicket-http-gtk` |
| **App directories** | `Storage.dirs: AppDirs(data, cache, temp)` as `java.nio.file.Path` | `getFilesDir`, `getCacheDir` | Application Support, Caches, the temporary directory, each under the bundle id (built, #98) | same (built, #98) | `$XDG_DATA_HOME/<app>`, `$XDG_CACHE_HOME/<app>` |
| **Preferences** (small key–value) | `Preferences`: get/set strings, numbers, booleans | `SharedPreferences` | ~~`UserDefaults`~~ a file under the data dir, as on GTK (built, #98; `docs/decisions.md` 2026-10-22) | ~~`UserDefaults`~~ the same | a file under the data dir (GSettings needs an installed schema) |
| **Secure storage** (tokens) | `Secrets`: get/set/delete a string | Android Keystore-wrapped value | Keychain, the app's own (built, #108; the host needs keychain entitlements, on the simulator too) | Keychain (built, #108): the data-protection keychain when entitled, else the login keychain, whose items an unsigned build cannot read after a rebuild | libsecret (Secret Service), optional dependency |
| **SQLite** | plain JDBC; `Sqlite.open(name): java.sql.Connection` | Thicket's JDBC driver over `android.database.sqlite` | `scala-native-jdbc-sqlite` | same | same |
| **Lifecycle** | `AppLifecycle.state: Signal[Foreground \| Background]` and an `onTerminate` hook | **built**: `ThicketActivity`'s `onStart`/`onStop`, a rotation not counted; terminate when the last one finishes | scene notifications | `NSApplication` notifications | **built**: the window shown — mapped, not minimised or suspended — not ~~`is-active`~~, which is focus; `GApplication` shutdown |
| **Permissions** | `Permissions.request(Permission.Location)` → `Granted \| Denied \| Restricted` | **built**: `requestPermissions` + manifest; an undeclared one throws | usage strings in `Info.plist` | entitlements (sandboxed apps) | **built**: always `Granted`; xdg-desktop-portal asks for location and camera when they are used |
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

**Measured (2026-10-22, Xcode 27.0, SDKs 27.0) and built (#90).**

| SDK | libcurl | How |
|---|---|---|
| iOS simulator | **no**: no `libcurl*.tbd` in `usr/lib`, no `curl/` headers, nothing named `*curl*` anywhere in the SDK, nor in the iOS 27.0 simulator runtime | `find -L <sdk> -iname '*curl*'`; `clang -lcurl` → `library 'curl' not found` |
| iOS device | **no**, the same | the same |
| macOS | **yes**: `libcurl.tbd` (→ `/usr/lib/libcurl.4.dylib`, curl 8.7.1, SecureTransport) and headers | a C program linked with `-lcurl` printed `libcurl/8.7.1`; sttp's `CurlBackend` did a GET and a POST against a local server (both 200) |

So option 1 was built: **`modules/http-apple` (`thicket-http-apple`)**, an sttp `SyncBackend`
(`URLSessionBackend()`, the same type as `CurlBackend()`) over a small Swift library of its
own, carried in its jar and kept out of `renderer-apple`. Its C ABI (`thk_http_*`) is
described in `tools/shim-gen` and pull-only: Scala blocks in a `@blocking` wait and reads the
result, and nothing calls into Scala from `URLSession`'s queue. On the iOS simulator it passes
13 checks against a local server — GET, POST, 404, repeated headers, redirect, a timeout, a
cancellation, connect refused, the GC not waiting for a parked request, one caliban-client
query — each check seen to fail with its feature broken. Rules and differences from
`CurlBackend` are in `modules/http-apple/README.md`; the numbers are in the decision log.

Two findings on the way:
- **sttp-model needs libidn2 on Scala Native.** Every host that is not an IPv6 literal goes
  through `idn2_to_ascii_8z` (`@link("idn2")`), and neither Apple SDK has libidn2. The iOS
  link failed on it; `thicket-http-apple` now supplies the three functions. On macOS the link
  picks up whatever libidn2 it finds (Homebrew's here), so **`CurlBackend` on macOS also
  needs libidn2 installed**, which a stock Mac does not have.
- **App Transport Security:** plain `http://` to anything but loopback needs an
  `NSAppTransportSecurity` exception in the app's `Info.plist`. The self-test reached
  `127.0.0.1` and `localhost` without one.

Android needs no new code, only `HttpURLConnectionBackend` (sttp's default JVM backend is
`java.net.http`, which Android does not have) and the `INTERNET` permission. That is
unverified on a device until someone runs it. *Superseded below: it needed a little.*

**Measured on Linux and Android (2026-10-27).** The Apple self-test's checks became
`examples/http`'s, shared by every platform, plus one for a connection reset. Run against
sttp 4.1.0's own backends:

| Check | `CurlBackend`, Linux | `HttpURLConnectionBackend`, Android |
|---|---|---|
| a binary request body (256 bytes, NUL first) | **times out**. sttp's source passes the bytes through `new String(bytes)` and a C string, which mangles any byte that is not UTF-8 and ends at a NUL; how that becomes a timeout is not traced | ok |
| a request header given twice | ok | **only the second is sent**: each header is set with `setRequestProperty`, which replaces |
| timeout, refused, reset | **plain `RuntimeException("Command failed with status …")`**, not sttp's `TimeoutException`, `ConnectException`, `ReadException` | ok |
| cancellation by `Thread.interrupt()` | **none**: the request runs its full 4 s | **none**, the same |
| a `System.gc()` while another thread waits 2 s for a response | **1712 ms**: `curl_easy_perform` is not `@blocking`, so a collection waits for the request, and every other thread, the UI's included, waits for the collection (not measured in an app) | 20 ms |
| GET, 404, redirect, idn2 host names, caliban-client | ok | ok |
| total | 6 of 12 | 10 of 12, and a request from the main thread is refused (`NetworkOnMainThreadException`) |

So, the owner decided (2026-10-27), Thicket has its own backend on Linux and wraps sttp's on
Android:
- **`thicket-http-gtk`: `LibcurlBackend`.** libcurl's multi interface, loaded with `dlopen`
  (`libcurl.so.4`), so neither the build nor the app needs the `-dev` package. The body is
  sized and copied by curl (binary-safe); curl writes the response into `open_memstream`
  buffers, so no Scala runs inside curl and its waits run with the thread Unmanaged; it waits
  in 50 ms slices, checking for an interrupt; errors are sttp's. `curl_easy_setopt`, variadic, goes
  through a few lines of C in the jar. **12 of 12**; a collection meanwhile takes 1 ms.
- **`thicket-http-android`: `AndroidHttpBackend`.** sttp's backend, with a repeated header
  added back by `addRequestProperty` once sttp has set its own, and the exchange on a worker
  so that an interrupted caller returns at once and disconnects the connection, which on
  Android closes the socket (the server sees the hang-up). It refuses the main thread itself,
  since the worker would otherwise hide Android's refusal. Plain JVM: its tests run on the
  desktop JDK. **12 of 12**, and the main-thread refusal.
- An Android app also needs, for plain `http://`, a network security config allowing cleartext
  to that host (refused since API 28), and with R8,
  `-dontwarn java.net.http.HttpTimeoutException`: sttp's exception mapping names the class,
  which Android lacks. The mapping still works there — a reset is a `ReadException`.

Each fix was broken and its check seen to fail: on Linux, no Unmanaged wait (the collection
took 1710 ms), no body size, no interrupt check; on Android, no re-added header, no disconnect
(the server saw no hang-up), no main-thread refusal. The upstream reports for sttp are drafted,
for `CurlBackend` and for the repeated header.

## 16.5 SQLite: plain JDBC (decided)

The owner chose **straight JDBC** over a framework query layer. `java.sql` is the API, the
same on every platform, and Thicket supplies drivers where none exist.

| Runtime | `java.sql` | Driver |
|---|---|---|
| JVM (unit tests on Linux) | yes | `org.xerial:sqlite-jdbc` 3.53.4.0 |
| Android | **yes, in the platform** (`java.sql.Connection`, `ResultSetMetaData` and the rest are in `android.jar`) | **none maintained**: xerial's jar has no Android native libraries (it ships FreeBSD, Linux, Mac and Windows only), and SQLDroid's last release is 1.1.0-rc1 from 2018. **Thicket writes one: `thicket-sqlite-android`, a JDBC driver over `android.database.sqlite`** |
| Scala Native (GTK, macOS, iOS) | from `com.github.lolgab::scala-native-jdbc` 0.0.7 (2026-10) | `com.github.lolgab::scala-native-jdbc-sqlite` 0.0.7, which bundles the SQLite 3.54.0 amalgamation (0.0.6: 3.53.2) |

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
  upstream before apps rely on it; until then, prepared statements only. *Fixed upstream in
  0.0.7 (lolgab/scala-native-jdbc#17), 2026-10-22: see "Built" below.*
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
- **0.0.7, 2026-10-22:** the fix for the three (lolgab/scala-native-jdbc#17, from this
  project) is released. The Native driver now fails exactly the 2 unimplemented checks;
  `sbt --error "sqliteNative/testOnly *"` with the known set still at 5 fails on precisely
  the three, now passing.
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
| shared plumbing: `Availability`, `Permissions`, lifecycle, fakes | `thicket-device` (JVM, Native; `Availability` added with location) | `thicket-device-android`, `-gtk` (built); `-apple` (Mac issue) |
| HTTP | none: sttp is the API | `thicket-http-apple` (#90), `thicket-http-android`, `thicket-http-gtk` |
| storage: directories, preferences | `thicket-storage` | `-android`, `-apple`, `-gtk` |
| secrets | `thicket-secrets` | `-android` (Keystore), `-apple` (Keychain), `-gtk` (libsecret) |
| SQLite | `thicket-sqlite` (`Sqlite.open`; `java.sql` is the API) | `thicket-sqlite-android` (a JDBC driver); Native uses `scala-native-jdbc-sqlite` |
| location | `thicket-location` (built) | `-android` (built), `-apple` (Mac issue); GTK and macOS use `Location.unavailable` |
| motion | `thicket-motion` (built) | `-android` (built), `-ios` (Mac issue); GTK and macOS use `Motion.unavailable` |
| clipboard | `thicket-clipboard` (built) | `-gtk`, `-android` (built); `-apple` (Mac issue) |
| haptics | `thicket-haptics` (built) | `-android` (built), `-apple` (Mac issue); GTK uses `Haptics.none` |
| pickers: photos, documents | `thicket-pickers` (built; `Documents`, not `Files`) | `-gtk`, `-android` (built); `-apple` (Mac issue) |
| camera capture, share | one API each | per platform, as built |

The implementations need the host (`ThicketActivity`, `GtkApp`, a Swift library), so each
platform module depends on its renderer. Each Apple module carries its own small Swift
library, like `renderer-apple` does since #41, so nothing links into an app that did not ask
for it. (As built, storage and secrets need none: `thicket-storage-apple` is plain Scala Native,
and `thicket-secrets-apple` is Scala Native over a C helper compiled by Scala Native's clang.)

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
   Unblocks phase 6. *Done on iOS and macOS (#90), and on GTK and Android, 2026-10-27, with
   Thicket's own backends (§16.4): the same checks, `examples/http`, on every platform.*
2. **Storage:** app directories, preferences, secrets; SQLite as the Android JDBC driver,
   `Sqlite.open`, the conformance suite, and the upstream fix for the Native driver's
   swallowed errors. *Done on GTK and Android, 2026-10-21, and on macOS and iOS, 2026-10-22
   (#98); the upstream fix is in `scala-native-jdbc-sqlite` 0.0.7, 2026-10-22.* *Secrets done
   on GTK and Android, 2026-10-23 (`modules/secrets/README.md`):*
   - GTK uses the Secret Service through libsecret, loaded with `dlopen`, so it is the
     optional dependency the table in §16.3 asks for: without it, `open` returns why.
   - Android uses AES-256-GCM under an Android Keystore key, with each ciphertext bound to
     its key.
   - Both pass the same 11 `SecretsConformance` checks. In the galleries, a secret stored on
     one run is read back on the next.
   - CI starts a keyring of its own (`bin/with-keyring.sh`), so the Secret Service is tested
     there, not skipped.
   - The Keychain half is done too, 2026-10-27 (#108, `thicket-secrets-apple`): a C helper
     that Scala Native compiles, no Swift. The iOS simulator uses the app's data-protection
     keychain. An unsigned Mac binary uses the login keychain, bound to the build that made
     each item. Conformance passes 11/11 on both, and the gallery's secret goes from stored
     to kept.
3. **Lifecycle and permissions:** the host plumbing every later service sits on. *Done on GTK
   and Android, 2026-10-28 (`modules/device/README.md`); Apple is a Mac issue.*
   - `ThicketActivity` and `GtkApp` tell listeners what they hear (start, stop, destruction
     and permission results; shown or hidden, and shutdown). `thicket-device-android` and
     `-gtk` turn that into `AppLifecycle` and `Permissions`, so an app forwards nothing.
   - GTK's foreground is the window being on screen, not its focus. On GNOME 50 (Wayland),
     minimising reaches the background after 3.3 s, when mutter reports the window suspended.
   - The gallery's Device section is driven through the real hosts. On GTK: hiding and showing
     the window, and shutdown. On Android: Home and back, "Don't allow" then "Allow" in the
     system's dialog (`build.sh` taps them), an undeclared permission, and Back.
4. **Location,** then **motion**, each with emulator-injected self-tests. *Location done on
   Android, and `Unavailable` on GTK, 2026-10-30 (`modules/location/README.md`); motion the
   same day (`modules/motion/README.md`). Apple's halves are Mac issues.*
   - Both use `thicket-device`'s `WhileInFront`: start while the owner lives and the app is in
     front, stop in the background, try again on each return.
   - `Location(provider, lifecycle)` is shared: a platform supplies a `LocationProvider`, and
     `updates` listens only while its owner lives and the app is in front, picking up the
     platform's last known position on each return.
   - The Android gallery moves the emulator with `adb emu geo fix` and counts Thicket's
     listeners through a wrapping provider: Android throttles background location by itself,
     so a position that stops changing in the background proves nothing (measured: with the
     background stop removed, the on-screen check still passed and the count failed).
   - Motion: `acceleration`, `rotationRate` and `attitude`, each a `Signal` at a `Rate`. The
     emulator's accelerometer and gyroscope are set with `adb emu sensor set`; the attitude is
     Android's fusion of them and is only checked to arrive. `Attitude` is its own type
     (roll, pitch, yaw), not the `Vec3` §16.3's table shows.
5. **Pickers, camera capture, share, clipboard, haptics.** *Clipboard (GTK, Android) and
   haptics (Android; GTK has none) done 2026-10-30 (`modules/clipboard/README.md`,
   `modules/haptics/README.md`); Apple is a Mac issue. The owner decided the pickers' shape
   the same day: a picked file is copied into the app's cache and returned as a `Path` on
   every platform, and results arrive by UI-thread callback, as every service's do.*
   - The clipboard's text left the app: `wl-paste` read the GTK gallery's copy from another
     process, and a paste read text a previous run had left. The haptic tap is in Android's
     vibrator history, which `build.sh` checks.
   - *Pickers done on GTK and Android, 2026-10-30 (`modules/pickers/README.md`):* `Photos.pick`
     and `Documents.open`/`save`, driven in the real system UI. `build.sh` taps the Android
     photo picker and documents UI; the GTK self-test answers the file chooser in-process
     (`GDK_DEBUG=no-portals`). Share and camera capture are next.
6. **Tier 3** as separate designs, live camera preview first if wanted.
