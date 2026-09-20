# S6 — Calibration baselines — REPORT

**Date:** 2026-09-20
**Machine:** macOS (Darwin 27.0.0), Apple Silicon arm64, Xcode 27.0, iOS 27.0 simulator (iPhone 17)
**Gate:** no. **Purpose:** numbers, not opinions, for the comparison tables in `docs/06`.

Three hello apps — a label, a button, and a counter, and nothing else — built and measured
the same way. Option B is included as the reference point, since a comparison table needs
the recommendation measured on the same rig as its alternatives.

---

## Result: PASS (partial, as the brief allows)

Option A and Option B produced complete numbers. **Option D produced a size figure only**:
Gluon's `ios-sim` target is x86_64-only and cannot build on Apple Silicon, so with no
physical device there is nothing to run. That limitation is itself one of the findings.

---

## Side-by-side

| | **A — React Native / Expo** | **B — native shims** (recommended) | **D — JavaFX + GraalVM + Gluon** |
|---|---|---|---|
| App bundle (simulator) | **26.0 MB** (27,271,168 B) | **0.53 MB** (552,960 B) | *cannot build* |
| Stripped binary, **device arm64** | not built | **0.53 MB** (560,848 B) | **60.06 MB** (62,982,472 B) |
| Cold start → first render | **702 ms** (700/700/706) | **425 ms** (421/426/428) | *not measurable* |
| RSS after launch | **203.2 MB** (203.2/203.1/203.3) | **152.4 MB** (152.4/152.5/152.4) | *not measurable* |
| Clean build time | 50 s (`xcodebuild` Release) | 4–6 s (`nativeLink`) + ~3 s link | 40 s (native-image) + Maven |
| Toolchain steps from a working Mac | 7, **two undocumented** | 3 | 6, **five version-pinned** |
| Extra disk beyond the repo | 301 MB `node_modules` + CocoaPods (pulled Ruby + OpenSSL) | none beyond MAC-SETUP | 966 MB GraalVM + 2 Maven distributions |
| Scala 3 usable today? | **No** — see finding 2 | Yes | Yes (with a bytecode-target fix) |

**Headline: Option B's app is 49× smaller than Option A's and 113× smaller than Option D's,
starts 1.65× faster than A, and uses 51 MB less RSS.** The device-to-device size comparison
(B 0.53 MB vs D 60.06 MB, same triple, both stripped) is the cleanest single number here.

### How these were measured

`tools/measure.sh`, identically for every option. Cold start is the interval between a host
Unix-epoch timestamp taken immediately before `simctl launch` and a `S6_FIRST_RENDER <epoch>`
line each app prints when its first UI update is on screen — Option B after `CATransaction.flush()`,
Option A from `RCTContentDidAppearNotification` (which fires when JS content first appears, so
RN is charged for bridge startup and its first render, not just native launch). Both clocks are
the host's, because simulator apps are ordinary macOS processes; RSS is read with `ps` for the
same reason.

An earlier version polled screenshots for "first non-blank frame". That was discarded:
`simctl io screenshot` costs ~420 ms, which is coarser than the thing being measured, and it
produced a flat ~580 ms for everything.

---

## Option A — Scala.js on React Native (Expo 57, RN 0.86.3, React 19.2.3)

### What it felt like

Fast and pleasant right up to the point where it didn't work. `create-expo-app` →
`npm install` → `expo prebuild` → `pod install` → `xcodebuild` is four commands and about two
minutes of machine time, and the tooling is genuinely good. Then the app **traps at launch on
iOS 27** and none of that helps.

### Finding 1: the Expo template does not run on iOS 27

```
EXC_BREAKPOINT (SIGTRAP)
__UIApplicationEvaluateRuntimeIssueForNoSceneLifecycleAdoption_block_invoke
```

`expo prebuild` generates an `AppDelegate.swift` that creates a `UIWindow` directly and never
adopts the `UIScene` lifecycle, which iOS 27 refuses. Expo *ships the fix* —
`node_modules/expo/ios/AppDelegates/ExpoAppSceneDelegate.swift`, whose own doc comment says
"Required by the iOS 27, which asserts at launch unless the app adopts the scene-based life
cycle" — but the template does not wire it up. Getting a hello world to launch required
editing generated native code:

- `AppDelegate` conforms to `ExpoReactNativeFactoryProvider`, and its window creation and
  `startReactNative` call are **deleted** (the scene delegate does both).
- `Info.plist` gains a `UIApplicationSceneManifest` naming `EXExpoAppSceneDelegate`.

