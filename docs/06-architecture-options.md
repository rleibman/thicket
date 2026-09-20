# 6. Architecture options

Six options were considered. The first four produce a Scala UI framework; E and F
are pragmatic fallbacks. Each is evaluated against the popularity criteria (usability, fidelity, stability, performance)
plus effort and Scala-ecosystem fit.

## Option A — Scala.js on React Native

Scala.js facades over React Native's host components (refresh Slinky-native or write
new facades on scalajs-react); Expo for tooling; RN-Windows / RN-macOS for desktop.

- **Fidelity:** native widgets on iOS/Android ✔; desktop through Microsoft forks that
  lag mainline by 3–6 minors ✘.
- **Usability:** Fast Refresh and Expo tooling are excellent ✔; but two toolchains (sbt + npm/Metro), JS interop
  debugging, and Scala.js `js.Dynamic` leakage ✘.
- **Stability:** inherits RN's upgrade churn; Scala facades must track RN releases;
  **S6 measured the decisive fact: `slinky-native` was never published for Scala 3 at all** —
  only `_sjs1_2.13` exists, and its newest artefacts are git-hash snapshots off 0.7.5. That is
  a stronger claim than "unmaintained": there is no Scala 3 React Native facade to maintain ✘✘.
  S6 also needed two hand edits to `expo prebuild`-generated native code just to launch on
  iOS 27 — to files every subsequent prebuild regenerates. Day one of a brand-new project.
- **Performance:** RN's new architecture (JSI/Fabric, synchronous) is good; JS
  engine (Hermes) adds startup and memory ✘.
- **Effort:** *reassessed after S6.* The low-effort story depended on a facade library that
  does not exist for Scala 3, so this option now begins with writing and maintaining RN
  facades from scratch.
- **Verdict:** best *time-to-demo* with real native widgets. Poor *identity*: it is
  React Native written in Scala, it never addresses desktop well, and the JS runtime
  contradicts goal G5. Useful as a benchmark to beat, not as the product.

## Option B — Pure Scala core, native widgets, thin per-platform shims ← recommended

Cross-compiled Scala 3 core (element tree + signals + Yoga layout + reconciler).
Renderers: Android via Scala/JVM calling `android.view.*` directly; iOS/macOS via
Scala Native calling a Swift shim with a C ABI (`@_cdecl`); Linux via Scala Native +
existing GTK4 bindings; Windows via Scala Native + Win32 (proof) then a WinUI 3 C shim.

- **Fidelity:** native by construction on all five ✔✔.
- **Usability:** DSL in pure Scala 3 (no compiler plugin) ✔; dev loop needs the
  Scala.js dev canvas / JVM host to hide Scala Native link times ~; generated
  Xcode/Gradle shells hide platform build systems ✔.
- **Stability:** no JS engine, no webview, no third-party UI framework in the
  critical path; only Yoga (Meta, stable C API) and platform SDKs ✔; OS releases
  absorbed by native widgets ✔; toolchain risk concentrated in Scala Native on iOS ✘.
- **Performance:** AOT native on Apple/desktop; ART on Android; direct C calls to
  widgets ✔.
- **Effort:** high. Roughly: core 6–9 months; each renderer 3–6 months (iOS first,
  macOS +1, GTK +2, Android +4, Windows +5); tooling 3–4 months; ongoing widget
  catalogue work. Requires 2–4 sustained contributors.
- **Verdict:** the only option that meets G1–G5 and has a genuine differentiator (native look on desktop *and* mobile,
  no VM). Its feasibility hinges on Spike S1 (Scala Native on iOS) and S2 (Scala 3 on Android). Do those first.

## Option C — Pure Scala core with a custom Skia-class renderer (the Flutter/Compose way)

One renderer everywhere, platform looks mimicked.

- **Fidelity:** always chasing; accessibility, IME, text editing are multi-year ✘.
- **Usability:** could be excellent (identical everywhere) ✔.
- **Stability/Performance:** fine once mature; Skia via Skiko (JVM) is not available
  to Scala Native; Skia's C API is incomplete; would need our own bindings or a
  different renderer (Vello/wgpu via C ABI) ✘.
