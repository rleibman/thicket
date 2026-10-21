# 05 — Tests, coverage and build health

Date 2026-10-20, Linux box (16 cores, 45 GiB), HEAD `9c98d28` on `main`, clean tree. sbt-mcp
server was down (ECONNREFUSED), so everything went through the sbt CLI. No repository files
changed: `git status --short` shows only the untracked `.claude/`, before and after.
Raw logs are next to this file in `scratchpad/review/`: `testFull.log` (the OOM run),
`testFull2.log`, `fmt.log`, `fmt2.log`, `unformatted.txt`, `compile-rest*.log`,
`gallery-*.log`, `counter-selftest.log`.

## 1. Test run

### 1a. `sbt --error testFull` FAILS at the default heap (build-health finding #1)

```
sbt --error testFull            -> EXIT=1 after 5m59s
java.lang.OutOfMemoryError: Java heap space
[error] ## Exception when compiling 25 sources to .../native0.5/.../thicket-core/test-classes
[error] ## Exception when compiling 25 sources to .../sjs1/.../thicket-core/test-classes
```

The sbt launcher (sdkman) starts with `-Xms1024m -Xmx1024m`. The repo has no `.jvmopts` or
`.sbtopts`, and there is no `~/.sbtopts` or `/etc/sbt/sbtopts`. coreJS and coreNative test
compiles run in parallel and exhaust 1 GiB. Before the OOM, the JVM suites had all passed
(shimGen 15, sbtThicket 9, signalsJVM 21, effectZioJVM 13, coreJVM 154).

**Fix:** a checked-in `.jvmopts` (`-Xmx4G` or more) or `.sbtopts` (`-J-Xmx4G`). As things
stand, the command CLAUDE.md and the workflow notes tell agents to run fails on a clean
checkout with default settings. That probably explains the earlier "last known" numbers,
which only list JVM projects.

### 1b. `sbt -J-Xmx6G --error testFull` PASSES, 1m22s wall time

| Project | Tests | Result | Note |
|---|---|---|---|
| shimGen | 15 | pass | zio-test summary line |
| sbtThicket | 9 | pass | |
| signalsJVM | 21 | pass | |
| signalsJS | 19 | pass | no ThreadGuardSpec (as documented) |
| signalsNative | 21 | pass | |
| coreJVM | 154 | pass | |
| coreJS | 154 | pass | |
| coreNative | 154 | pass | |
| effectZioJVM | 13 | pass | |
| effectZioNative | **0** | none | no `native/src/test`; all tests live in `jvm/src/test` |
| rendererApi* | 0 | none | pure types; reached through core |
| **Total** | **560** | 0 failed, 0 ignored | |

JS and Native print no zio-test summary line. I got their counts by counting `+` leaf
lines in each block of `testFull2.log`. Each core block has the same 154 leaves as the
JVM block.

No skipped or flaky markers: a grep for `TestAspect.ignore|flaky|retries|nonFlaky|jvmOnly|
exceptJS|exceptNative` and for `Thread.sleep` / `TestClock` in test sources returns nothing.

### 1c. Formatting

- `sbt -J-Xmx4G --error scalafmtCheckAll` -> EXIT=0. This only covers the **aggregated**
  projects (signals, rendererApi, core, effectZio, shimGen, sbtThicket).
- The aggregate leaves out every renderer and every example. `rendererGtk/scalafmtCheckAll`
  fails with "8 files must be formatted", and sbt stops on that first failure.
- A read-only full list from the CLI (`scalafmt --list --config .scalafmt.conf <tracked
  .scala files>`, no files written) finds **26 unformatted files**. I left out `Shim.scala`:
  it is in `excludeFilters` and showed up only because I passed it explicitly.
  - `modules/renderer-gtk`: 8 (GtkRenderer, GtkApp, GtkInspect, GtkTabs, Handles, UrlOpener, WaylandFocus, +1)
  - `modules/renderer-android`: 3 (AndroidRenderer, AndroidTabs, IconDrawable)
  - `modules/renderer-apple`: 3 (AppleRenderer, AppleInspect, Handles)
  - `examples/*`: 12 (gallery 6, counter-gtk 2, shared 2, todo-android 2)
  - The exact list is in `scratchpad/review/unformatted.txt`.
- **Root cause:** the rule "run `sbt scalafmtAll` before every commit" formats only what
  `root` aggregates. Renderer and example code is never formatted, even by agents that
  follow the rule. The fix is either to add the Linux-buildable projects to a format
  alias, or to run `scalafmt` (CLI) over `git ls-files '*.scala'`.
- Minor: `.scalafmt.conf` sets `trailingCommas` twice (`always`, then `never`). The last
  one wins, so the first line does nothing and misleads readers.

### 1d. Compiler warnings

