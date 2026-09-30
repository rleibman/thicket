# Decisions (binding for phase 0)

These are the choices an agent would otherwise make arbitrarily. Change them by
adding a dated entry to the Decision log at the bottom, not by editing the table.

## Build, language, versions

| Topic        | Decision                                                                                                                                                                                                                                                      | Notes                                                              |
|--------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| **Version policy** | **Always target the latest stable Scala and the latest stable sbt.** Not the LTS line, not a pinned older release: newest stable. Re-resolve at the start of every spike/milestone with the commands below and record what you used in `REPORT.md`. If a dependency blocks the newest version, that is a finding to report, not a reason to silently pin. | User directive, 2026-09-18. |
| Build tool | **sbt 2.0.9** (`sbt.version=2.0.9`). Verified working for a JVM/JS/Native crossproject in S5. mill/scala-cli support is a later tooling task (P-06). | See sbt-2 notes below. |
| Scala | **3.9.0** (latest stable; 3.10.0 is RC). Verified on all three backends in S5. Re-check: `cs complete-dep org.scala-lang:scala3-compiler_3:` | |
| Scala Native | **0.5.12**, plugin `org.scala-native:sbt-scala-native:0.5.12`. Publishes `nscplugin_3.9.0`, so it supports Scala 3.9. Re-check compatibility with `cs complete-dep org.scala-native:nscplugin_<scalaVersion>:` before bumping Scala. | Scala Native's compiler plugin is published per *exact* Scala version — this is the usual thing that blocks a Scala upgrade. |
| Scala.js | **1.22.0**. Scala 3 has Scala.js support in the compiler itself (no separate compiler plugin), so it does not constrain the Scala version. | |
| sbt-crossproject | 1.4.0 (`org.portable-scala`, both `sbt-scalajs-crossproject` and `sbt-scala-native-crossproject`). | |
| ZIO | **2.1.26** (`dev.zio::zio`, `zio-streams`); `_native0.5_3` artefacts confirmed. **Requires `io.github.cquiroz::scala-java-time` 2.7.0 on Scala Native** — `zio.Duration` *is* `java.time.Duration` and Scala Native has no `java.time`, so nothing links without it (S8). | S8, M0. |
| munit | **1.3.6**; `munit-scalacheck` is versioned separately at **1.3.1**. | All spikes. |
| scalafmt | 3.11.5 (`.scalafmt.conf` in `spikes/_template/`). | |
| JDK | JDK 21 LTS for Android work. The Linux box has **OpenJDK 23-ea** on `PATH`; S5 ran fine on it, but AGP requires a non-EA JDK ≥ 17 — install 21 via `cs java --jvm temurin:21` for S2. | |
| Android | min SDK 26, compile/target SDK **36**, AGP **9.4.1**, Gradle **9.7.1**, build-tools 37.0.0. SDK installed at `~/Android/Sdk` (2026-09-18) with an `android-36 google_apis x86_64` image; AVD `s2test`. **Three mandatory settings for Scala** — `android.enableR8.fullMode=false`, the `*$lzy*` keep rule, and excluding `**/*.tasty` from packaging (S2). | Note `platforms;android-37` is listed by sdkmanager but is not installable. |
| iOS/macOS | Xcode latest stable; deployment target iOS 17 / macOS 13; Swift 5 language mode in the shim. **Three mandatory settings (S1, S8):** (1) linktime property `scala.scalanative.meta.linktimeinfo.target.os = "darwin"`, or `java.lang.Thread` does not link (javalib's `isMac` does not recognise the iOS triple); (2) **immix is the only working GC** — commix and boehm do not compile for iOS; (3) shipping config is `releaseFast` + `LTO.full` + `-lc++` at the final link, which is the difference between 7.73 MB (over N-03) and 5.75 MB (under). App templates must be **`UIScene`-based**: iOS 27 traps at launch on the old `UIApplicationDelegate` lifecycle (S3). | Mirrors the Android "three mandatory settings" row. |
| C toolchain | clang (present: 21 on Linux; Apple clang on Mac). Scala Native on Linux needs `zlib1g-dev` and **`libunwind-dev`** (installed 2026-09-18). | |

### sbt 2 notes (from S5 — read before starting a build)

- **Plugin suffix is `_sbt2_3`**, not `_3_2.0`. All four plugins this project needs exist:
  `org.scala-js:sbt-scalajs`, `org.scala-native:sbt-scala-native`,
  `org.portable-scala:sbt-scalajs-crossproject`, `org.portable-scala:sbt-scala-native-crossproject`.
- **`%%%` does not exist.** There is no `sbt-platform-deps` for sbt 2. Write cross-platform
  dependencies per platform with explicit artefact suffixes, e.g.
  `"org.scalameta" %% "munit" % v` (JVM), `"org.scalameta" % "munit_sjs1_3" % v` (JS),
  `"org.scalameta" % "munit_native0.5_3" % v` (Native). See `spikes/s5-signals/build.sbt`.
- **`sbt test` is incremental** and runs *nothing* when sources are unchanged — which reads as a
  pass. Use **`testOnly *`** in briefs, scripts and CI.
- **`-Xfatal-warnings` is deprecated** in Scala 3.9: use `-Werror`.
- **Minimum `-java-output-version` for Scala 3.9 is 17** (S2). 8/9/11/16 are rejected.
- **`unmanagedJars` cannot take a `File`** (classpaths are `HashedVirtualFileRef`); use the
  `lib/` convention. Built artifacts are symlinks into `~/.cache/sbt/v2/cas` — `readlink -f` them.
- Scala.js `runMain` is not available; set `Compile / mainClass` and
  `scalaJSUseMainModuleInitializer := true`, then use `run`.


## Naming and layout

| Topic               | Decision                                                                                                                                                                                                                                                     |
|---------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Working name        | `thicket` (repo), root package **`thicket`**. Renaming is a product question (Q14) — do not bikeshed.                                                                                                                                                       |
| Licence             | Apache-2.0. Add `LICENSE` at M0, not during spikes.                                                                                                                                                                                                          |
| Repo layout         | `docs/` plan · `spikes/sN-<slug>/` one self-contained sbt (or Gradle/Xcode) project per spike, each with `BRIEF.md` and `REPORT.md` · `modules/` framework code, **empty until M0** · `shims/` Swift/C++ shims (M1+) · `tooling/` plugins/CLI (M5).          |
| Spike independence  | Spikes never depend on each other's code. Copying a file between spikes is allowed; importing a spike as a dependency is not.                                                                                                                                |
| Module naming (M0+) | `thicket-core`, `thicket-signals`, `thicket-renderer-api`, `thicket-effect-api`, `thicket-effect-zio`, `thicket-renderer-<platform>`, `thicket-layout-yoga`. Group id decided at M0.                                                                  |
| Code style | Scala 3 syntax, **braces — not significant indentation, and not the "fewer braces" colon form**. Every build sets `-no-indent`, which makes the compiler reject both rather than merely permit braces. `given`/`using`, `enum`, no `implicit` keyword, no `null`, no exceptions for control flow in library code. One `.scalafmt.conf` at the repo root (`maxColumn = 120`), shared by every module and spike. |
| Tests               | munit (cross-published for all three backends) + munit-scalacheck for property tests.                                                                                                                                                                        |

## Execution order for phase 0

| Order | Spike                      | Machine                   | Parallel with |
|-------|----------------------------|---------------------------|---------------|
| 1     | **S5 signals**             | Linux                     | S2            |
| 1     | **S2 Android**             | Linux (after SDK install) | S5            |
| 2     | **S1 Scala Native on iOS** | Mac                       | S4            |
| 2     | S4 Yoga                    | Linux                     | S1            |
| 3     | S3 Swift shim              | Mac (after S1 pass)       | S7            |
| 3     | S7 GTK4                    | Linux                     | S3            |
| 4     | S8 ZIO on iOS              | Mac (after S1 pass)       | —             |
| any   | S6 calibration             | Mac                       | optional      |

Go decision (see `08` §8.1) is made after S1, S2, S3 have reports.

## Decision log

- 2026-09-30 — **The Apple self-test finds widgets by *platform class*, read back from the
  shim, not by the renderer's own bookkeeping** (#4). Four inspection functions were added
  for it — `sui_class_name`, `sui_get_progress`, `sui_get_value`, `sui_is_secure` — so a
  renderer that asked for the right kind and got the wrong control fails the check. It also
  sidesteps the trap Android hit (a slider matching "the determinate progress bar", since
  `SeekBar` extends `ProgressBar`): `NSSlider` and `NSProgressIndicator` are different
  classes by name. One consequence worth knowing: on UIKit the draft form's `Checkbox` is a
  `UISwitch` too, so the Toggle check finds *the settings row's* switch rather than counting
  switches. Measured: **43/43** on macOS, **42/42** on the iOS simulator (one overflow check
  is not exercised on a phone this wide, and says so), progress bar **0.750** on both — the
  same figure GTK and Android report. Control: clamping `sui_set_progress` to full made the
  progress check fail with 1.000 against 0.750.
- 2026-09-30 — **UIKit has no indeterminate progress bar, and `ProgressBar(None)` is not
  faked there.** `UIProgressView` only draws a fraction. The shim records the state, so
  `sui_get_progress` reports it as indeterminate, and draws an empty bar. Substituting an
  activity indicator would change the widget's shape and size under the app. On AppKit
  `NSProgressIndicator` does both, so `None` animates there.

- 2026-09-29 — **`thicket_apple.h` and `Shim.scala` are generated artefacts, checked in**
  (GitHub #3). Both are now written by `sbt shimGen/run` from
  `tools/shim-gen/.../Abi.scala` and carry a "GENERATED — edit Abi.scala" header;
  `GenerateSpec` compares them to the generator's output byte for byte, so `sbt test` fails on
  a hand edit or a missed regeneration (`sbt "shimGen/run --check"` is the same check as an
  exit code). They stay checked in so that building `rendererApple` needs nothing but the
  repository. Also decided:
  (a) **Per-platform widget choice lives in `Abi.kinds`**: each `sui_create` code names the
  Swift construction each toolkit uses (`NSButton(checkboxWithTitle:` vs `UISwitch(`), and
  `ConsistencySpec` reads both shims' `sui_create` to check it — and that no shim handles a code
  the table does not declare. The Scala codes are generated as `ShimKind` next to the externs.
  (b) **Swift bodies stay hand-written**, and so do the two `@_cdecl` signatures per function:
  per function that is 1 description entry + 2 checked Swift signatures, down from 4 unchecked
  declarations. Generating Swift *files* would mean generating bodies, which the phase-1
  measurement (14 of 34 genuinely different) rules out.
  (c) The generated Scala binds `const uint8_t *` as `Ptr[Byte]`, not `Ptr[UByte]` — the
  renderer allocates the buffer as `Byte`, and the two are the same register (`Abi.Repr`).
- 2026-09-30 — **A test's `UiThread` must actually marshal; `install(f => f())` is the wrong
  stub.** The effect-zio suite installed a UiThread that runs the post *inline on the ZIO
  fibre's thread*, so `Var.set` happened off the test thread while the test spun on
  `signal.now`. The signal graph is single-threaded by construction — plain `var`s, no
  barriers, which is precisely what `ThreadGuard` enforces — so that spin was racing on
  memory visibility and lost about half the time. It surfaced as one flaky test,
  "RemoteScreen: a failure renders the error branch", which was blamed twice on fibre
  scheduling and was not that. `TestUiThread` queues posts and drains them on the waiting
  thread, which is what GTK's main loop and Android's looper do, so the tests now exercise
  the marshalling instead of skipping it. Eight consecutive clean runs against ~50% failure
  before.
  Two lessons worth keeping: a stub that is *simpler* than the real thing can hide the
  behaviour under test, and "flaky" is a hypothesis, not a diagnosis.

- 2026-09-30 — **`AppRoot.actions` had been in the contract since navigation landed and no
  host ever read it.** `Screen.actions` → `NavHost.actions` → `AppRoot.actions` was wired
  end to end, and both hosts dropped it on the floor: an API the docs described and nothing
  honoured, which is worse than a missing feature because it reads as done. Now rendered as
  native chrome — `gtk_header_bar_pack_end` on GTK, the action bar's options menu on
  Android, via `invalidateOptionsMenu` because Android owns that lifecycle rather than
  letting a caller push items in. Android uppercases the labels itself, which is the point
  of keeping chrome out of the element tree.
  `Action` also changed shape to `Action(label, enabled)(body)` with a by-name body,
  matching `Button` and `AlertAction`; it was the only callback in the DSL that still took
  an explicit `() => Unit`, and the moment to fix that was while nothing rendered it.
  GTK packs in reverse because `pack_end` puts each new button nearest the window controls.

- 2026-09-29 — **Some widgets are *presented*, not inserted, and the contract now says so.**
  `WidgetKind.presented` plus `Renderer.present` / `Renderer.dismiss`. An alert is not a
  child of anything on any of the four toolkits, and GTK makes that a type error —
  `GtkAlertDialog` is a `GObject`, not a `GtkWidget`. Routing it through `insertAfter`
  would have meant the same special case in three methods of four renderers. Mounting
  presents and unmounting dismisses, so `Show(confirming)(Alert(...))` is the whole API and
  there is no `show()` — the same choice `ActivityIndicator` made. `Sheet` and `Menu` will
  follow the same path.
- 2026-09-29 — **An alert states button *roles*; each platform decides position and
  meaning.** GTK takes an ordered array and answers with an index; Android has three fixed
  slots placed by its own convention, so the declared order is not the shown order there.
  And `OnDismiss` means the *platform* closed it — which Android reports natively and GTK
  does not, because declaring a cancel button makes Escape activate that button instead.
  Both behaviours are the platform's own; the framework exposes the distinction rather than
  flattening it, since an app that conflates "declined" with "looked away" will eventually
  be wrong.
- 2026-09-29 — **Android's platform SDK is drifting away from Android's idioms, and this
  renderer stays on the platform.** `android.app.AlertDialog` and `android.widget.TabHost`
  both carry class-level deprecation flags in android-36; the replacements live in AndroidX
  and Material, not in the platform. The Alert uses the platform class anyway: scalac does
  not even warn on it, it still draws correctly, and the reason this APK is 165 KB is that
  it depends on nothing. `TabView` is a harder case and is not built — see §12.2a, this is
  the same question `Radio` and `Stepper` raised.

- 2026-09-29 — **`ContentFit.Cover` is drawn by hand on AppKit, and rendering is verified by
  measuring pixels rather than asserting on the property that was set** (Forgejo #6).
  `NSImageView.imageScaling` has no mode that crops: `.scaleProportionallyUpOrDown` is
  Contain, `.scaleAxesIndependently` is Fill, and Cover had been mapped to the latter — so
  Cover distorted, which is the one thing it must not do. It is now an `NSImageView` subclass
  that draws the image into an aspect-fill rect under a clip; a layer's `contentsGravity`
  would fight the view, which draws its own image. UIKit needed no change: `.scaleAspectFill`
  is exactly Cover, **confirmed by measurement, not assumed**.
  The verification is the point worth keeping: a 400x100 image with a centred circular marker
  is rendered into a 200x200 frame through the real `@_cdecl` entry points, and the marker's
  bounding box is measured. Contain 1.00, Cover 1.00, Fill 0.25 — and a distorting Cover
  reads 0.25, indistinguishable from Fill, which is how the defect stayed invisible in logs.
  Harness: `modules/renderer-apple/shim/run-fit-harness.sh` (AppKit natively, UIKit headless
  under `simctl spawn`, no app bundle). Picture: `docs/screenshots/content-fit-appkit.png`.
- 2026-09-29 — **Coverage is measured over the JVM-tested modules only, and the figure is
  reported as such.** 91.10% statement / 87.64% branch via sbt-scoverage 2.4.4, which does
  publish for sbt 2 (`_sbt2_3`); Scala 3 needs no compiler plugin because coverage is built
  into the compiler. A ratchet minimum sits just under the measured value. The three
  renderers are deliberately absent: they are Scala Native and ART code exercised by
  self-tests against the real toolkit, which scoverage cannot instrument, so their absence
  is not a testing gap and the number must never be quoted as "the project's coverage".
  Two traps are encoded in `bin/coverage.sh` rather than left to be rediscovered: sbt 2's
  disk cache restores instrumented classes without re-running the compiler, so the
  scoverage *metadata* is missing and the module silently drops out of the aggregate; and a
  report with no metadata reads **100% of 0 statements** and passes any threshold, so the
  script fails when the statement count is zero. See `docs/12` §12.9.

- 2026-09-29 — **The project is `Thicket`, not `scala-ui`.** `scala-ui` was provisional and
  became a liability ahead of going public: `<language>-<thing>` reads as *official*, so
  `dev.scalaui` would invite the assumption of Scala Center or EPFL endorsement while
  claiming "the" Scala UI namespace for a project that is 15 of 32 widgets in. It is also
  unsearchable and unbrandable. **Thicket** comes from the gap the project exists to fill —
  *thick client* — and a thicket is a dense growth of trees, which is what the framework
  manages. A mothballed functional language shares the name; that is precedent, not an
  obstacle (Google shipped Go over McCabe's Go!, and Rust is also a plant disease). The
  collision is handled in handles and domains, not by deforming the name into `ThicketUI`,
  which would keep the collision while looking like a component library.
  Packages `thicket.*`, groupId `dev.thicket`, artefacts `thicket-*`, app ids
  `dev.thicket.*`, env var `THICKET_SELFTEST`, system property `thicket.root`.
- 2026-09-29 — **The Apple shim's `sui_` ABI prefix is deliberately left alone** in the
  rename. Renaming 35 exported symbols across two Swift files, a C header and the Scala
  externs is Apple work that cannot be compiled on Linux, and Swift never sees a Scala
  package name, so leaving it costs nothing functionally. What *was* renamed there is the
  part the Linux tooling verifies: the header filenames and include guards
  (`thicket_apple.h`, `THICKET_APPLE_H`), the static library (`libthicketapple.a`), the
  Swift module name and the iOS bridging header — plus `thicket_todo_start`, which is a
  cross-boundary symbol the Scala side had already renamed and whose Swift caller would
  otherwise have failed to link. `shimGen`'s ConsistencySuite proves the four artefacts
  still agree.
- 2026-09-29 — **A callback may return a pointer across the Scala/Swift boundary; what is
  banned is a returned *struct*** (#1). A virtualising table inverts control — it
  asks for the row it is about to show — so `sui_row_cb` returns the row's view rather than
  taking it. That looked like it collided with S3/S4, which found `CFuncPtr` returns
  silently wrong, but those were small structs returned by value in the wrong registers. A
  pointer is one register. Verified by running it: 10 000 rows, correct views, no
  corruption. The ban in the ABI description is now written as "no struct by value",
  which is what it always meant.
- 2026-09-29 — **`RowSource` needed no change for a third toolkit in a row.** `NSTableView`
  (`makeView(withIdentifier:)`) and `UITableView` (`dequeueReusableCell`) both fit the
  contract as written, after `GtkListView` and Android's `ListView`. Measured on a
  10 000-row list: **AppKit 40 row views, UIKit 34**, against GTK 205 and Android 66 — the
  Apple toolkits recycle harder, not less. The counter lives in the shim
  (`sui_table_materialised`) rather than the self-test, because only the platform knows what
  it chose to build, and "how many rows exist" is exactly the measurement that distinguishes
  virtualisation from a list that merely works in a demo. With `supportsVirtualRows = false`
  the same probe produced **no output in 151 s** — the tree never finished mounting, which
  is what "works in the demo and dies in an app" looks like with a stopwatch on it.
- 2026-09-29 — **Apple measurements now run through focused probes, not the demo's
  self-test.** Phase 2 made the shared `TodoApp` use six widgets `AppleRenderer` throws on
  (#4), so the Apple demo cannot start at all, and an unrelated gap would otherwise
  block every Apple measurement behind it. `LazyProbe` (a 10 000-row screen and nothing
  else) and `run-fit-harness.sh` (which links the shim without the app) are the pattern: a
  renderer measurement should depend on the widgets it is measuring and no others.

- 2026-09-29 — **A scroll view must pin its document view on the cross axis, on both Apple
  toolkits** (Forgejo #4). The vertical `Scroll` had been left on AppKit's defaults with the
  note that it laid out correctly without constraints. It did — until something inside it
  asked the container for its width. A horizontal `Scroll` does exactly that, which closed a
  width loop (`Column` asks its children, a child asks the `Column`) and Auto Layout resolves
  a circular width as **zero**: the button row collapsed to 0px rather than clipping. So the
  cross-axis pin is not an optimisation for the horizontal case, it is what keeps the
  vertical one composable. Measured on macOS: viewport 206px against content 357px.
- 2026-09-29 — **A renderer's self-test asserts the *axis*, not GTK's viewport-to-content
  ratio.** GTK asserts `viewport * 4 < content`, which is a GTK constant rather than a
  cross-toolkit invariant: `GtkScrolledWindow` reports a minimum near zero, `NSScrollView`
  has no intrinsic size and is pinned to fill its parent, and `UIScrollView` reports its
  laid-out frame — the three numbers are not comparable. The portable invariant is that a
  horizontal scroller leaves its content's **width free**, so content ≠ viewport; a scroller
  built on the wrong axis reports them equal to the pixel. Falsified on both platforms by
  forcing the vertical kind code and confirming the check goes red. Also recorded: on every
  simulator available here the phone is wider than the five buttons, so the *overflow* path
  is exercised on macOS only and the iOS self-test says so out loud rather than passing
  silently.
- 2026-09-29 — **A horizontal `Scroll` is Apple kind code 15, not 9.** Phase 2 reserved
  9-14 for the widgets it added (`docs/12` §12.2), and an axis is not worth renumbering six
  pending widgets over. The collision is the predictable cost of the kind code being a bare
  int agreed by comment across four files, which is the thing Forgejo #7 exists to fix.
- 2026-09-29 — **Tests are zio-test, not munit, across every module.** User preference, and
  it paid for itself twice on the way in. (a) zio-test runs a suite's tests **in parallel by
  default**; munit does not. That broke the signal graph on the first run — `NullPointerException`
  on `Runtime.collected`, `ConcurrentModificationException` in `relink` — because the
  dependency-tracking context is two process-global `var`s, deliberately, since an `Option`
  per tracked read is measurable against the 1 µs/node budget. The graph is owned by one
  thread, which `ThreadGuard` enforces in production, but nothing in the test suite *said*
  so. Every graph-touching spec now carries `@@ TestAspect.sequential` with the reason.
  (b) `TestAspect.sequential` orders tests **within** a spec and gives no ordering
  *between* specs. `BridgeSpec` and `RemoteScreenSpec` both install `UiThread` and
  `ThreadGuard`, raced, and timed out. Suites sharing process-global state must be **one
  spec**; they are now `EffectZioSpec`. `munit-scalacheck` is gone — property testing comes
  with zio-test. `Checks` accumulates assertions made *during* a test, because these tests
  check a mutable graph after each step and the interesting property is usually how many
  times something happened between two writes, which the end state cannot show.
- 2026-09-29 — **Never stringify a number in a cross-built test.** Scala.js has no
  int/double distinction, so `7.0.toString` is `"7"` there and `"7.0"` on the JVM. Phase 2's
  `CatalogueSuite` compared rendered strings for `Prop.Value`, `Prop.Range` and
  `Prop.Progress`; it passed on the JVM and **had never been run on JS or Native**, where it
  fails. `TestRenderer` now keeps numeric props in a separate `nums: Map[String, Double]`.
  The lesson is the process one: running `coreJVM/testOnly` is not running the tests.

- 2026-09-29 — **sbt 2's disk cache does not notice a *deleted* test source.** Renaming
  `GraphPropertySuite` to `GraphPropertySpec` left `signalsNative/nativeLink` failing with
  "Unreachable symbols found" pointing at the deleted file, through `clean`, through
  deleting the module's `target/out` subtree, and through touching sources — every run
  reporting "cache 100%, 35 disk cache hits". The fix is to clear `~/.cache/sbt/v2` *and*
  `target/out/<platform>`; clearing either alone is not enough. Worth knowing before
  chasing a phantom compile error for twenty minutes.
- 2026-09-29 — **A widget enters the catalogue only if every platform we render to has it.**
  `docs/07` §7.10 listed the v1 catalogue before any renderer existed; phase 2 checked each
  entry against four real toolkits for the first time and two failed. **`Radio`**: UIKit has
  no radio control at all, and mutual exclusion is a sibling *relationship* that Android
  expresses structurally (`RadioGroup` is a container) and GTK as a pointer to a sibling —
  neither is a prop, and the contract has no grouping concept. **`Stepper`**: Android has
  none, and `NumberPicker` is a scrolling wheel, a different control. Both were verified
  against the installed GTK4 headers and `android-36/android.jar`. Neither is built;
  both are reclassified as app-level composition or `.platform` escape hatches, and "32
  widgets" is retired as the denominator. Every remaining §7.10 entry gets this question
  asked before it is built, not after. See `docs/12` §12.2a.
- 2026-09-29 — **A slider's `Prop.Value` is in the app's units, never a fraction**, and
  `Prop.Range` is sent before it. An app choosing a volume between 0 and 11 says 7, not
  0.636; the renderer whose control is integral underneath (Android's `SeekBar`, 1 000
  steps) does the conversion, because only it knows its own resolution. Range first because
  a value outside its bounds is meaningless and every toolkit clamps silently — the
  ordering is asserted in `CatalogueSuite` rather than left to how the DSL happens to be
  written. `SecureField` is a widget kind rather than a flag on `TextField` because
  `NSSecureTextField` is a separate class and an Apple widget is chosen at `create`.

- 2026-09-29 — **`Toggle` carries no label, and `ActivityIndicator` has no "running" prop.**
  Two catalogue-shaping calls made in phase 2, both for the same reason: do not promise an
  API that some platforms cannot keep. A `GtkSwitch` and a `UISwitch` have nowhere to put a
  caption — only Android's `Switch` does — so `Toggle` takes none and the caption is a
  sibling, which is what a settings row looks like on all four. And a spinner spins while it
  is mounted, so `Show(loading)(Spinner())` stops it rather than a second prop that
  duplicates what the reconciler already does. Related: `Prop.Progress` is
  `Option[Double]`, where **`None` is indeterminate and is deliberately not `Some(0.0)`** —
  every toolkit here distinguishes them visibly, so collapsing them would make "nothing has
  happened yet" and "we cannot say" look identical. `Toggle` is also a separate
  `WidgetKind` rather than a style flag on `Checkbox`, because the platforms disagree about
  which control a given setting *is*, and that is the app's judgement to make.

- 2026-09-28 — **The Apple shim's *bodies* are not generated; its *declarations* are.**
  Phase 1 measured the two shims before building anything and disproved its own premise: 80
  generatable signature lines against 211 hand-written body lines per shim, and of 34 shared
  functions only 7 have byte-identical bodies while 14 are genuinely different AppKit/UIKit
  code. The projected ~5 200-line saving from generating Swift was never available. What is
  available is worth more: every boundary function is declared **four** times (C header, two
  `@_cdecl`, one Scala `extern`) — 136 declarations at current size — and **nothing checks
  them**, because the linker matches by name only, so an `Int32`/`Int64` mismatch links
  cleanly and reads a garbage register at runtime. `tools/shim-gen` describes the ABI once,
  generates the declaration layer, and checks all four copies agree; it is plain JVM and
  runs where there is no Xcode. Two deliberate exceptions are encoded: `sui_set_root_view`
  is Swift-only (iOS owns `@main`), and the Scala comparison runs at machine
  representation, because Scala Native binds `sui_handle` and `const uint8_t *` to the same
  `Ptr[Byte]`.

- 2026-09-28 — **Work is phased: one phase, one branch (`phase/<n>-<slug>`), one PR**, with a
  measured exit criterion each. `docs/13-phases.md` holds the table. Phase 1 is the shim
  generator rather than widget breadth, because at 11.0 Swift lines per exported function
  across two shims the remaining 23 widgets are otherwise the largest cost in the project —
  and because the generator is testable on Linux against the hand-written shims as golden
  files. README rewritten as a real README (prerequisites, run-the-demo, first app, the
  three concepts) and an Apache-2.0 `LICENSE` file added; the licence was declared in
  `build.sbt` but the file was missing.

- 2026-09-28 — **Phase 0 declared closed; work is now explicitly MVP work.** The GO gate was
  met on 2026-09-19 (`docs/10`); everything built since has been MVP work under a phase-0
  label. MVP (0.1) is defined in `docs/12` §12.7 as *an outside developer can build and ship
  a real app for Android, iOS and one desktop without reading the framework's source*, with
  shim generation first because it is what makes the remaining widget breadth affordable.
  Windows, Yoga layout, the dev canvas, the CLI and the cats-effect bridge are out of the
  MVP. Also introduced `docs/12-component-status.md` as the living done/left list — there
  was no such list before, and the gaps were scattered across commit messages.

- 2026-09-28 — **`Row` overflow is answered by a horizontal `Scroll`, not by a wrapping
  `Row`.** New `Prop.Axis(Orientation)` on `WidgetKind.Scroll`, read at `create` and
  ignored at `update`: on Android the two directions are different widget classes, so no
  renderer can honour a later change without replacing the widget, and the contract now
  says so explicitly. Chosen over a `FlowBox`/`FlexboxLayout` wrapping container because
  all four toolkits have a scroller and only two have a wrapping box. Measured on GTK: the
  five-button row wants 429 px, the scroller asks 46 px. GTK and Android implemented;
  AppKit/UIKit no-op the prop pending a shim change (Forgejo #4). Wrapping and ellipsis
  remain unsolved.

- 2026-09-28 — **No handle table in any renderer may use `synchronized`; they use
  `ConcurrentHashMap` + `AtomicLong`.** S9 found on iOS that a monitor-guarded
  `mutable.LongMap` — the obvious design, and correct on the JVM — throws
  `IllegalMonitorStateException` on Scala Native under real load and leaks ~400 bytes of
  main-thread stack per callback that reposts from inside itself (measured 79 600 bytes vs
  0), which kills iOS's 1 MB main thread. GTK's `Handles.scala` had the identical shape and
  the identical traffic (`g_idle_add` callbacks registering further callbacks); Linux's
  8 MB main thread only postpones the failure. Fixed in
  `modules/renderer-gtk/.../Handles.scala`; GTK self-test and all 13 JVM suites still pass.
  Consequence: this is a standing rule for the Android and Windows renderers too — a
  Native-hosted callback table is lock-free or it is wrong.

- 2026-09-23 — **Issue #3 done: the Apple renderer covers macOS and iOS from one Scala
  module and two Swift shims.** `modules/renderer-apple` (was `renderer-appkit`) with
  `Shim+AppKit.swift` and `Shim+UIKit.swift` against one `thicket_apple.h`; both hosts run
  the same `TodoApp` from `examples/todo-apple/shared` and pass the same 23 checks — macOS
  measuring 480x460, iOS simulator 365x724. Consequences:
  (a) **`modules/renderer-api` needed no changes for either platform.** The contract was
  written against GTK, checked against `android.view`, and absorbed AppKit and UIKit without
  a line moving. `insertAfter`-by-preceding-sibling, `destroy`-detaches, and the remove+insert
  `moveAfter` default all earned their place; `NSStackView`/`UIStackView` have no reorder
  primitive, so unlike GTK neither renderer overrides `moveAfter`.
  (b) **The Scala side is genuinely platform-neutral across Apple.** `AppleRenderer`,
  `AppleApp`, `AppleInspect`, `Handles` and `GcState` are shared byte-for-byte; the whole iOS
  delta is one Swift file plus a 4-line entry point. S3's per-*file* shim separation was the
  right call and is now the rule: no `#if os()` inside shim function bodies.
  (c) **One asymmetry is real and must stay in the design: who owns `main`.** On macOS
  `sui_app_start` enters `NSApp.run()` and never returns; on iOS the Swift host owns `@main`
  because iOS 27 requires UIScene adoption and a scene delegate cannot live in the static
  archive, so `sui_app_start` schedules `ready` and returns. `AppleApp` cannot tell the
  difference. Any future Apple entry-point API must not assume the macOS shape.
  (d) **Shim cost measured: 11.0 non-comment Swift lines per exported function**, against
  S3's 5.8 estimate from a 21-function spike — and now doubled, because there are two shims
  with nothing but a header and a self-test keeping them in step. §7.6's
  generate-shim+header+bindings-from-one-description recommendation is upgraded from
  desirable to necessary before the catalogue grows.
  (e) **iOS build settings are non-negotiable and now encoded** in `iosNativeSettings`:
  `BuildTarget.libraryStatic`, `GC.immix`, and the linktime property `target.os -> "darwin"`
  (S1). Simulator only; a device build is M1's.

- 2026-09-20 — **S6 done (partial, as its brief allows): calibration baselines measured.**
  Three hello apps, one harness, same machine. Option B (the recommendation) 0.53 MB / 425 ms /
  152.4 MB RSS; Option A (React Native/Expo) 26.0 MB / 702 ms / 203.2 MB; Option D (JavaFX +
  Gluon) 60.06 MB device binary and **no runtime numbers** — its `ios-sim` target is x86_64-only
  and will not build on Apple Silicon. `spikes/s6-calibration/REPORT.md`. Consequences:
  (a) **Option A is not available to this project in Scala** — `slinky-native` has no Scala 3
  build at any version, so §6.1's effort score for A assumes a facade library that does not
  exist for Scala 3.
  (b) **Option D's iOS toolchain is frozen**: Gluon's mandatory patched GraalVM last shipped
  2024-09-08 while its plugin shipped through 2026-06, and only plugin 1.0.24 + Maven exactly
  3.8.8 builds at all.
  (c) **`docs/06` §6.1 should gain a binary-size row** — the sharpest number measured, and
  N-03 already makes size a requirement.
  (d) **CocoaPods belongs in `spikes/MAC-SETUP.md`** if Option A is ever revisited.
  The Option B recommendation survives calibration on every axis measured.
- 2026-09-19 — **S8 PASSED: ZIO 2.1.26 runs on Scala Native on iOS.** Runtime init 1.2 ms,
  +3.25 MB RSS, 37,500/37,500 ticks delivered at 2.1% CPU over 10 minutes, prompt
  interruption, no crash. `spikes/s8-zio-ios/REPORT.md`. Binding consequences:
  (a) **ZIO on Native requires `io.github.cquiroz::scala-java-time` 2.7.0** —
  `zio.Duration` is `java.time.Duration` and Scala Native has no `java.time`; ZIO's Native
  artefacts do not supply a substitute, and without it ZIO does not link.
  (b) **Apple shipping configuration is `Mode.releaseFast` + `LTO.full` + `-lc++`** at the
  final link. That is 5.75 MB stripped (inside N-03's 6 MB) versus 7.73 MB with LTO off.
  `LTO.thin` is warned against on Mac by Scala Native's own Validator — use `full`.
  (c) **`ZStream.tick` is fixed-delay, not fixed-rate**, so it yields ~55–58 Hz, not 60.
  100% of ticks arrive; they just arrive late. Frame pacing needs `Schedule.fixed` or a
  display link — but note fixed-delay is also why resuming from background produces no
  catch-up burst.
  The §7.13 decision that the **ZIO bridge is first-class from M0 stands** — the size and
  runtime costs are all inside budget.
  Also: **do not drive build configuration from environment variables in sbt 2.** Beyond the
  server capturing env at startup (S1), sbt 2 caches the evaluated setting in its CAS with
  `sys.env` outside the cache key, so a stale value survives killing the server and
  `touch build.sbt`; only a content change or deleting `target/out` clears it.
- 2026-09-19 — **S3 PASSED: the Swift C-ABI shim design is sound.** Round trip 433 ns vs
  N-05's 5 µs; no leak over 100k taps; sn-bindgen consumed the header. Full report in
  `spikes/s3-swift-shim/REPORT.md`. Binding consequences:
  (a) **Scala must mark itself GC-Unmanaged whenever it returns into a host event loop**
  (`scalanative_GC_set_mutator_thread_state`), or the first GC from a background thread
  deadlocks against the run loop and aborts. Applies to GTK and Win32 too, not just Apple.
  (b) **Shim ABI rules, all now measured:** no struct by value across a Scala callback
  (S4's bug reproduces on arm64, returning zeros); callback context is `int64_t`, never
  `void*`; strings leaving the shim use caller-supplied buffers.
  (c) **`@blocking` is not the default** on shim externs — it doubles per-call cost
  (216 → 444 ns) and bought nothing in a 400-round GC soak.
  (d) The C header is split types/functions so Swift can implement `@_cdecl` without a
  redeclaration clash, and the iOS app template must adopt the `UIScene` lifecycle
  (iOS 27 traps at launch otherwise).
  **Gate: S1 ∧ S2 ∧ S3 have all passed — `08` §8.1's go decision is a go**, with each
  report's risks recorded.
- 2026-09-19 — **S1 PASSED (with risk): Scala Native 0.5.12 runs on the iOS simulator.**
  Full report in `spikes/s1-native-ios/REPORT.md`. Three binding consequences:
  (a) **iOS builds must set the linktime property
  `scala.scalanative.meta.linktimeinfo.target.os = "darwin"`** — without it `java.lang.Thread`
  does not link, because javalib's `LinktimeInfo.isMac` rejects the `ios` OS while Scala
  Native's own `Config.targetsMac` accepts it. This is the iOS equivalent of Android's three
  mandatory R8 settings.
  (b) **Scala code must only run on the main thread or on Scala-created threads — never a GCD
  queue**, which segfaults in the GC allocator; Scala Native 0.5.12 cannot attach a foreign
  thread. Binding on S3, S8 and every Apple renderer.
  (c) **immix is the only GC that builds for iOS** (boehm and commix need headers absent from
  the iOS SDK), so there is no fallback collector.
  Also recorded: `java.time`, `java.text` and `java.util.Locale` are absent from Scala Native's
  javalib, and such gaps fail only at `nativeLink`, never at `compile` — so CI must run
  `nativeLink` per module. Measured on Xcode 27.0 / iOS 27.0 simulator / Apple clang 21.0.0.
- 2026-09-18 — **Apple spikes run on the iOS simulator only**; no physical iPhone is
  available. Sufficient for go/no-go (the question is whether the Scala Native runtime
  works on iOS at all); device signing, arm64-only codegen and real memory pressure are
  deferred to M1. S1's brief updated accordingly.
- 2026-09-18 — **Repo hosted on the user's Forgejo**
  (`ssh://forgejo@forgejo.leibmanland.com/rleibman/scala-ui.git`), matching meal-o-rama,
  **superseded 2026-09-29: the project moved to `github.com/rleibman/thicket`**,
  rather than GitHub. Push-to-create is disabled server-side, so the repo must be created
  in the web UI before the first push.
- 2026-09-18 — **Version policy set by the user: always target the latest stable Scala and sbt,
  for all projects.** Consequently sbt 1.13→**2.0.9** and Scala 3.3.8 LTS→**3.9.0**. The earlier
  claim that the Scala.js/Native/crossproject plugins are sbt-1-only was **wrong** — they publish
  under the `_sbt2_3` suffix. S5 verified the whole JVM/JS/Native crossproject on sbt 2.0.9 +
  Scala 3.9.0. Known sbt-2 gaps recorded above (`%%%`, incremental `test`).
- 2026-09-18 — `libunwind-dev` installed on the Linux box (Scala Native prerequisite).
- 2026-09-18 — Initial decisions written during planning. Effect strategy: core is
  effect-free; ZIO bridge is first-class from M0 (see `07` §7.13). Hardware: Linux
  box (primary), macOS laptop (Apple spikes), Windows box (later); iOS device
  possibly available, simulator is the baseline.
