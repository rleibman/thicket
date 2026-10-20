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

- 2026-10-08 — **GTK's Wayland input-method race is worked around in the renderer, not only
  in the test** (#56; answers `09` §9.8). The project owner's call: upstream bugs get
  workarounds here rather than waiting on reports. #57 stopped the self-test from racing; a
  real app could still lose the race if the first text field it ever focuses is destroyed in
  the same main-loop turn.
  Now `GtkRenderer.destroy` first asks `WaylandFocus` whether the widget going away holds the
  keyboard focus. If it does, and the display is a `GdkWaylandDisplay`, it does one
  `wl_display_roundtrip`. That delivers the registry reply GTK's input method is waiting
  for, so `text_input` exists, and then the focus is cleared, so GTK's own focus-out and
  unrealize release the context normally. **The roundtrip is the part that matters**: clearing
  the focus alone, tried first, crashed 5/5, because `focus_out` does nothing until
  `text_input` arrives. The functions are found with `dlsym`, not linked, so no app's link
  line changes and a GTK without Wayland just skips it.
  The self-test is put back the way #57 found it, mounting and unmounting the sheet in one
  callback, so every run exercises the race: 6/6 clean with the workaround, 3/3 crashing
  without it (`ok=67`, `Unhandled signal 11`, the #56 signature). Clean on X11 as well, where
  the workaround does nothing.

- 2026-10-07 — **`Grid` is built, the second §12.2a exception; icons come before `TabView`**
  (#60, #61, both decided by the project owner). UIKit has no grid view, while GTK
  (`GtkGrid`), Android (`android.widget.GridLayout`, framework, not deprecated in
  `android-36`) and AppKit (`NSGridView`) do. So `Grid` is built, and UIKit's is a
  constraint construction, like `SegmentedControl`'s on GTK and Android.
  The shape is the smallest one a form needs: children flow into `Prop.Columns` columns in
  child order, and cell `i` is at row `i / columns`, column `i % columns`. Each column is as
  wide as its widest cell, which is all three native grids' default. A cell is just the next
  child, so `Show` and `ForEach` add and remove cells and the later cells move along. Spans
  and explicit placement are out until an app needs them.
  **GTK** re-places every cell through its `GtkGridLayoutChild` whenever the children
  change, including on `destroy`, which detaches without `removeChild`. Remove-and-reattach
  is not an option: `gtk_grid_remove` drops the grid's reference and frees the child.
  **Android** relies on `GridLayout`'s auto-flow, so inserting at an index is the whole of
  it. `GridLayout` has no spacing, so the gap is a margin on every cell that is not first in
  its row or column, recomputed on every change. `Grow` is ignored in a grid, as in a
  `ZStack`, so a child keeps its `GridLayout.LayoutParams`.
  Falsified on both: without re-placing on insert, GTK put the inserted cells over the
  first row; without the column count, Android flowed everything into one column.
  The Apple half is **#62, for the Mac**. Kind code 22 is reserved as
  `Kind(22, "Grid", None, None, …)`, and `Prop.Columns` is a no-op in `AppleRenderer` until
  then.
  `TabView` waits for an icon system (#61). Android's framework tab controls are all
  deprecated (`TabHost`, `TabWidget`, `ActionBar.Tab`), and tab bars on iOS and Android are
  icon-led.
- 2026-10-07 — **The Apple CI runner runs in the logged-in GUI session, without
  `SessionCreate`.** First runs on the self-hosted runner (registered on the owner's Mac,
  `magrathea-thicket`, labels `self-hosted, macOS, ARM64, thicket-apple`) passed 12 of 13
  steps and failed `getting-started` twice, for two reasons that never show up by hand:
  sbt's output under the runner puts a terminal escape *before* the version line, so a
  `grep '^[0-9]'` found nothing and `pipefail` ended the script silently; and the window
  check found `windows=0` for an app that was up. The second is the runner, not the script:
  `svc.sh`'s LaunchAgent template sets `SessionCreate`, which gives the runner a security
  session of its own, outside the GUI session, where AppKit runs but the window server does
  not show its windows. With the escapes stripped, the window counted by size (320 wide,
  ≥160 tall — the app also owns 1728x33 menu-bar strips on layer 0), and `SessionCreate`
  deleted from the plist, the workflow passes **all 13 steps on the runner**. The macOS
  self-tests had passed under `SessionCreate` too, because they read their own views rather
  than ask the window server; they are now running where a user's windows are.

- 2026-10-07 — **Apple's `Picker`, `ZStack`, `SegmentedControl`, `DatePicker`, `Link` and
  `SafeArea` were run on a Mac for the first time, and measured** (#45, #46). The Swift was
  written on Linux, where it cannot be compiled; it compiled first time. One crash, iOS only:
  the gallery aborted at mount with a `NullPointerException` inside the signal runtime. That
  was not the cause but Scala Native failing to handle a **foreign** exception — UIKit raised
  `NSInternalInconsistencyException: Menu does not have a valid element for default
  selection`, because the menu-backed Picker set `changesSelectionAsPrimaryAction` with an
  empty menu and its options arrive afterwards, clear-then-add. Found by installing an
  Objective-C exception preprocessor in the host, after two wrong theories (a re-entrant
  `OnSelect`, a re-entrant callback — both ruled out by instrumenting them). The flag now goes
  on only once the menu has an element. **An Objective-C exception that unwinds into Scala
  frames is uncatchable there and surfaces as a misleading NPE**; the shims must never let
  UIKit or AppKit raise. The gallery's Apple checks for these controls had been section
  headings only — text that is on screen whether the control works or not — so they now ask
  what GTK asks, of the platform's control: options and selection, a date (the leap day, so an
  off-by-one reads as March 1), segments and their selection, a choice through the control's
  own action reaching the app, links clicked with the opener recording instead of launching a
  browser, and ZStack placement and size. Positions are **alignment rects** (what Auto Layout
  positions); by frame, an AppKit label overhangs a trailing edge it is exactly aligned to by
  2 pt. Gallery **48/48** macOS, **47/47** iOS; UIKit cannot perform a `UIAction` from code, so
  the iOS Picker choice is reported as not exercised. Controls: a date written one day late
  and links that open nothing fail four checks between them.
- 2026-10-07 — **The Apple half is verified by a self-hosted Mac runner, and only on
  `push`** (#46; the project owner chose this over cross-compiling with xtool, whose Darwin
  SDK comes from an `Xcode.xip` that Apple's licence keeps off non-Apple hardware).
  `bin/verify-apple.sh` is the whole of it, so CI and a run by hand on the Mac are the same
  thing. It compiles both shims, runs shim-gen's consistency checks, links the four Apple
  apps, runs the gallery and todo self-tests on macOS and on the simulator, and measures
  `ContentFit`. Every step runs even after a failure, so one run shows everything that is
  broken, and the summary is the result. `.github/workflows/apple.yml` runs it.
  **Never on `pull_request`.** The repository is public and the runner is a personal
  machine, so that event would run a stranger's fork on it. `push` needs write access, and a
  PR from a branch here still gets the check from the push that updated it. The job also
  checks `github.repository`, so a fork that copies the workflow finds no runner, and the
  third-party actions are pinned to commit SHAs rather than tags that can be moved.
  The runner has to be a LaunchAgent in a logged-in session, because the macOS self-tests
  open real windows.
  **First run, on the Mac (2026-10-07):** the script as written could pass on binaries it had
  not built. Its link step handed sbt's thin client four commands as separate arguments,
  which it rejects, so nothing linked — and the self-tests then ran whatever an earlier build
  had left in `target/`, and passed. Now each app links in its own invocation, their old
  outputs are deleted first so a failed link fails the self-tests, any running sbt server is
  shut down so the build (and its `git describe` version) is this checkout's, every iOS step
  starts from a freshly booted simulator, and `SBT_OPTS` defaults to a 4 GB heap. Added: the
  10 000-row probe on both platforms and `bin/verify-getting-started-apple.sh`. On `main` it
  reports the two real failures #59 fixes (the iOS gallery's Picker crash, a stale image
  check); with #59 merged, **all 13 steps pass**.

- 2026-10-07 — **The GTK todo self-test's segfault was a GTK race, and the fix is to stop
  racing it** (#56). Found by reading GTK's private state from the self-test: the
  `GtkIMContextWaylandGlobal` and its `current` context, at their offsets in GTK 4.22.4's
  `gtkimcontextwayland.c`. On Wayland GTK creates its input-method state lazily, on the
  **first** input-method focus-in, and gets its `text_input` only from a registry reply that
  arrives when the main loop next runs. `focus_in` records the context as `current` *before*
  checking for `text_input`; `focus_out` refuses to do anything until `text_input` exists.
  So if the first text field ever focused is torn down in the same main-loop turn, `current`
  is never cleared. The context is freed, and the next `zwp_text_input_v3.enter` (sent when
  the window gains keyboard focus, which is the compositor's timing) dereferences it.
  The self-test did exactly that: it mounted the sheet, whose field is the run's first,
  and unmounted it in one callback. Measured: `text_input=null` throughout the teardown;
  after one main-loop turn it was set, and `current` cleared to null as it should.
  **The fix is in the test**: it posts the rest of the run after the sheet is mounted,
  because no user opens and closes a sheet inside one frame. 8/8 runs pass where `main`
  crashed 3/3.
  **Why it came and went:** the crash needs the window to get keyboard focus afterwards, so
  it depended on what the desktop was doing. `feat/link` passed this test the same morning
  that `main`, with identical code, crashed. Adding the `Link` only shifted the timing.
  **What was rejected:** clearing the root's focus before destroying a widget (it does
  clear, but `focus_out` still bails while `text_input` is NULL); creating the IM state at
  startup (the function that does it, `gtk_im_context_wayland_get_text_protocol`, is not
  exported); faking an input-method focus at startup (can pop an on-screen keyboard); and a
  `wl_display_roundtrip` during teardown (re-entrant event dispatch mid-destroy). The narrow
  real-app exposure that remains is recorded in `09` §9.8.

- 2026-10-06 — **`DatePicker` is the compact idiom on all four, and its value is a day,
  not an instant** (#50). Each platform's *form* control, not an inline calendar: a
  `.compact` `UIDatePicker` and a text-field `NSDatePicker` are the Apple ones. GTK has no
  compact date picker, so it gets what GNOME apps use, a `GtkMenuButton` whose popover holds
  a `GtkCalendar`. Android gets a spinner-styled field that opens `DatePickerDialog`; the
  inline `DatePicker` is a whole calendar, and no form puts one in a row.
  **The value is `CalendarDate(year, month, day)`**: no time and no time zone. Not
  `java.time.LocalDate`, which Scala Native lacks. Months are 1–12 in the contract;
  Android's and `GtkCalendar`'s count from 0, and each renderer converts. Impossible dates
  are refused rather than rolled over, which is how a lenient calendar comes to show a date
  nobody chose. Across the Apple ABI it is an **epoch day** (`int32`), converted by Hinnant's
  `days_from_civil`, which is arithmetic only and is checked against every day from 1800 to
  2200. The shims read it at **midnight UTC in a picker set to UTC**, which is the same day
  wherever the device is. The picker keeps the user's own *calendar*, so a Buddhist or
  Japanese locale still sees the right day. Android formats its field the same way, the
  user's medium format at UTC, because the user's zone would show the day before everywhere
  west of Greenwich.
  The gallery and the todo app start on **2028-02-29**, so a one-off slip shows as March.
  Falsified on both Linux-side platforms by dropping the 0-based month conversion: GTK's
  calendar showed `(2028,3,29)`, Android's dialog `2028-3-29`.
  One Android trap: `AlertDialog` delivers a button's listener and its own dismissal as
  `Handler` messages, so the first check, made right after clicking OK, saw nothing. The
  check now runs behind them, posted.
  Known GTK behaviour, left alone: `GtkCalendar`'s month arrows move the selected date, so
  browsing months reports dates to the app. That is GtkCalendar's model, and a GNOME user
  expects it.

- 2026-10-06 — **`SegmentedControl` is built, and is the one §12.2a exception** (#50,
  decided by the project owner there). Only the two Apple platforms have one:
  `NSSegmentedControl` and `UISegmentedControl`. By the rule it would be an app-level
  composition; it is in the contract anyway because it is a common, recognisable control. The
  condition is that each other platform gets **its own construction, not an imitation of
  Apple's**.
  **GTK: a `linked` `GtkBox` of grouped `GtkToggleButton`s**, which is how GNOME apps drew
  this before `AdwToggleGroup` and what any GTK 4 still has. **Not `AdwToggleGroup`**, which
  the note on #50 allowed "where libadwaita is new enough": the pinned bindings (0.2.6)
  predate it, and declaring its symbols by hand would make **every** thicket GTK app fail to
  start on libadwaita older than 1.7. Ubuntu 24.04 LTS ships 1.5. A runtime switch is
  possible later, through `dlsym`, and is not worth its second code path today.
  **Android: a horizontal `RadioGroup`.** Android has no segmented control outside Material
  Components, a dependency this project does not take, and a `RadioGroup` is the framework's
  own "choose one, all visible": real mutual exclusion, and TalkBack reads "radio button, 2
  of 3". It looks like radio buttons because on Android it is radio buttons.
  **The same three props as `Picker`**: `Options`, `Selected` as an index with `-1` for none,
  and `OnSelect`. It is the same choice drawn differently, so the Apple ABI reuses the
  picker's five functions and adds only kind code **20**. The selection is held beside the
  control on GTK and Android, because both rebuild their segments when the options change and
  the index has to survive that. Falsified on GTK by dropping `gtk_toggle_button_set_group`:
  clicking "Month" left `List(false, true, true)`, two segments on.

- 2026-10-06 — **`Link` is `Prop.OpenUrl(url)`, and a tap and a URL on one widget both
  run** (#50; that it is a prop was the project owner's decision there). `Link(text, url)` is
  a `Button` that carries the URL and **no accent fill** — the fill is what makes a button
  look like a command, and a link leaves the app. `.link(url)` puts the URL on any element,
  as behaviour only.
  Every renderer keeps the app's tap and the URL **in two tables behind one connection** and
  dispatches both. A view has one click listener on Android and one target/action on Apple,
  so storing the URL as "the" handler would let `.link` on a tappable row silently replace the
  row's tap, or the tap the link — whichever was applied last. Falsified on GTK by letting the
  URL displace the tap: "and runs the tap as well" fails with the counter still at 0.
  Opening is the platform's: `GtkUriLauncher` (with the window as parent, so the portal can
  place a chooser), an `ACTION_VIEW` intent with `FLAG_ACTIVITY_NEW_TASK` when the context is
  not an Activity, `NSWorkspace.open`, `UIApplication.open`. A failure is logged, never thrown
  into a click handler: there is nothing the app could do differently.
  **The self-tests intercept the opener**, so clicking a link does not launch a browser on
  the machine running them. The platform half was measured once by hand on Android: the
  link tapped from `adb` brought `com.android.chrome` to the foreground. On GTK it has not
  been exercised; launching the desktop's browser from a test is not something to do
  unattended.
  Two GTK traps on the way. `gtk_widget_activate` on a button does not emit "clicked" until
  its pressed animation ends about 250 ms later, so the first check read an empty list; the
  self-test emits "clicked" itself. And `GtkUriLauncher` is released in its completion
  callback, the one place it is known to be finished with.
  Also found, not caused, here: **#49's Swift calls a `str(...)` helper that was never
  defined**, in both shims, so the Picker could not have compiled on a Mac. Defined now;
  nothing on Linux compiles Swift, which is the whole of #45/#46.

- 2026-10-06 — **`ZStack`, not `Stack`: children overlap in order, each at its natural size,
  placed by one alignment on the stack** (#50). The name is load bearing. `NSStackView` and
  `UIStackView` are what `Column` and `Row` already *are*, and a `GtkStack` shows one child at
  a time, so "Stack" would be misread by exactly the people who know the toolkits.
  Child order is **paint order** on all four (`GtkOverlay` snapshot order, `FrameLayout`
  index, subview order), so the reconciler's insert-after-sibling already is a z-order
  promise; nothing new crosses the contract for depth.
  The stack is as large as its **largest child**, and none of the toolkits does that by
  default, each for a different reason. **GTK:** a `GtkOverlay` is sized by its one *main*
  child and ignores overlays, so a ZStack has no main child and every child is an overlay with
  `measure` set; without it the stack measures **0×0**, and that is how the gallery check was
  falsified. **Android:** a view with no layout params gets the parent's defaults, and
  `FrameLayout`'s are `MATCH_PARENT` both ways where `LinearLayout`'s are `WRAP_CONTENT`, so
  every plain child was stretched to fill the stack. The emulator self-test found that
  one: a spinner measuring exactly the 300×374 of the picture it sat on. **Apple:** neither
  toolkit has a view for it, so a plain view whose wish to be 0×0 is at fitting-size priority
  (50), beneath anything a child asks for, with four containment inequalities per child.
  Alignment is **one `Prop.StackAlignment(horizontal, vertical)` on the stack**, not per
  child: the largest child fills the stack whatever its alignment, so what it decides is where
  the smaller ones sit, a badge in a corner or a spinner in the middle. `Start`/`End`, which
  all four mirror under right-to-left. The ABI passes it as two `int32`s spelled out by a
  match, not `Alignment.ordinal`, so reordering the enum cannot silently move them.
  Kind code **19**. The Apple half is written and passes shim-gen's consistency checks, but
  nothing here can build Swift (#46). The open cost of "natural size" is in `09` §9.7.

- 2026-10-05 — **`SafeArea` is a prop, and three wrong versions of its test were the real
  work.** (#50) §12.2a a fifth time: no toolkit models a safe area as something you place.
  Apple exposes `safeAreaInsets` on a view, Android dispatches insets to a listener on a
  view, and **GTK4 has no such concept at all** — so it is `Prop.SafeArea(Set[Edge])` and a
  `.safeArea()` modifier. GTK's answer is no padding, and that is *correct* rather than a
  stub: a desktop window's safe area is the whole window. That is the line between this and
  a control GTK genuinely lacks, where the only options are to imitate or decline.
  The payoff: the Android host's hand-rolled inset handling is deleted (`docs/05` F-02 had
  asked for this), and the demo now says `.safeArea()` once for all four platforms.
  **`Edge.Leading`/`Trailing`, not left/right**, because both Apple platforms and Android lay
  out RTL and a safe area pinned to "left" is wrong in Arabic on exactly the hardware that
  has a notch.
  What took the time was the check, and each wrong version failed differently. **One**: "does
  any view have top padding" — passed for the wrong reason, the screen's own
  `Column(padding = 16)`. **Two**: compare the target view against
  `getRootWindowInsets` — failed a *correct* implementation, because the root reports 128
  where the insets dispatched to that view are 275; they are not the same question.
  **Three**: asked inside `onCreate`, before the first layout pass, so insets read zero.
  The version that holds asks only what is true whatever the device reports: the view
  `.safeArea()` is applied to has top padding, and a `Scroll` declares none of its own.
  Falsified with `.safeArea(Edge.Bottom)` → `top paddings List(0)`.
  Two process notes worth more than the feature. `requestApplyInsets()` on a **detached**
  view is a no-op, and a prop is applied before the reconciler inserts the view, so the
  request has to happen on attach. And inside package `thicket.renderer.android`,
  `android.util.Log` resolves to the *local* package: my instrumentation never compiled, two
  builds failed, and I read the resulting empty logs as evidence twice — once concluding the
  listener never fired and once that the prop never arrived. Both were wrong. **Check the
  build exited 0 before believing a log.**

- 2026-10-05 — **The Swift shim ships inside `thicket-renderer-apple`'s jar** (#41). An
  Apple consumer needs `libthicketapple.a` as well as Scala artefacts, and it was built by a
  shell script here and never published. Of #41's options — a classified artefact, an
  XCFramework, the plugin running `build-shim.sh`, or "clone and build" — the jar is the one
  that rides the dependency graph a consumer already resolves: no second coordinate to keep in
  step, no build of Thicket's sources on their machine. Built at *package* time, so ordinary
  compiles do not need Xcode; both slices (`macos`, `ios-sim`) plus the headers, about 540 KB.
  `ThicketMacPlugin` / `ThicketIosPlugin` unpack it from the resolved jar and link it with the
  same `ThicketNativeFlags` the repository uses. Measured by
  `bin/verify-getting-started-apple.sh`, outside the repo against a local publish: the macOS
  template owns a window titled "Hello Thicket" after 10 s, the iOS template is still running
  in the simulator after 10 s; control: an iOS app that throws at start fails it. Three things
  the first runs got wrong, each now handled and commented where it is: sbt's thin client
  ends `print` with "[success]" and a terminal escape, so the version is the line that looks
  like one; sbt 2 keeps `target` under `target/out/`, so the template asks
  `print thicketAppleShim` for the path; and `launchctl list | grep -q` under `pipefail` fails
  on a match, because launchctl dies of SIGPIPE.

- 2026-10-05 — **`Picker` is a widget, its selection is an index, and its options cross the
  boundary clear-then-add.** The first new `WidgetKind` since the Apple shims were written,
  so it is also the first real test of what adding one costs: three props, five ABI
  functions, both Swift shims, three renderers, and **every renderer failing to compile with
  six errors each** until they handled the new props. That pressure is the point and it
  worked, but a widget is never a small change here.
  An **index**, not the selected string: two options may share a label, and every toolkit's
  selection API is index-based underneath. `-1` is "nothing", which is not `0`.
  Options are applied **before** the selection, and that ordering is load bearing rather than
  tidy — selecting index 2 of a list that is not there yet is ignored by GTK and throws on
  Android. `TestRenderer` now records the order props arrive in per node, because a map
  cannot express a sequence promise; falsified by swapping the two.
  Per-platform choices, each for a reason: GTK a `GtkDropDown` over a **`GtkStringList`
  model** rather than `gtk_drop_down_new_from_strings`, because options are a prop that
  changes and a model refills in place; `splice` in one call, since remove-all-then-append
  emits a change per item and flickers; `GTK_INVALID_LIST_POSITION` is unsigned `-1`, so a
  raw `-1` would select item 4294967295. Android a `Spinner` whose **adapter contents** are
  rebuilt rather than the adapter, since swapping the adapter resets the selection to 0.
  UIKit a **menu-backed `UIButton`**, not `UIPickerView` — a wheel is for long or continuous
  ranges — which means the selection has to be held beside the control, because a `UIMenu` is
  immutable and has no `indexOfSelectedItem`.

- 2026-10-05 — **`sbt-thicket` publishes the build, and the flags have exactly one
  definition** (#40). A GTK app was copying twelve lines of `nativeConfig` out of our
  `build.sbt`; now it says `addSbtPlugin("dev.thicket" % "sbt-thicket" % version)` and
  `.enablePlugins(ThicketGtkPlugin)`, and gets the pkg-config flags, `LTO.none`,
  `Mode.debug`, `GC.immix`, `sbt-scala-native` and the two Thicket dependencies at the
  plugin's own version. Measured on `templates/hello-thicket`, counting
  non-blank non-comment lines: **25 before (24 in `build.sbt`, 1 in `plugins.sbt`), 11 after
  (9 and 2)**. Totals including comments go 33 to 23. The twelve that mattered are gone. `bin/verify-getting-started.sh` PASSes, 5,213,600-byte binary, still up
  after 10s.
  **How the two copies are kept in step: there is one copy.**
  `tools/sbt-thicket/shared/src/main/scala/.../ThicketNativeFlags.scala` is compiled into the
  plugin *and* into this repository's meta-build, which adds that directory to
  `Compile / unmanagedSourceDirectories` in `project/build.sbt`. `gtkNativeSettings` and
  `thicketGtkSettings` are then the same method on the same bytes. The cost is a path the
  meta-build must be able to find — if it goes stale the build does not load, which is the
  failure we want, since no local copy remains to fall back on. The circular alternative,
  this build depending on the published plugin, would need a plugin release before the
  renderer could be built at all.
  Falsified three ways. Dropping `pkgConfig("--libs")` from the shared definition and
  republishing made the *consumer's* link fail with ~200 undefined `gtk_*`/`adw_*` symbols and
  **zero** compile errors; restoring it linked the same tree again, so the failure was the
  sabotage and not a stale binary. Pasting a `pkg-config` command line back into `build.sbt`
  now stops the build from loading with a message naming the file to edit instead.
  **A guard that was quietly not running.** That second check started as a zio-test suite
  reading `build.sbt`. It passed, and then sbt said "No tests to run" for every later edit —
  a build file is not one of a test's inputs, so the suite was cached green against a
  sabotaged `build.sbt`. Found by sabotaging and watching nothing happen. It is a build-load
  check now; `SharedFlagsSpec` keeps only what a test is good at, which is what the flags are.
  I also got the Scala pin wrong in the other direction: I expected a plugin compiled with
  3.9.0 to be unreadable by a meta-build on sbt's own 3.8.4, and said so. It is not — the
  template loaded and linked against it. `scalaVersion := "3.8.4"` stays as a precaution for
  a consumer on an older sbt, not as a reproduced failure.
  Apple is deliberately not a plugin. `ThicketNativeFlags.apple` and `.ios` are shared the
  same way and `build.sbt` uses them, but flags are not what an Apple consumer is missing:
  it needs `libthicketapple.a`, which nothing publishes, and on iOS the Swift host owns
  `main`. Neither function has been run since being moved — there is no Mac here, and they
  compile, which is weaker than verified. #41.

- 2026-10-05 — **The getting-started is a script, not a paragraph.** Phase 5's exit
  criterion is a person building an app in under thirty minutes, which only a person can
  measure — but the half that does not need one is checkable, so
  `bin/verify-getting-started.sh` checks it: publish locally, copy
  `templates/hello-thicket` **outside** the repo, build against the published artefacts,
  run. Outside is the point; building the template in place would resolve the modules as
  project dependencies and prove nothing about what a stranger gets from a jar. PASS at
  5.2 MB.
  Doing it rather than describing it found the thing worth finding: a GTK app has to copy
  about a dozen lines of our `nativeConfig` because **nothing publishes the build settings**
  (#40). The artefacts are consumable; the build is not. `docs/14-releasing.md` §14.3 says
  so plainly rather than leaving a stranger to discover it at link time, where a wrong GC
  fails with nothing pointing at the cause.
  Also: `versionScheme := early-semver`, and what counts as breaking here is wider than it
  looks. `Prop` is an exhaustive `enum` under `-Werror` and `Renderer` is a trait an app may
  implement, so *adding* a case or a member breaks downstream code. That pressure is
  deliberate — it is what stops a widget silently doing nothing on one platform — but it
  means "we only added something" is not automatically compatible.

- 2026-10-04 — **On Apple, a relative `ImageSource.FromFile` path falls back to the app
  bundle** (#31). Used as given when it is absolute or exists from the working directory — a
  binary run from the repo, as the macOS examples are — and otherwise looked up under
  `Bundle.main.resourcePath`. An iOS app's working directory is `/`, and the bundle is the
  only place its own files live. Found by a gallery check added for it: the iOS gallery
  showed **0 of 2** images, macOS 2 of 2; after the fallback and copying the logo into the
  bundle, 2 of 2 on both. The first version of that check counted *every* image view and
  found ten on iOS — UIKit's switches, sliders and buttons contain their own — so it asks the
  renderer which handles are the framework's `Image` widgets (`AppleRenderer.kindOf`) and
  the platform whether each holds a picture (`sui_has_image`). Gallery **20/20** on macOS
  and on the iOS simulator; the four checks #31 expected might need adjusting all pass as
  written, each for the reason it states.

- 2026-10-04 — **AppKit's `Column` and `Row` are flipped views** (#28). Top-left origin, as
  on UIKit and in reading order. It matters where a stack is a scroll view's document:
  AppKit keeps an unflipped document's *bottom* edge in view, so the todo screen — a
  vertical `Scroll` taller than its window — opened **48 pt** from its top, the first row
  under the title bar. Measured with a new `sui_scroll_offset` (distance from the top of the
  content, whatever the document's coordinate system): **0 pt** after, on both toolkits;
  UIKit was 0 before too. Stack layout is by constraints, so flipping moves nothing else:
  the 87 existing todo checks pass unchanged, plus the new one (88/88 macOS, 83/83 iOS);
  gallery 19/19 on both. Screenshot confirms the first field is visible. The bug predated
  `AppRoot.pages`: the old `element` path showed it on the same build.

- 2026-10-04 — **Android's navigation container is a `FrameLayout` plus
  `android.transition.Slide`, not Fragments.** `android.app.Fragment` has been deprecated
  since API 28, and the AndroidX one would be a new dependency for an app whose only
  dependency is `scala3-library`. A FrameLayout holding one child per `NavPage`, with the
  top visible and `TransitionManager` animating the change, is current platform API and
  gives what the phase needed: the stack stays alive, and the transition is the platform's
  own. Back was already native, through `OnBackInvokedDispatcher`.
  Worth recording that an earlier check in this session reported
  `android.app.Fragment` as *current* and I repeated that. It was wrong: I had looked only
  for the class-level `Deprecated` attribute, which android.jar does not carry, and not for
  the annotation, which it does. The right command is
  `javap -v | grep -i 'java/lang/Deprecated'`.
  Also: a self-test cannot cache "the screen". With one view per entry, a `View` captured
  once goes stale as soon as the stack changes — and `navigator.reset` replaces the root
  page outright, after which twelve checks were walking a disposed view and reporting an
  empty tree. The root is a thunk now, and "is the screen below still mounted" is asked of
  the *container*, since the top page rightly knows nothing about the one beneath it.

- 2026-10-04 — **GTK4 has no `&` nesting selector, and a toolkit warning nobody reads is not
  a test.** (#30) The renderer built `.cls { color: X; & > * { color: X; } }`; GTK stopped at
  the `&`, discarded the rest of the block, and the rule meant to colour a tinted
  container's children never applied. Fixed by emitting two flat rules —
  `.cls { … } .cls > * { … }` — which GTK4 parses fine. 26 warnings per run of the demo
  before, 0 after.
  The bug dated from the day theming landed and survived because of *how* GTK complains: a
  `Gtk-WARNING` on stderr, after which it carries on with whatever parsed. The self-tests
  grep for `Gtk-CRITICAL`, so the output scrolled past in every run.
  So the renderer now connects `parsing-error` on each `GtkCssProvider` and counts
  failures, exposed as `GtkInspect.cssParseErrors`, and both GTK self-tests assert it is
  zero. A number the renderer keeps is checkable; a warning on stderr is not.
  Useful side effect, found while falsifying: with a handler connected GTK **stops printing
  the warning** and reports through the signal instead. Reintroducing the bug produced 0
  stderr warnings and 20 counted errors with the check failing — so the fix removes the
  noise and makes the failure visible at the same time.

- 2026-10-02 — **On macOS a navigation stack is a sidebar** (#25, decided by the project
  owner). macOS has no push idiom, so `AppRoot.pages` renders as an `NSSplitViewController`:
  the live pages as a source list, the selected page's content beside it, every page kept
  mounted. Choosing an earlier entry is going back to it, reported to `Nav` like any platform
  pop. **With a single page the sidebar is collapsed** — there is nothing to choose between.
  Rejected: staying on `element` (a Mac app would lose the stack) and hand-rolled push/pop
  (an idiom the platform does not have).
- 2026-10-02 — **On iOS `AppRoot.pages` is the host's own `UINavigationController`, and its
  changes share the presentation queue** (#25). One controller per page; the bottom page
  reuses the host's root controller, so `rootView` never leaves the window. A swipe-back or
  the back button pops UIKit's stack without asking, so the navigation delegate reports the
  new depth and the host calls `back()` until `Nav` agrees. UIKit does not apply a stack
  change made mid-transition, and a sheet presented during a push left the push's transition
  unfinished: measured, a push and pop in one turn left the platform two deep while `Nav`
  was one. Deferring on the push's own coordinator was not enough —
  `animate(alongsideTransition:completion:)` returns false and never calls the completion
  when it cannot attach — so every UIKit transition, navigation or presentation, now starts
  only when the previous one has finished. Self-test **87/87** macOS, **82/82** iOS.
  Controls: a platform pop not reported to `Nav` fails "a back gesture by the platform pops
  Nav too"; rebuilding pages on every change fails "the page below is the same mounted view".
- 2026-10-02 — **Pages mount the screen's content, not `NavHost.element`'s wrapper**, so the
  self-test's layout probes now start one level deeper. On macOS the page fills the window,
  where `element`'s `Column` hugged ~197 px and clipped the button row; with the space, the
  row fits and the overflow branch no longer runs. Compared by screenshot on the same build.
  A clipped *top* seen in both is pre-existing, filed as #28.

- 2026-10-02 — **On Apple a context menu is `NSView.menu` and a `UIContextMenuInteraction`,
  with items carried by the tap trampoline** (#19). Each item is a label, an enabled flag and
  an ordinary `sui_void_cb` with its own handle-table id — the alert's shape exactly, so no
  new callback machinery. AppKit needs `autoenablesItems = false` or it decides enablement
  itself and `enabled` is ignored. UIKit's interaction holds its delegate weakly, so the shim
  owns it and drops it in `sui_destroy`; the renderer releases the item ids on replace and on
  destroy. Measured: every row has a menu of the app's two items, a row with a menu still has
  exactly its two children, and the labels are absent from a tree walk (on GTK they are
  present — checked, not assumed). Choosing Delete through `NSMenu`'s own dispatch removes
  that row only; control: dispatching to the first item fails it. Self-test **77/77** macOS,
  **71/71** iOS; UIKit offers no public way to perform a `UIAction`, so the Delete check is
  not run on iOS and the test says so. Lifetime, after review: both shims count live items in
  `init`/`deinit` — exactly two per mounted row once the run loop turns, and a removed row's
  two are freed. AppKit frees them when the autorelease pool drains, not at `sui_destroy`
  (24 live for 5 rows mid-test, then 10). Controls: a UIKit delegate held only weakly reads
  as no menus; a source kept past destroy, or an `NSMenu` kept in a global, stays at 24.

- 2026-10-02 — **In GTK, detaching is releasing: whoever removes a widget owns its teardown.**
  Second time this has cost real debugging, so it is a rule now rather than an anecdote.
  `Sheet` taught it first — `gtk_window_set_child(win, null)` drops the window's only
  reference, so detaching the child *was* destroying it, and `dismiss` had to stop at hiding.
  `AdwNavigationView` is the same shape: `adw_navigation_view_pop` releases the page, which
  destroys the subtree underneath, so calling `Mounted.dispose()` afterwards is a double
  destroy. GTK says nothing at the time; it corrupts quietly and kills an **unrelated**
  widget several operations later. The symptom here was a list losing one row, with the
  destroying call three navigations earlier.
  The rule: after handing a widget to a GTK container that takes ownership, our teardown is
  the `Owner` only — the effects that were driving the widgets the toolkit has taken away.
  Diagnosing it meant asking whether the widget was *destroyed* or *reparented*, which one
  line settled: count the subtree under the whole window, not under the page. Reparented
  keeps the count; destroyed does not.

- 2026-10-02 — **A host's bookkeeping is not evidence about the toolkit.**
  The GTK navigation self-test first asked `GtkApp.pageHandles` — the host's own map — how
  many pages were live. Deliberately breaking the pop logic left that map correct while
  `AdwNavigationView` had silently lost a page, so the check passed twice against code known
  to be wrong. It now asks the container, via
  `adw_navigation_view_get_visible_page`, and the demo stack is three deep because a two-deep
  stack hides an extra pop entirely: Adw refuses to pop its root. A check that cannot
  distinguish the states it is written to distinguish is not a weak check, it is not a check.
- 2026-10-01 — **A navigation container is host-level, not a widget kind — §12.2a's fourth
  answer.** The test is whether every toolkit models the thing as a view you *place*, and
  navigation fails it three ways out of four: `UINavigationController` is a view *controller*
  that owns the screen, `FragmentManager` is a manager rather than a view, and **AppKit has
  no push idiom at all** — a Mac app uses a sidebar or separate windows. Only
  `AdwNavigationView` is a widget. One out of four is not a catalogue entry, so there is no
  `WidgetKind.NavigationStack`.
  Instead `AppRoot` gains `pages: Signal[Seq[NavPage]]`, bottom first, beside the existing
  `element`. A host with a native container pushes and pops real pages; a host without one
  mounts `element` and nothing else, which is today's behaviour. The two are alternative
  renderings of one stack and a host must not mount both. This is what `NavHost`'s own
  doc comment promised: *"a renderer change behind this same API — the app-facing shape does
  not move."*
  `NavPage.content` is built **once per entry id** and returned unchanged afterwards, which
  is the point: a host can mount it once and leave it mounted, so the screen below a push
  keeps its scroll position and in-flight requests. The cache is keyed on the id rather than
  the route because two visits to one route are two screens. Falsified by removing the
  memoisation — exactly the two identity tests fail and nothing else.

- 2026-10-01 — **libadwaita works under Scala Native, and `adw_init()` is not optional.**
  Spiked before designing around it: `com.indoorvivants.gnome:adwaita_native0.5_3` 0.2.6
  compiles, links against `libadwaita-1` and runs, with `adw_navigation_view_new()`
  returning a live pointer. The dependency and the `pkg-config` flags are wired in
  `build.sbt`.
  **Calling any `adw_*` constructor before `adw_init()` segfaults** — `Unhandled signal 11`
  then `free(): invalid size`, with no hint that initialisation is what is missing. Worth
  knowing before it costs someone an afternoon. `adw_init()` is deliberately *not* called
  yet: it installs libadwaita's stylesheet, which changes how every existing widget looks,
  so it belongs in the commit that introduces `AdwNavigationView` rather than one that
  silently restyles the app.
  Why libadwaita at all: GTK core has no navigation container. `GtkStack` gives transitions
  and no back-gesture semantics, and `AdwNavigationView` is what GNOME apps actually use.

- 2026-10-01 — **libadwaita is a dependency of the Linux renderer, and the full apt list is
  in the README.** GTK core has no navigation container: `GtkStack` gives transitions but no
  back-gesture semantics, and `AdwNavigationView` is the thing GNOME apps actually use.
  `libadwaita-1-dev` 1.9.1 and the matching `com.indoorvivants.gnome:adwaita_native0.5_3`
  0.2.6 bindings — the same version as the GTK4 bindings already in the build — make it
  viable without generating bindings by hand.
  The README now lists every Linux package with what it is for and the version it was
  verified against, including two that were implicit before: **`pkg-config`**, which
  `build.sbt` shells out to for GTK's compile *and* link flags, so missing it fails the
  build rather than the link; and `python3`, which `bin/coverage.sh` uses. The `-dev`
  suffixes matter — Scala Native compiles against the C headers, so runtime libraries alone
  do not suffice.
- 2026-09-30 — **The iOS host initialises Scala Native at the top of `main`, before
  `UIApplicationMain`, not in scene setup** (#22). Scala Native's collector records a
  thread's stack base as the address of a local inside its own initialisation and scans
  from the current stack pointer up to it. The host called `ScalaNativeInit` from
  `scene(_:willConnectTo:)`, deep inside UIKit's launch, and every later entry into Scala —
  from the run loop — runs *nearer the true base* (448 bytes higher, measured), outside the
  scanned range. So **objects referenced only from the main thread's stack were invisible to
  the collector**, freed while live, and their memory reused. Measured with `PostProbe`, a
  `postToUi` loop whose posted closure carries a heap canary: on the iOS simulator, **18, 18
  and 14** canary mismatches per 200 000 hops before; **0 in 4 of 4 runs** after. macOS was
  0 throughout — Scala owns `main` there, so the base is right. This is what crashed the
  #18 self-test, and it was latent in every iOS run of this host. Mismatches appeared only
  when a collection was triggered from inside `postToUi`'s own allocation; forcing
  `System.gc()` every hop did not show it, because at that point nothing live was held only
  by the stack. The spike hosts (S1, S3, S6, S8, S9) initialise the same way; see
  `docs/09` §9.6.
- 2026-09-30 — **A `SIMCTL_CHILD_` variable keeps its first value for the whole simulator
  boot session.** Relaunching with a different value — even after uninstalling and
  reinstalling the app — still delivered the first one; rebooting the simulator did not.
  Found because `PostProbe` printed `mode=chain` for every mode. Any iOS measurement that
  varies an environment variable between launches must reboot the simulator between them, or
  it silently measures the wrong thing.
- 2026-09-30 — **Correction to the #4 entry: the Apple self-test was 42/42 on macOS and
  41/41 on iOS, not 43/43 and 42/42.** Counted from the logs (`grep -c '] ok '`) while
  checking #22; the earlier figures were written down rather than counted. The #18 figures
  (68 and 64) were counted and stand.
- 2026-09-30 — **UIKit presentations go through a serial queue in the shim** (#18). UIKit
  presents and dismisses asynchronously even with `animated: false` — the transition ends on
  a later turn of the run loop — and a present or dismiss requested before the previous one
  finished is **dropped**, silently apart from a console warning. `Show(flag)` toggled twice
  in one turn does exactly that. The self-test found it: a sheet opened and closed in one
  turn stayed on screen (the same `UINavigationController` was still presented a turn later),
  and the alert opened next was configured but never shown. Each change now starts only when
  the previous one's completion has run; the handle's own state updates at once, so the
  renderer never waits on UIKit. AppKit's `beginSheet`/`endSheet` needed nothing.
- 2026-09-30 — **"Is it on screen?" is asked of the platform, not the renderer.** The first
  version of the sheet check passed on the renderer's own list of presented handles while
  UIKit was still showing the sheet — a check that could not fail, which is what #18 warned
  about. `sui_presented_count` reads the window's attached sheets (AppKit) or the presented
  view-controller chain (UIKit), and the self-test waits for it, bounded by time and paced a
  frame at a time (`sui_run_on_main_after`), because a few hundred back-to-back run-loop
  turns can all fall inside one frame.
- 2026-09-30 — **`OnDismiss` on Apple, per platform.** An `NSAlert` has no way to close
  without a choice: Escape activates the button marked as cancel, so on AppKit a cancel action
  arrives as a *choice* (as on GTK). A `UIAlertController` in `.alert` style cannot be
  dismissed by tapping outside either. So **an Apple alert never reports `OnDismiss`**. A
  sheet does: Escape on an AppKit sheet (`cancelOperation`) and a swipe down on a UIKit one
  (`presentationControllerDidDismiss`, which UIKit calls only for a user dismissal). Neither
  gesture is driven by the self-test; they are wired, not measured. An app-initiated dismiss
  reports nothing on any of the four, and that *is* checked. **No lifetime hazard of the GTK
  kind:** every Apple handle holds its own retain until `sui_destroy`, so a presentation
  dropping its reference in `sui_dismiss` cannot free content the reconciler still holds.
- 2026-09-30 — **A context menu is a *prop*, not a widget kind.** All four toolkits model it
  as something a view has — `NSView.menu`, `UIContextMenuInteraction`, a `GtkPopover`
  parented to the widget, a `PopupMenu` anchored at the view — so `Prop.ContextMenu` and a
  `.contextMenu(...)` modifier, adding no node to the tree. This is the §12.2a question
  asked a third time, after `Radio`/`Stepper` and `TabView`, and the first time the answer
  was "it belongs in the catalogue, but not as a widget".
  **Which gesture opens it is the platform's business**: secondary click on GTK, long press
  on Android. An app that hard-coded either would be wrong on the other, so the API names
  neither.
  GTK uses a popover of flat buttons with the `menu` style class rather than a
  `GtkPopoverMenu`, because the latter is driven by a `GMenuModel` addressing `GAction`s by
  *name* through an action group — an indirection that does not fit an API where each item
  carries its own closure.
- 2026-09-30 — **`Provide` scopes a theme to a subtree, and takes its child by name.** Roles
  resolve to colours when an element is *built*, not when it is rendered, so a `Provide` is
  the theme in scope during construction — which means a by-value child would be evaluated
  by the caller *before* `Provide` ran, outside the scope it is meant to be inside. That is
  exactly how it first failed, and the symptom was worth noting: the **static** cases failed
  while the **region** cases passed, because regions build later and went through the
  reconciler's scope correctly.
  The second half is that a region must rebuild under the theme in scope where it was
  *declared*, not whatever is active when its signal fires. `RegionSlot` captures
  `Theme.active` once at construction — the only moment an enclosing `Provide` is still on
  the stack — so a row appended to a themed list an hour later is still themed.
  `Provide` contributes no node; the renderers never see it.

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
- 2026-09-30 — **`Sheet` is presented *and* a container, and needed no reconciler change.**
  That is the evidence `WidgetKind.presented` is a real distinction rather than a special
  case shaped around `Alert`: the children mount into the sheet's own handle by the ordinary
  `insertAfter` path, and only the attachment differs.
  Two platform notes. GTK: the content is attached through `gtk_window_set_child`, so it is
  *detached* the same way — and that call drops the window's only reference, so it is also
  the release. Doing it in `dismiss` freed the widget before the reconciler destroyed it and
  GTK said so (`assertion 'GTK_IS_WIDGET (widget)' failed`); `dismiss` now only hides, and
  `destroy` owns the teardown, which is the rule the `Scroll` case already encoded.
  Android: built on `AlertDialog.Builder().setView(...)`, not a plain `Dialog` — a plain
  `Dialog` reserves a title band under current themes and then draws nothing in it.
- 2026-09-30 — **A presented widget is in its own window, so no in-process view walk can see
  it.** Both self-tests can check that the screen behind is untouched and that the subtree's
  signals still drive the model; they cannot check what the sheet *shows*. That belongs at
  the renderer boundary, in a unit test. It matters because both renderers routed
  `Prop.Text` to a title only when the kind was `Alert`, so a `Sheet`'s title silently went
  nowhere — and every automated check passed, because not one of them could observe it. A
  screenshot caught it. Where a class of assertion is structurally unavailable, say so and
  move the assertion rather than leaving a check that cannot fail.
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
