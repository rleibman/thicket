# 9. Open questions

Grouped by who can answer them. Each links to the spike or milestone that resolves it.

## 9.1 Toolchain (answered by spikes)

1. Does Scala Native's runtime (Immix GC, threads, javalib) work on iOS device and
   simulator? Which target triples? — **S1**
2. Does Scala Native's GC tolerate a foreign main thread calling in, and can callback
   contexts be kept alive without pinning? — **S1, S3**
3. Scala 3 on ART: `LazyVals`/`Unsafe`/`VarHandle` behaviour with min SDK 26; APK
   delta after R8; AGP 9 compatibility. — **S2**
4. sbt/mill-built AAR vs a Gradle plugin running Zinc — which gives Android Studio
   users a normal experience? — **S2**
5. Scala Native debug link time for a realistic app on a laptop; is `-O0` + no LTO
   under 15 s? Does that make the desktop host a viable inner loop? — **M1**
6. Yoga on JVM: RN's `com.facebook.yoga` artefact vs our own JNI build. — **S4**
7. Can sn-bindgen consume our shim header directly, including function-pointer
   typedefs and opaque handles? — **S3**

## 9.2 Design (answered by prototyping in the dev canvas, M0)

8. Modifiers as extension methods vs named parameters vs both. Ergonomics test with
   external developers.
9. Static `if` vs `Show(signal)` — how to make the non-reactive branch a compile-time
   warning rather than a silent bug. (Idea: `Element` constructors require a
   `Tracking` context in reactive positions.)
10. Auto-marshal `Var.set` to the UI thread, or fail fast? Plan says fail fast in
    dev; confirm with users.
11. Should the optional MVU layer (A-07) ship in v1 or wait for demand?
12. How much of Yoga's Flexbox to expose (percentages, aspect ratio, gap, wrap) vs
    a smaller, opinionated API like SwiftUI's stacks.
    13a. Effect bridge: is `ComponentScope` a ZIO `Scope` directly, or a neutral scope
    the ZIO bridge wraps? (Neutral keeps `effect-api` agnostic; direct is simpler
    for the ZIO user.) Decide when the cats-effect bridge is attempted (M4).
    13b. Should `UiRuntime[R]` be a `given`, or should apps fix one `AppEnv` and hide `R`
    behind a type alias? Ergonomics test in M0.
    13c. ZIO runtime cold-start cost on a phone vs the N-01 budget; lazy `ZLayer` boot
    after first frame? — **S8, M2**
13. Navigation model: declarative route table vs imperative push/pop mirroring
    the platform. (Compose Navigation and SwiftUI NavigationStack chose
    "declarative stack of values"; likely follow.)

## 9.3 Product & community

14. Name, governance (Scala Center? Typelevel? independent?), licence — Apache-2.0
    assumed.
15. Funding/staffing: this needs 2–4 sustained contributors for ~2 years. Grants,
    sponsorship, or a company anchoring it?
16. Which sample app makes the best showcase? A client for an existing ZIO/Caliban
    server (e.g. the meal-planner) demonstrates the code-sharing story.
17. How do we avoid the Slinky/sri fate (single maintainer drifts away)? Governance
    plus vendored bindings plus a documented renderer contract are the plan; is it
    enough?

## 9.3a Answered by S5 (2026-09-18)

- **Signals are feasible and fast.** 283 lines of shared Scala 3, no macros, no dependencies;
  76.8 / 418.9 / 219.3 ns per node update on JVM / Scala.js / Scala Native, against a 1 000 ns
  budget. Glitch-freedom property-tested over random DAGs on all three backends.
- **New limitation: validation is recursive**, so a dependency chain deeper than ~4 000 (JVM,
  Scala.js) overflows the stack; Scala Native survived > 16 000. UI trees are tens deep, so this
  is not a blocker, but if the reconciler ever builds deep derived chains (e.g. one computed per
  list row *chained* rather than fanned out), it becomes one. Mitigation if needed: an iterative
  validator with an explicit work stack (~80 lines, harder to read). **Decide at M4** when list
  virtualisation lands, not before.