- **Effort:** very high — this is what JetBrains and Google staff dozens of engineers
  for.
- **Verdict:** out of reach for the community that would build this. Keep the
  renderer contract abstract so a Skia backend could be added later (F-06).

## Option D — ScalaFX + GraalVM native-image + Gluon Substrate

Existing Java toolchain: JavaFX UI, compiled to native for iOS/Android by Substrate.

- **Fidelity:** JavaFX Modena / Gluon Glisten themes — not native ✘.
- **Usability:** mature desktop story, SceneBuilder; mobile build chain is heavy and
  depends on Gluon's GraalVM builds ~.
- **Stability:** JavaFX is well maintained (OpenJFX 25 LTS line). **Gluon's iOS path is
  frozen: the GraalVM fork it mandates was last published 2024-09-08**, two years before S6
  measured it, while the plugin and substrate kept shipping — so the tooling has drifted from
  the GraalVM it requires, and only the plugin contemporary with the frozen fork works. Not
  "a small company might stop"; it has not shipped in two years ✘.
- **Performance:** native-image startup is fine; JavaFX rendering is fine ✔.
- **Effort:** *reassessed after S6.* Low on desktop/JVM, which is what the original score
  reflected. The **iOS** path took five failed builds against version-pinned tooling (plugin
  1.0.24 exactly, Maven 3.8.8 exactly, a 966 MB two-year-old GraalVM), and **`ios-sim` is
  x86_64-only, so an Apple Silicon Mac cannot run a Gluon iOS app at all** without a device.
- **Verdict:** ~~the fastest route to a Scala app on an iPhone~~ — S6 ran that experiment and
  it is not fast. A hello world is **60.06 MB** stripped on device: 112× Option B on the same
  triple, and 10× the N-03 budget. It could not be launched at all on the available hardware.
  Fails G1 and now N-03 too.

## Option E — Scala.js in a webview shell (Tauri 2 / Capacitor)

Laminar or Tyrian frontend; Tauri 2 (desktop + mobile) or Capacitor (mobile).

- **Fidelity:** web ✘ (Ionic-style CSS mimicry at best).
- **Usability:** best-in-class Scala web tooling; Tauri mobile still maturing ~.
- **Stability:** webview behaviour varies by OS version ~.
- **Performance:** webview memory/startup ✘.
- **Effort:** very low.
- **Verdict:** valid pragmatic answer *today* for internal tools; not a thick-client
  framework. Note this is essentially the dev-canvas backend of Option B shipped as
  a product, so it comes almost free once B exists.

## Option F — Share logic in Scala, keep UIs native (KMP-before-Compose model)

Scala Native `libraryStatic` + `@exported` C ABI consumed by SwiftUI; Scala/JVM
consumed by Jetpack Compose on Android.

- **Fidelity:** perfect ✔✔. **Usability:** three UI languages ✘.
- **Verdict:** not a UI framework, but it *is* step one of Option B's iOS renderer (the Swift shim is exactly this), and
  it is the honest recommendation for any team
  that needs to ship before Option B matures.

## 6.1 Scoring

Weights reflect the popularity analysis in `02-…`. 1 = poor, 5 = excellent.

| Criterion (weight) | A: RN | B: native shims | C: Skia | D: JavaFX/Gluon | E: webview | F: logic only |
|---|---|---|---|---|---|---|
| Platform fidelity (×3) | 4 (desktop 2) | 5 | 3 | 2 | 2 | 5 |
| Developer usability (×3) | 4 | 4* | 4 | 3 | 4 | 2 |
| Stability / bus factor (×2) | 2 | 4 | 3 | 3 | 3 | 5 |
| Performance (×2) | 3 | 5 | 4 | 4 | 2 | 5 |
| **Binary size (×2)** † | **2** | **5** | 3 | **1** | 2 | 5 |
| Effort / time-to-value (×2) | **2** ‡ | 1 | 1 | **2** ‡ | 5 | 4 |
| Scala identity & ecosystem fit (×2) | 2 | 5 | 5 | 3 | 4 | 3 |
| **Weighted total (max 80)** | **46** | **67** | **53** | **41** | **50** | **65** |