Everything uses `-Werror -Wunused:all -deprecation -feature`, so any warning would fail
the build. There is one suppression in the repo:
`examples/todo-android/.../MainActivity.scala:201 @nowarn("cat=deprecation")`. The run
showed no warnings. SLF4J NOP-logger noise appears at sbt startup and is harmless.

### 1e. Projects outside the aggregate (none of them compiled by `testFull`)

`sbt -J-Xmx6G --error "rendererGtk/compile; rendererAndroid/compile; rendererApple/compile;
counterGtk/compile; galleryGtk/compile; galleryAndroid/compile; todoAndroid/compile;
galleryGtk/nativeLink"` -> EXIT=0, 30s.

Adding `galleryMacos/compile` (and the other Apple apps) **fails on Linux**:
`build-shim.sh: xcrun: command not found ... (rendererApple / Compile / packageBin /
mappings)`. sbt 2 builds downstream projects against the packaged jar, and
`rendererApple`'s `packageBin` mappings run the Swift shim build. So on Linux the Scala
sources of `galleryMacos`, `galleryIos`, `todoMacos` and `todoIos` cannot even be
type-checked, although `rendererApple` compiles. That is the same blind spot #49 fell
into, now for the Scala side. A fix: make the shim mapping conditional (for example,
skip it when `xcrun` is missing), or set `exportJars := false` on `rendererApple`.

## 2. GTK self-tests (real toolkit, Wayland session)

| Binary | Command | Result |
|---|---|---|
| gallery-gtk (existing binary, 12:49) | `timeout 60 env THICKET_GALLERY_SELFTEST=1 target/out/native0.5/scala-3.9.0/gallery-gtk/gallery-gtk` | **72/72**, `ALL CHECKS PASSED`, exit 124 |
| gallery-gtk (relinked 16:54) | same, run 3 times | 72/72 each time, exit 124 |
| counter-gtk (binary was from **Oct 8**, relinked) | `timeout 90 env THICKET_SELFTEST=1 .../counter-gtk/counter-gtk` | **69/69**, `ALL CHECKS PASSED`, exit 124 |