- **New ergonomic constraint: `computed`/`effect`/`map`/`zip` require `(using Owner)`** to avoid
  leaks in a design with no weak references. This ripples into the element DSL: every component
  function takes an implicit lifetime. Confirm it stays invisible in app code at M0.
- **`EventStream` is not needed in the signals module**, but UI events do need *something*: a tap
  has no resting value, and modelling it as a counter `Var` is an anti-pattern. Recommendation is
  to let each effect bridge expose events as its own stream type (`ZStream` for ZIO), consistent
  with §7.13's decision to keep streams out of the bridge contract. **Confirm at M1.**
- **Scala Native link time** for a small library: 4.3 s debug, 13.6 s releaseFast (Linux). First
  datum for question 5; re-measure on a real app at M1.

## 9.3b Answered by S4 (2026-09-18)

- **Yoga works from Scala Native and is far faster than needed**: 1 001 nodes lay out in
  **0.344 ms** against a 2 ms target (~2% of a 16.6 ms frame). The Flexbox-subset fallback is
  not needed. sn-bindgen consumed Yoga's header whole — function-pointer typedefs, opaque
  handles, enums, by-value structs — answering question 7 affirmatively (1 795 lines generated
  from one `Binding`).
- **NEW HIGH-PRIORITY RISK — Scala Native cannot return a small struct by value from a
  callback.** Yoga's measure function returns `YGSize{float,float}` by value; the Scala
  callback is invoked but the caller reads shifted fields (`222.0 x 0.0` for `{111, 222}`),
  **silently**, with no crash. A 25-line C trampoline that converts the return into an
  out-parameter fixes it completely and is implemented in `spikes/s4-yoga`.
  **Implication for S3/M1:** `CGSize`, `CGRect` and `CGPoint` are returned by value all over
  UIKit/AppKit. S3 must test this explicitly, and the Swift shim should use out-parameters as
  a blanket rule rather than rediscovering it per API. Added to the `docs/04` §4.8 register.
- **The handle-table design in §7.6 is mandatory, not a preference.** Scala Native rejects a
  `CFuncPtr` closing over local state at *compile* time, so every C callback must be a static
  function plus explicit context.
- **Question 6 answered (Yoga on the JVM).** `com.facebook.yoga:yoga` is an Android **AAR**
  (`packaging=pom`, `-debug`/`-release` classifiers, depends on SoLoader) — there is no JVM jar,
  so a desktop JVM cannot use it. It ships `arm64-v8a`, `armeabi-v7a`, `x86` but **no x86_64**,
  so the default x86_64 emulator lacks the native library. Our own JNI build was deliberately
  not attempted: the only JVM target is Android. **Decide Android packaging in S2/M2**
  (ship the AAR and require arm64, or build all four ABIs with the NDK plus a JNI shim).
- **sn-bindgen toolchain risk (S-02).** The prebuilt binary is linked against
  `libclang-17.so.17` while Ubuntu ships 20/21, failing with a bare exit 127. A user-local
  symlink works, but CI must pin the binary and its libclang deliberately.

## 9.3c Answered by S2 (2026-09-18)

- **Scala 3 works on Android and costs ~74 KB of APK** (102 230 B vs a 26 910 B Kotlin twin)
  against a 4 MB budget, with 1 783 dex methods. Cold start **457 ms** median (budget 500 ms),
  but **1.40× the Kotlin twin** — meets the absolute bar, misses "within 20%". Re-measure on a
  physical device at M2 and try Baseline Profiles before accepting it as inherent.
- **Three build settings are mandatory and none is discoverable.** Two fail at *runtime* with
  errors naming no Scala concept, so the framework's generated Gradle shell must apply them:
  1. `android.enableR8.fullMode=false` — full mode (AGP 8+ default) strips lazy-val backing
     fields even when `-keepclassmembers` says otherwise, crashing inside `scala.math.BigDecimal$`.
  2. `-keepclassmembers class ** { *** *$lzy*; }` — `lazy val` resolves its field by *name* via
     `MethodHandles.findVarHandle`. `-keepclassmembernames` is not enough (stops renaming, not removal).
  3. `packaging { resources { excludes += "**/*.tasty" } }` — `scala3-library` ships ~7.5 MB of
     TASTy as jar resources that nothing strips. Without it the APK is **3.89 MB**; with it, 81 KB.