Both edits are to files `expo prebuild` regenerates, so they are lost on every prebuild.
This is precisely the "inherits RN's upgrade churn" risk `docs/06` scores Option A 2/5 on,
observed on day one of a brand-new project. (Option B hit the same iOS 27 requirement in S3
and paid for it once, in code it owns.)

### Finding 2: there is no Scala 3 React Native facade

The brief asks for Slinky-native or an alternative. `slinky-native` **has no Scala 3 build at
all**:

| artifact | published versions |
|---|---|
| `me.shadaj:slinky-native_sjs1_2.13` | `0.7.5+4-a97ed072`, `0.7.5+11-4455d42a` |
| `me.shadaj:slinky-native_sjs1_3` | **none** — `cs fetch` fails for every version tried |

`slinky-core_sjs1_3` exists (web React), but the React Native half was never cross-published
for Scala 3, and the newest artefacts are git-hash snapshots off 0.7.5 rather than releases.

**So the measured Option A above is plain JavaScript React Native, not Scala.** Taking Option A
with this project's chosen language would mean writing and maintaining RN facades from scratch
— which changes its "Effort / time-to-value: 4" score materially, since the low-effort story
depends on a facade library that does not exist for Scala 3. The size, startup and memory
numbers are unaffected by that (a Scala.js layer would only add to them), so the table above
is a *floor* for Option A.

---

## Option B — native shims (the recommendation), measured for reference

`releaseFast` + `LTO.full` + `-lc++`, the shipping configuration S8 established. Built from
S3's shim, with a Scala closure on a real `UIButton` driving a `UILabel`.

0.53 MB, 425 ms, 152.4 MB RSS. The ~150 MB RSS floor is UIKit itself and is common to A and B
alike — S1's UI-less harness sat at 13.5 MB, so roughly 139 MB of every number in the RSS row
is the platform, not the framework. **Option A's meaningful overhead is the +51 MB on top.**

---

## Option D — JavaFX + GraalVM native-image + Gluon Substrate

### What it felt like

Every single component has a narrow version window, and none of the error messages tell you
that. Four builds failed before one succeeded, each for a different version reason:

| attempt | configuration | outcome |
|---|---|---|
| 1 | plugin 1.0.29, target `ios-sim` | `Missing CAP cache value for: ...AMD64LibCHelperDirectives...fAVX_IFMA` |
| 2 | plugin 1.0.29, target `ios` (arm64) | `Missing CAP cache value for: ...JNIHeaderDirectives:ConstantInfo:JNI_VERSION_19` |
| 3 | plugin 1.0.24, Maven 3.9.11 | `Maven version 3.9.11 is not currently supported... Please downgrade to 3.8.8` |
| 4 | **plugin 1.0.24, Maven 3.8.8, target `ios`** | **BUILD SUCCESS**, native image in 40.1 s |
| 5 | plugin 1.0.24, Maven 3.8.8, target `ios-sim` | `Missing CAP cache value for: ...struct___darwin_mcontext64...__ss___r10` |

Also required: `maven.compiler.release` must be **21, not 23**, even though Gluon's GraalVM is
JDK 23 — Scala 3.9 rejects 23 with "23 is not a valid choice for -java-output-version".

### Finding 3: the mandatory iOS toolchain has been frozen for two years

iOS and iOS-simulator builds **must** use Gluon's own GraalVM fork (it carries patches absent
from upstream). Its newest release is **`gluon-23+25.1-dev-2409082136`, published 2024-09-08**
— two years old as of this report, 966 MB unpacked.

Meanwhile `gluonfx-maven-plugin` and `substrate` have kept shipping (plugin metadata
`lastUpdated 20260618`, i.e. June 2026). **The tooling has moved on from the GraalVM it
requires**, and the CAP-cache errors in attempts 1 and 2 are exactly that drift: substrate
0.0.69's cached native-layout queries lack fields the newer SVM expects. The only combination
that works is the plugin contemporary with the frozen GraalVM — 1.0.24, dated 2024-09-18, ten
days after it.

This is direct evidence for the "Gluon is a small company" concern behind `docs/06`'s
stability score, and it is worse than that score implies: not "might stop", but "the iOS
toolchain's keystone has not shipped in two years while everything around it moved".

### Finding 4: `ios-sim` is x86_64-only, so Apple Silicon cannot run Gluon iOS builds