- **Intermittent `Gtk-CRITICAL`:** in 1 of 4 gallery runs (the first one, while sbt was
  loading the machine), three lines appeared *after* `ALL CHECKS PASSED`:
  `gtk_widget_unmap / gtk_widget_unrealize / gtk_widget_unparent: assertion
  'GTK_IS_WIDGET (widget)' failed`. That points to a widget that had already been freed
  and was then unparented. The likely place is a removal step late in the self-test (the
  Grid row or TabView tab removal, or the Wayland-focus workaround from #71). The
  self-test does not detect it.
- **The self-tests cannot gate anything.** The process never exits, so the exit code is
  always 124 whether checks pass or fail. Only a grep on stdout can tell. Apple has
  `verify-apple.sh` to wrap this; Linux has no wrapper. Fix: call `exit(failures)` after
  the self-test, behind an env flag.

## 3. Coverage map

### Unit tests (TestRenderer, zio-test)

- Every public DSL constructor in `thicket.core` appears in at least one spec, except
  `Divider` (no test), `Switch` (only reached indirectly through `RemoteData.apply`), and
  `Attr` / `Static` / `Reactive` / `Entry` (internal ADT names, covered through use).
- All 37 `Prop` cases are mentioned in core tests. `TestRenderer` only records props, so a
  test like that proves the reconciler passes a prop through, not that a renderer honours
  it. docs/12 §12.10 says so itself.
- `SvgPath` and `MaterialSymbols` live in `renderer-api` and are tested through
  `core/IconSpec`. Coverage instrumentation is turned off for `renderer-api`, so this
  parser is real logic that appears in no coverage number.
- **effect-zio is the weakest unit-tested module** (docs/12 §12.9 says 69.44%, the lowest).
  Nothing tests:
  - `UiRuntime.fromLayer`, including a layer that fails to build
  - `ErrorPresenter.into` and `ErrorPresenter.printing`
  - `launch`/`asSignal` with a *defect* (`ZIO.die`) or an interruption, as opposed to a
    typed failure
  - a `ZStream` that *fails* partway through, in `asSignal`/`asRemoteSignal`
- **effectZioNative runs 0 tests.** docs/12 says the bridge "runs on iOS". That claim
  rests on manual app runs, and nothing here checks it, because the specs sit in
  `jvm/src/test` (TestUiThread uses JVM threads). Moving the spec to shared plus a
  per-platform `TestUiThread` would let Native run it.

### Renderers: covered only by self-tests, or not at all

| Renderer | JVM/unit tests | Self-test available on this box | Notes |
|---|---|---|---|
| GTK (`renderer-gtk`, ~2.9k lines) | none | gallery 72, counter 69, both pass | not in `root` aggregate, not in CI |
| Android (`renderer-android`, ~1.4k lines) | none | none here (no emulator). `todo-android` SelfTest has 86 `check(` calls | **The Android gallery has no self-test.** `GalleryActivity` reads no selftest flag, but `examples/gallery/README.md` says "Every host takes the same switch" |
| Apple (`renderer-apple`) | none (ABI checked by shimGen) | Mac only, via `verify-apple.sh` | |

Pure bookkeeping that could be unit-tested without a toolkit, and currently is not:
`GtkTabs`/`AndroidTabs` selection preservation on insert/remove (two copies of the same
index logic) and the `Grid` cell-placement arithmetic. Both are tested only through
self-tests on a real display.

### What the GTK gallery self-test checks only by section heading

`Image` (three ContentFit modes), `Divider`, `Spacer`/`Grow`, and `Checkbox` are verified
only by "section is on screen: ..." (`GalleryGtk.scala:64-82`). The Apple gallery
self-test reads the images back (`GallerySelfTest.scala`, 10 Image references). That
check found the iOS bug in #31, and GTK has no equivalent. Alert and Sheet appear in the
gallery only as "not presented before asked". counter-gtk does present and dismiss them.

### docs/12 claims vs. measurement (doc drift)

- §12.8 table: `coreJVM/JS/Native 73`, `shimGen 10`. Measured **154** and **15**.
  `sbtThicket` (9) is missing from the table.
- §12.9: "42 checks on GTK, 35 on Android, 27 and 26 on macOS and iOS". Measured GTK
  gallery 72 and counter 69. Coverage figures are dated 2026-09-29 and predate the
  #61–#70 work. I did **not** re-measure coverage: `bin/coverage.sh` deletes
  `~/.cache/sbt/v2` and `target/out`, which was outside what I was asked to change.
- §12.4 says "all 22 props" and "All thirty are honoured". §12.1 says 37, and
  `enum Prop` has **37** cases.
- §12.1/§12.2: IconButton and TabView are "done" on GTK and Android. GTK backs this with
  real self-test checks (gallery lines 204–257). Android backs it only with `todo-android`
  SelfTest sections (`SelfTest.scala:328` TabView), which I could not run here.

## 4. CI

`.github/workflows/apple.yml` is the **only** workflow. It runs on `push` and
`workflow_dispatch`, on a self-hosted Mac, and calls `bin/verify-apple.sh`, which runs
`shimGen/testFull`, the four Apple nativeLinks, and the macOS and iOS self-tests.

These things run in **no** CI at all:
- signals, core and effect-zio tests on any backend (the 545 tests other than shimGen's
  15)
- `scalafmtCheck` (and a CI check would also need to cover non-aggregated projects; see 1c)
- compiling `rendererGtk`, `rendererAndroid` or any example. They sit outside the aggregate,
  so a broken GTK renderer passes `testFull`
- the coverage ratchet (`coverageFailOnMinimum` takes effect only when someone runs
  `bin/coverage.sh` by hand)
- the GTK self-tests (they would need `xvfb`/`weston --headless` and an exit code, see §2)
- `bin/verify-getting-started.sh`

Suggested minimal Linux job on `ubuntu-latest`:
`sbt -J-Xmx4G --error "testFull; scalafmtCheckAll; rendererGtk/compile; rendererAndroid/compile; galleryGtk/compile; counterGtk/compile"`.
`rendererAndroid` needs `lib/android.jar` (`setup.sh`), so that step needs the SDK or a
cached jar. A second job would run the GTK self-tests under a headless compositor once
they can exit with a status.

## 5. Prioritised recommendations

1. Check in `.jvmopts`/`.sbtopts` with a larger heap. `testFull` currently OOMs on a clean
   default setup. (Critical: the documented test command fails.)
2. Add a Linux CI workflow (see §4). Today 545 of 560 tests and the whole GTK/Android
   compile run only when someone remembers.
3. Make the self-tests exit with their failure count, so a script or CI can gate on them.
4. Format the 26 drifted files, and widen the format command to cover non-aggregated
   projects.
5. Add a self-test to the Android gallery (the README says one exists), and image and
   divider read-back checks to the GTK gallery.
6. effect-zio: tests for `fromLayer`, defects and interruption, stream failure,
   `ErrorPresenter.into`/`printing`, and get them running on Native.
7. Unit-test the tab-selection and grid-placement index logic. Consider extracting it into
   `core` so GTK and Android share one tested copy.
8. Investigate the intermittent `Gtk-CRITICAL ... GTK_IS_WIDGET` on teardown.
9. Let Apple app Scala sources type-check on Linux (`exportJars := false` or a
   conditional shim mapping).
10. Refresh docs/12 §12.4/§12.8/§12.9 numbers (append-only rules permitting).