- **Question 4 answered (sbt-built AAR vs Gradle `scala` plugin).** The Gradle `scala` plugin
  **cannot** coexist with AGP — it applies `JavaPlugin`, which collides on the `implementation`
  configuration. So sbt/mill builds the artifact and a generated Gradle project consumes it, as
  §7.9 proposed. Verified, not assumed.
- **Scala 3.9's minimum `-java-output-version` is 17** (8, 9, 11 and 16 are all rejected);
  emitted bytecode is major 61. Fine at minSdk 26 with AGP 9, no core-library desugaring needed.
- **Every `lazy val` the framework ships costs a reflective `findVarHandle` at class-init** —
  both the crash risk above and a cold-start cost. Prefer avoiding them in framework code.
- **AGP 9 rejects `org.jetbrains.kotlin.android`** (Kotlin support is built in), and **sbt 2
  cannot put a `File` on a classpath** (`unmanagedJars` needs `HashedVirtualFileRef`; use the
  `lib/` convention). sbt 2 artifacts are symlinks into a CAS — scripts must `readlink -f`.

## 9.3d Answered by S7 (2026-09-18)

GTK4 works from Scala Native with the published bindings (no regeneration needed):
~95 ms to a presented window, 2.5 MB releaseFast binary, 5.7 s link. The valuable output is
**four defects in the renderer contract of §7.4**, found by making it meet a real toolkit:

1. **`setFrame` is not universally expressible.** A `GtkBox` positions its own children;
   honouring a Yoga-computed rectangle would require `GtkFixed` everywhere, discarding GTK's
   own sizing, RTL and baseline alignment. The Yoga-box-vs-native-container distinction cannot
   be per-`WidgetKind` as written — the renderer must *declare* it
   (`layoutMode(kind): FrameBased | ToolkitManaged`), and the reconciler must drive both.
   **This tension exists on every native toolkit; GTK is the easiest one.**
2. **`insertChild(parent, child, index)`** has no GTK primitive. Specify it by *preceding
   sibling* instead of index — every toolkit can express that.
3. **`measure` must return min *and* natural size.** GTK (and CSS min-content/max-content)
   depend on the distinction; one `MeasuredSize` loses it.
4. **Name collisions**: a contract-level `Size` is shadowed by `scalanative.unsafe.Size`.
   Audit `renderer-api` for `Size`, `Rect`, `Point`, `Tag`, `Zone` before M0.

Also: **accessibility is free on GTK** (button role=3/BUTTON, label role=22/LABEL, unprompted),
the **handle table works** on a second toolkit, and **`Long` ⇄ `Ptr` needs
`Intrinsics.castLongToRawPtr`** — `id.asInstanceOf[Ptr[Byte]]` compiles and throws at runtime,
the same "type-checks, fails later" family as S4's struct-return bug.

**Sequencing suggestion:** GTK is the cheapest renderer to build, so iterate the contract there
before committing to the Apple shim — consider doing M3's contract work before M2, while
keeping M1 (iOS) first for toolchain risk.

## 9.3e Answered by S1 (2026-09-19)

**Scala Native 0.5.12 runs on iOS** (simulator, arm64): runtime starts in ~0.6 ms, 779 M
objects allocated over 10 minutes with RSS flat at 13.53 MB, threads correct, Scala→C
callbacks work, 1.90 MB stripped app binary. The `arm64-apple-ios17.0` *device* triple also
links, though nothing ran it. Full numbers in `spikes/s1-native-ios/REPORT.md`.

Three things the plan has to absorb:

1. **Scala code must never be called from a GCD queue.** It segfaults in `Allocator_Alloc`:
   Scala Native's GC keeps per-thread allocator state and only knows threads it created, and
   0.5.12 has **no API to attach an existing foreign thread** (`ScalaNativeGC.h` offers only
   `scalanative_GC_pthread_create`, which *creates* a registered thread). The main thread and
   Scala-created `java.lang.Thread`s are fine. This constrains every Apple renderer and, more
   sharply, **S8**: ZIO's executor must be backed by Scala-created threads, and
   `ZIO.attemptBlocking`'s pool must be checked against this before use.