† **Size row added after S6**, which measured it as the most discriminating property of the
lot — 26.0 / 0.53 / 60.06 MB for the same hello app — while N-03 already makes size a
requirement that the scoring did not reflect anywhere.

‡ **Effort re-scored after S6**, in both directions and for the same underlying reason: both
options were originally graded on their desktop/JVM experience rather than their mobile one.
A's 4 assumed Scala 3 React Native facades exist (they do not); D's 5 assumed the iOS path
resembles the desktop one (it does not).

\* conditional on solving the dev loop (D-01).

### 6.1.1 Calibration (S6, 2026-09-20)

Options A, B and D were built as the same hello app — a label, a button, a counter — and
measured on one rig, iOS 27 simulator on Apple Silicon:

| | A — RN/Expo | **B — native shims** | D — JavaFX/Gluon |
|---|---|---|---|
| App bundle (simulator) | 26.0 MB | **0.53 MB** | *cannot build on Apple Silicon* |
| Stripped binary, device arm64 | not built | **0.53 MB** | **60.06 MB** |
| Cold start → first render | 702 ms | **425 ms** | *not measurable* |
| RSS after launch | 203.2 MB | **152.4 MB** | *not measurable* |
| Toolchain steps from a working Mac | 7 (two undocumented) | **3** | 6 (five version-pinned) |
| Extra disk | 301 MB `node_modules` + CocoaPods | **none** | 966 MB GraalVM + 2 Mavens |
| Scala 3 usable today? | **No** | **Yes** | Yes, with a bytecode-target fix |

Read the RSS row carefully: ~139 MB of every figure is UIKit itself (S1's UI-less harness sat
at 13.5 MB), so **A's real overhead is the +51 MB**, not the whole 203 MB.

The recommendation survives its own calibration: Option B is the smallest by two orders of
magnitude, the fastest to start, the lightest in memory, has the fewest toolchain steps, and
was the only one whose iOS story worked on the available hardware without fighting version
skew. Full detail: [`spikes/s6-calibration/REPORT.md`](../spikes/s6-calibration/REPORT.md).

## 6.2 Recommendation

1. **Commit to Option B** *conditionally*: it is the only option that is both a
   real Scala UI framework and a differentiated one.
2. **Gate the commitment on two spikes** (`08-…`): S1 Scala Native on iOS device and
   simulator; S2 Scala 3 on Android with R8. If S1 fails and cannot be fixed
   upstream within a bounded effort, fall back to **A for mobile** with **B's core
   and desktop renderers** (the core is renderer-agnostic, so nothing is lost).
3. Build **Option F as the first deliverable of B** (Scala Native static lib +
   Swift host app) — it de-risks the toolchain and is useful on its own.
4. Use the **Scala.js dev canvas** (essentially E) as the dev-loop backend from the
   first milestone, so hot reload exists before the native renderers do.
5. Spend one week on **D** purely to have a working Scala-on-iPhone baseline for
   size/startup comparison.
6. **Effects: "F at the edges", ZIO first.** The core is synchronous signals with
   no effect type in the API; a per-effect-system bridge module adapts fibers,
   streams and resources to signals and the UI thread. The ZIO bridge is built
   alongside the core (M0/M1) and is the reference implementation of the bridge
   contract; cats-effect follows. Rejected alternative: tagless `F[_]` through the
   whole widget API (Tyrian's `Cmd[F]` style) — see `07-…` §7.13 for why.

## 6.3 What we deliberately reject

- Making Compose Multiplatform callable from Scala: impossible on iOS (Kotlin/Native
  cannot run Scala) and only half-possible on JVM (Scala cannot author
  `@Composable`). Dead end.
- A Scala compiler plugin for positional memoization (Compose-style): powerful but
  brittle across Scala versions and IDEs; explicit signals achieve the ergonomics
  without it (see `07-…`).
- Binding UIKit through `objc_msgSend` directly: possible, but the Swift shim is
  smaller, safer and maintainable by ordinary iOS developers.