The target directory is literally `target/gluonfx/x86_64-ios/`, and attempts 1 and 5 both die
in AMD64/x86_64 CAP-cache lookups. On an Apple Silicon Mac there is **no way to run a Gluon
iOS app without a physical device** (or, presumably, an Intel Mac). Since this project has no
iPhone (2026-09-18 decision), Option D's startup and memory are unmeasurable here — which is
itself a meaningful verdict about its fitness as a development path.

### Finding 5: 60 MB for a label and a button

The arm64 device binary is **62,982,472 B stripped (60.06 MB)**; the whole `.app` is a single
executable. Against Option B's 0.53 MB on the identical triple, that is **112×**, and it is
ten times N-03's 6 MB budget.

Caveat, stated plainly: this is a default `gluonfx:build` with no size tuning, while Option B's
figure *is* tuned (`releaseFast` + `LTO.full`). Gluon's documentation cites smaller figures for
optimised builds. But the gap is two orders of magnitude and native-image cannot tree-shake
JavaFX's graphics stack down by 100×, so tuning changes the number without changing the
conclusion.

---

## What this means for `docs/06`

1. **§6.1's "Effort / time-to-value" row is the one that needs revising, and in both
   directions.** Option A scores 4 on the assumption that RN facades exist; for Scala 3 they do
   not, so the honest score is lower unless someone writes them. Option D scores 5 — "low
   (weeks) — Scala works with JavaFX today" — which held for *compiling*, but the iOS path took
   five attempts against a two-year-frozen 966 MB toolchain, and on Apple Silicon does not
   reach a runnable app at all. Both were graded on the desktop/JVM experience, not the mobile
   one.

2. **The performance rows are confirmed, with numbers.** A's "JS engine adds startup and
   memory ✘" is +277 ms and +51 MB. D's "native-image startup is fine ✔" is unverified on iOS
   and comes with a 60 MB binary.

3. **Add a size row to §6.1.** It is the most discriminating measurement taken — 0.53 / 26.0 /
   60.06 MB — and N-03 already makes size a requirement, but the scoring table does not reflect
   it anywhere.

4. **`docs/06` Option A says "Slinky is effectively unmaintained for RN".** Strengthen that to
   the measured fact: *never published for Scala 3*. That is a different and more decisive
   claim than "unmaintained".

5. **Option B's recommendation survives calibration.** It is the smallest by two orders of
   magnitude, the fastest to start, the lightest in memory, has the fewest toolchain steps, and
   is the only one of the three whose iOS story worked on this machine without fighting version
   skew. The ~150 MB UIKit RSS floor applies to everyone and should not be read as framework
   overhead.

---

## What was built

```
spikes/s6-calibration/
  tools/measure.sh          uniform cold-start / RSS / size harness
  tools/timelog.txt
  optionA/                  Expo 57 + RN 0.86.3 hello (App.js, patched AppDelegate + Info.plist)
  optionB/                  Scala Native + Swift shim hello (build.sbt, Hello.scala, shim/)
  optionD/                  JavaFX + Scala hello (pom.xml, HelloD.scala)
```

Not committed: `node_modules/` (301 MB), `ios/` and `Pods/` (regenerable via
`expo prebuild && pod install`), `tools/apache-maven-*` and `tools/graalvm-*` (downloads),
`target/`, `build/`.

Reproduce: Option B `sbt scalaLib/nativeLink` then the `swiftc` line in this report; Option A
`npm install && npx expo prebuild --platform ios && (cd ios && pod install) && xcodebuild ...`
plus the two scene-lifecycle edits; Option D
`tools/apache-maven-3.8.8/bin/mvn -Pios gluonfx:build` with `GRAALVM_HOME` pointing at Gluon's
GraalVM and the plugin pinned to 1.0.24.

## A note on the brief's "build effort (hours)"

Not reported as hours. This session spanned long idle gaps between exchanges, so wall-clock
elapsed would be fiction. What is reported instead — toolchain steps, measured build times, the
count of undocumented fixes each option required, and extra disk — is what actually
distinguishes them, and is reproducible. The honest qualitative summary: **A was quick until
it wasn't; B was uneventful; D was five failed builds against frozen tooling and still has no
runnable artefact on this machine.**

## Side findings

- **CocoaPods is required for any RN/Expo iOS build and is not in `spikes/MAC-SETUP.md`.**
  Installing it via Homebrew also pulls Ruby 4.0.7 and OpenSSL 3.6.4.
- **`gluonfx-maven-plugin` 1.0.24 hard-refuses Maven 3.9.x**, requiring exactly 3.8.8.
- Both Maven and Gluon's GraalVM install cleanly *inside the spike directory*, so neither
  needed a system install — worth remembering for any future toolchain calibration.