2. **`java.time`, `java.text` and `java.util.Locale` do not exist in Scala Native's javalib.**
   Not an iOS issue — it would fail identically on Linux. Any `scala-ui-core` API exposing
   dates/times needs a decision at M0: ship a `java.time` subset, abstract the clock behind a
   platform interface, or depend on a cross-published date-time library. Related and worse:
   **these gaps compile cleanly and only fail at `nativeLink`**, so CI must run `nativeLink`
   for every module, not just `compile`, or they reach `main` unnoticed.

3. **immix is the only GC available on iOS.** `boehm` needs `gc/gc.h` and `commix` needs
   `sys/posix_sem.h`; neither is in the iOS SDK. `none` links but leaks by design (4 GB in
   10 s). There is no fallback, so M1 should re-verify immix on a physical device early.

Also: iOS builds need `scala.scalanative.meta.linktimeinfo.target.os = "darwin"` set as a
linktime property or **`java.lang.Thread` does not link at all** — an upstream Scala Native
bug where javalib's `LinktimeInfo.isMac` rejects the `ios` OS while the toolchain's own
`Config.targetsMac` accepts it. Same family as S4's struct-return bug and S7's `Ptr` cast:
**it type-checks and fails later.** That is now three of four Apple/native spikes hitting the
same failure mode; it is worth treating as a standing expectation rather than a coincidence.

## 9.3f Answered by S3 (2026-09-19)

**The Scala-core-drives-UIKit-through-a-Swift-shim design works and is fast.** Tap round
trip **433 ns** against N-05's 5 µs budget, no leak over 100k taps (RSS +0.06 MB), real
UIControl event dispatch reaching a Scala closure, and background-thread → main-thread
updates working. sn-bindgen consumed the C header completely. Full numbers in
`spikes/s3-swift-shim/REPORT.md`.

Four things for the design docs:

1. **A foreign event loop deadlocks the GC unless Scala yields Managed state.** Scala Native
   stops the world by waiting for every Managed thread to reach a safepoint; the UIKit main
   thread calls `ScalaNativeInit`, returns to `CFRunLoop` and parks in `mach_msg` — Managed,
   in native code, polling nothing. The first GC from any other Scala thread hangs, then
   aborts. Fix: `scalanative_GC_set_mutator_thread_state(Unmanaged)` on every return into the
   host loop, Managed on re-entry, via one guarded trampoline. **This is not iOS-specific** —
   GTK, Win32 and AppKit have the same shape, and S7 escaped it only by never allocating off
   the main thread.

2. **`@blocking` is the wrong default on shim externs.** Measured: annotating all externs
   doubles `sui_label_set_text` (216 → 444 ns) and the soak passes identically without it,
   because UIKit calls return far inside the 10 s safepoint timeout. Reserve it for calls that
   can genuinely block — I/O, modal presentation.

3. **The real cost is string encoding, not the ABI.** Crossing into Swift costs 216 ns;
   `Zone` + `toCString` per call costs 3420 ns — **16×** more. Renderer property paths need a
   reusable encode buffer or interning, and per-call `Zone` allocation is the wrong shape.

4. **S4's struct-by-value bug reproduces on arm64 and is worse** — `0.0 x 0.0` rather than
   x86-64's shifted `222.0 x 0.0`. Same silent wrong answer, different wrongness per ABI. The
   "no structs by value across a Scala callback" rule is confirmed on the architecture that
   ships, and flattening `CGRect` to four doubles is vindicated.

Shim cost for §7.10 sizing: **~5.8 non-comment lines of Swift per exported function** (21
functions in 121 lines). The v1 catalogue at ~6 functions per component ≈ 240 functions ≈
1,400 lines of repetitive Swift that must track the C header and the Scala bindings —
**a strong argument for generating all three from one widget description.**

Also: `@_cdecl` collides with its own C declaration, so the header must be split into types
(imported by Swift) and functions (read by sn-bindgen only); and **iOS 27 traps at launch on
apps that do not adopt the `UIScene` lifecycle**, so the app template must be scene-based.

