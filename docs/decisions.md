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
| Working name        | `scala-ui` (repo), root package **`scalaui`**. Renaming is a product question (Q14) — do not bikeshed.                                                                                                                                                       |
| Licence             | Apache-2.0. Add `LICENSE` at M0, not during spikes.                                                                                                                                                                                                          |
| Repo layout         | `docs/` plan · `spikes/sN-<slug>/` one self-contained sbt (or Gradle/Xcode) project per spike, each with `BRIEF.md` and `REPORT.md` · `modules/` framework code, **empty until M0** · `shims/` Swift/C++ shims (M1+) · `tooling/` plugins/CLI (M5).          |
| Spike independence  | Spikes never depend on each other's code. Copying a file between spikes is allowed; importing a spike as a dependency is not.                                                                                                                                |
| Module naming (M0+) | `scala-ui-core`, `scala-ui-signals`, `scala-ui-renderer-api`, `scala-ui-effect-api`, `scala-ui-effect-zio`, `scala-ui-renderer-<platform>`, `scala-ui-layout-yoga`. Group id decided at M0.                                                                  |
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
  `Shim+AppKit.swift` and `Shim+UIKit.swift` against one `scalaui_apple.h`; both hosts run
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