## 9.3g Answered by S8 (2026-09-19)

**ZIO 2.1.26 runs on Scala Native on iOS, and runs well.** Two independent 10-minute runs:
`Runtime.default` init **1.2 ms**, ZIO adds **+3.25 MB RSS**, **37,500/37,500 ticks delivered
(100.0%)** driving UI updates through a main-thread executor, **2.1% CPU**, fibre checksum
matching an independently computed value, interruption in 0.06 ms, no crash and no GC
warning. Full numbers in `spikes/s8-zio-ios/REPORT.md`.

1. **ZIO needs a `java.time` polyfill on Native.** `zio.Duration` *is* `java.time.Duration`
   and Scala Native has no `java.time` (§9.3e). ZIO's Native artefacts do not supply a
   substitute, so every downstream build must add
   `io.github.cquiroz::scala-java-time` (2.7.0). Without it ZIO does not link at all.

2. **`releaseFast` + `LTO.full` is what makes the size budget work.** LTO off: 7.73 MB
   stripped, over N-03's 6 MB. `LTO.full`: **5.75 MB**, under it, with every test still
   passing. Two gotchas — `LTO.full` needs `-lc++` at the final link (Scala Native's
   `ExceptionWrapper` pulls in `std::exception`, invisible until LTO merges objects), and
   Scala Native's `Validator` warns `LTO.thin` is unstable on Mac, so use `full`.

3. **`ZStream.tick(16.millis)` is not 60 Hz.** Every tick is delivered, but it is a
   fixed-*delay* schedule, so the period is 16 ms plus the body's cost: 37,500 ticks took
   653–678 s rather than 600, an effective **55–58 Hz**. A frame loop that must track
   wall-clock needs `Schedule.fixed` or a `CADisplayLink`-driven source. The flip side is
   good: on app resume after backgrounding the stream continues at its normal rate rather
   than firing a burst of queued ticks, so whatever replaces `tick` must not reintroduce a
   catch-up stampede.

4. **Backgrounding is safe.** Sending the app to the background suspends it (as iOS does to
   any app) and returning resumes cleanly — no priority inversion, no hang, no crash, no GC
   warning. The ZIO scheduler and UIKit's main thread do not fight.

Smaller things: **`Thread.activeCount()` is meaningless on Scala Native** (reported 14 → 1
while a blocking pool thread was in use — the thread *name* is reliable, the count is not);
`import zio.*` shadows `java.lang.System` with `zio.System`; and `SubscriptionRef.changes`
needs the consumer subscribed before the first `set`, or a `take(n)` hangs rather than fails.

Also, sharpening §9.3e's sbt note: **sbt 2 caches env-driven settings in its content-addressed
store and `sys.env` is not part of the cache key.** A stale value survives killing the server,
`touch build.sbt`, a real content edit, *and* `rm -rf target/out` — the CAS lives in
`~/.cache/sbt/v2/cas`, not under `target/`. S6 hit this again and silently built an
iOS-*device* archive from a build that said simulator; only the linker caught it. The fix is
to remove the `sys.env` read and write the value literally. **Do not drive build configuration
from environment variables in sbt 2.**

## 9.3h Answered by S6 (2026-09-20)

**Calibration numbers, measured identically for three hello apps** (label + button + counter)
on the same Mac and simulator. Full method and caveats in `spikes/s6-calibration/REPORT.md`.

| | A — React Native/Expo | B — native shims | D — JavaFX + Gluon |
|---|---|---|---|
| App bundle (sim) | 26.0 MB | **0.53 MB** | cannot build |
| Stripped binary (device arm64) | — | **0.53 MB** | 60.06 MB |
| Cold start → first render | 702 ms | **425 ms** | not measurable |
| RSS | 203.2 MB | **152.4 MB** | not measurable |

**Option B is 49× smaller than A and 113× smaller than D, starts 1.65× faster than A, and
uses 51 MB less RSS.** Note ~139 MB of every RSS figure is UIKit itself (S1's UI-less harness
sat at 13.5 MB), so A's real overhead is the +51 MB on top, not the absolute number.

Three things that change how `docs/06` should read:

1. **There is no Scala 3 React Native facade.** `slinky-native` is published only for
   Scala 2.13; `slinky-native_sjs1_3` does not exist at any version. `docs/06` says Slinky is
   "effectively unmaintained for RN" — the measured fact is stronger: it was *never published
   for Scala 3*. Option A's "Effort: 4" score assumes a facade library that this project
   cannot use, so the numbers above are for plain-JS RN and are a **floor**.

2. **Gluon's iOS toolchain has been frozen for two years while its tooling moved on.** iOS
   builds must use Gluon's patched GraalVM; its newest release is 2024-09-08 (966 MB), yet
   `gluonfx-maven-plugin`/`substrate` shipped through June 2026. The drift shows up as
   `Missing CAP cache value` failures. Four builds failed before one worked, each for a
   different version reason, and the winning combination is the plugin dated ten days after
   that frozen GraalVM (1.0.24) plus **Maven exactly 3.8.8** (3.9.x is hard-refused).

3. **Gluon's `ios-sim` target is x86_64-only**, so on Apple Silicon a Gluon iOS app cannot be
   run at all without a physical device. With no iPhone available, Option D has no measurable
   startup or memory here — which is itself a verdict on it as a development path.

Also: **Expo's generated iOS template traps at launch on iOS 27** with the same
`NoSceneLifecycleAdoption` assertion S3 hit — Expo ships `ExpoAppSceneDelegate` but
`expo prebuild` does not wire it up, so a brand-new project needs two edits to generated
native files that are lost on every prebuild. And **CocoaPods is required for any RN/Expo iOS
build and is missing from `spikes/MAC-SETUP.md`** (installing it pulls Ruby and OpenSSL).

Suggested edit to §6.1: **add a binary-size row** — it is the most discriminating number
measured (0.53 / 26.0 / 60.06 MB), and N-03 makes size a requirement even though the scoring
table never mentions it.

### 9.3i — Two hand-written shims are the real cost of Apple (issue #3, 2026-09-23)

Issue #3 asked whether `modules/renderer-api` survives a third toolkit family. It does, on
both AppKit and UIKit, unchanged — that question is closed.

The question it opens is **maintenance of the shim, not the contract**. `Shim+AppKit.swift`
(385 lines) and `Shim+UIKit.swift` (388 lines) implement the same 35 functions, and nothing
keeps them in step except one C header and one self-test. They diverge for good structural
reasons — UIKit has no checkbox, `UIControl` takes an event mask, fonts come from the Dynamic
Type scale — so `#if os()` inside function bodies would be worse, not better. At the ~240
functions §7.6 projects for v1, hand-writing both is roughly 5 200 lines of Swift maintained
in duplicate.

This makes S3's "generate the shim, header and bindings from one widget description" a
**prerequisite for the widget catalogue** rather than an optimisation. Proposed edit to
§7.6: say so, and note that the generator's input must be able to express per-platform
widget *choice* (checkbox vs. switch), not only per-platform naming.

Two smaller things worth recording:

- **`fittingSize` / `systemLayoutSizeFitting` return zero for a container already sized by
  its parent** — on both platforms, independently found. Measurement falls back to the
  view's frame. Any future Apple layout work should expect this.
- **A simulator app's `stdout` never reaches `os_log`**, so `log show` cannot see `println`.
  Only `simctl launch --console-pty` captures it, and that never returns on its own. Every
  automated iOS check has to launch detached against a pty and poll the file; recorded
  because it looks like a crash when the app is in fact running fine.

Still open, deliberately: **iOS is verified on the simulator only.** Nothing in the renderer
is simulator-specific, but a device triple and a signing identity are untested and the claim
should not be made until M1.

## 9.4 Not yet researched (deliberately deferred)

- Push notifications, deep links, background tasks, app extensions.
- Localisation pipeline and typed resources (A-09).
- Testing story for app authors (headless renderer? snapshot tests on the dev canvas?).
- Distribution of the shims: SwiftPM package + prebuilt XCFramework vs source.
- Wasm as a fourth Scala backend, should Scala.js's Wasm output mature.
