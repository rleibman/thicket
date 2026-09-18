# 5. Requirements

MoSCoW priority: **M** must, **S** should, **C** could, **W** won't (this horizon).
IDs are stable; reference them from issues and spikes.

## 5.1 Platform & packaging

| ID   | Pri | Requirement                                                                                                                                                                 |
|------|-----|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| P-01 | M   | Build and run on iOS 17+ (device + simulator, arm64), Android API 26+ (arm64-v8a, x86_64 emulator), macOS 13+, Linux (GTK4, x86_64/arm64), Windows 10+ (x86_64).            |
| P-02 | M   | One Scala 3 codebase per app; platform-specific code is opt-in via `platform` sub-modules, never required for the common widget set.                                        |
| P-03 | M   | Produce store-ready artefacts: signed `.ipa`/`.app` via a generated Xcode project, `.apk`/`.aab` via a generated Gradle project, `.dmg`/`.msi`/`.deb`/AppImage for desktop. |
| P-04 | M   | Generated Xcode/Gradle projects are stable, checked into the app repo, and re-generation is idempotent (diffs are meaningful).                                              |
| P-05 | S   | Single CLI (`scala-ui new                                                                                                                                                   |run|build|doctor`) wrapping sbt/mill/scala-cli, Xcode CLI tools, Gradle. |
| P-06 | S   | Runs under sbt, mill and scala-cli (core is a plain library; tooling plugins for sbt and mill).                                                                             |
| P-07 | C   | Browser dev canvas via Scala.js for previews/docs.                                                                                                                          |
| P-08 | W   | Web as production target; watchOS/tvOS/wearOS; embedded.                                                                                                                    |

## 5.2 Platform fidelity

| ID   | Pri | Requirement                                                                                                                                                                                                                                                                          |
|------|-----|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| F-01 | M   | Every standard control (text, button, text field, switch, slider, checkbox, radio, segmented/tabs, picker/date picker, progress, activity indicator, image, list, scroll view, alert/dialog, sheet, menu, toolbar/nav bar) is backed by the platform's own control where one exists. |
| F-02 | M   | System behaviours are free: dark mode, dynamic type/font scaling, RTL, accessibility (VoiceOver/TalkBack/Narrator/Orca), IME & text selection, scroll physics, safe areas, keyboard avoidance, swipe-back / predictive back.                                                         |
| F-03 | M   | Navigation uses the platform's navigation container (UINavigationController, Android back-stack semantics, NSWindow/NSToolbar & menu bar on macOS, GTK header bar, Win32/WinUI window chrome).                                                                                       |
| F-04 | S   | Platform-specific extensions are reachable from Scala without forking the app: `Platform.ios { ... }` blocks compile only on that target.                                                                                                                                            |
| F-05 | S   | Platform "escape hatch": embed a raw native view (UIView / android.view.View / GtkWidget / HWND) as a leaf.                                                                                                                                                                          |
| F-06 | C   | Optional consistent-look mode (custom-drawn) for apps that want one brand look; not needed for v1.                                                                                                                                                                                   |

## 5.3 Programming model

| ID    | Pri | Requirement                                                                                                                                                                                                                                                                                                |
|-------|-----|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| A-01  | M   | Declarative element tree: UI = function of state; framework reconciles against retained native widgets.                                                                                                                                                                                                    |
| A-02  | M   | Fine-grained reactive state (signals/vars/computed/effects) that works identically on JVM, JS and Native; no compiler plugin required.                                                                                                                                                                     |
| A-03  | M   | Type-safe styling: units are opaque types (`Dp`, `Sp`, `Px`), colours/typography via theme `given`s, enums for alignment/direction; no stringly-typed styles.                                                                                                                                              |
| A-04  | M   | Flexbox layout semantics (Yoga) across all platforms so layout behaves identically; leaves may report intrinsic size from the native control.                                                                                                                                                              |
| A-05  | M   | Effect-system agnostic core: no `F[_]` in widget signatures; effects enter through a documented **effect bridge contract** (`renderer-api`-style versioned interface).                                                                                                                                     |
| A-05a | M   | **ZIO bridge (`scala-ui-zio`) ships with the first milestone** and is the reference implementation: `ZIO`/`ZStream`/`SubscriptionRef`/`Hub` ↔ signals, component-scoped `Scope` (fibers interrupted on unmount), UI-thread `Executor`, typed-error routing, `ZLayer` app wiring, `Runtime` swap for tests. |
| A-05b | S   | cats-effect bridge (`scala-ui-cats-effect`) implementing the same contract (`Dispatcher`, `Resource`, fs2 streams).                                                                                                                                                                                        |
| A-05c | M   | ZIO 2 and the ZIO bridge compile and run on all three backends, including Scala Native on iOS (ZIO is cross-published for Native; iOS behaviour is unverified → Spike S8).                                                                                                                                 |
| A-05d | S   | The optional MVU layer (A-07) expresses commands as effects of the chosen bridge (ZIO first), Tyrian-style.                                                                                                                                                                                                |
| A-06  | M   | Main-thread safety enforced: mutating UI state off the UI thread is either automatically marshalled or a compile-time/runtime error with a clear message — never silent corruption.                                                                                                                        |
| A-07  | S   | Optional Model-View-Update layer (Tyrian/Elm style) on top of signals for teams that prefer it.                                                                                                                                                                                                            |
| A-08  | S   | Component model: user components are functions; keyed children for stable identity in lists.                                                                                                                                                                                                               |
| A-09  | S   | Resource management: images, fonts, localisation strings referenced through generated typed accessors.                                                                                                                                                                                                     |
| A-10  | C   | Server-shared code: domain models, Caliban/GraphQL or JSON clients compile unchanged (i.e. the core forces no dependency that breaks `%%%` cross-building).                                                                                                                                                |

## 5.4 Developer experience

| ID   | Pri | Requirement                                                                                                             |
|------|-----|-------------------------------------------------------------------------------------------------------------------------|
| D-01 | M   | Edit→see loop ≤ 2 s in a development mode (dev canvas or JVM desktop host) and ≤ 30 s to redeploy on a device/emulator. |
| D-02 | M   | `scala-ui doctor` reports missing SDKs, toolchains, signing identities.                                                 |
| D-03 | M   | Metals and IntelliJ work out of the box on the Scala parts; Xcode/Android Studio open the generated shells.             |
| D-04 | M   | Errors from the native side surface as Scala exceptions with the native stack attached.                                 |
| D-05 | S   | Widget inspector (tree, computed layout, signal graph) in dev mode.                                                     |
| D-06 | S   | Component gallery app that doubles as visual regression test-bed.                                                       |
| D-07 | S   | Documentation: getting-started per platform in ≤ 15 minutes, API docs, cookbook; every public API has an example.       |
| D-08 | C   | Live preview of a single component (SwiftUI/Compose preview analogue) using the dev canvas.                             |

## 5.5 Performance (non-functional)

| ID   | Pri | Requirement                                                                                                      | How measured                                 |
|------|-----|------------------------------------------------------------------------------------------------------------------|----------------------------------------------|
| N-01 | M   | Cold start to first interactive frame ≤ 500 ms on a 2021 mid-range Android phone and iPhone 12; ≤ 300 ms desktop | Instrumented gallery app, CI on real devices |
| N-02 | M   | Steady 60 fps scrolling of a 10 000-row list with mixed content; no frame > 32 ms                                | Platform profilers (Instruments, Perfetto)   |
| N-03 | M   | Framework overhead to APK ≤ 4 MB after R8; iOS static lib ≤ 6 MB in a hello-world                                | Size in CI                                   |
| N-04 | M   | Baseline RSS ≤ 60 MB for hello-world on mobile                                                                   | Device measurement                           |
| N-05 | S   | Scala→native leaf call round-trip ≤ 5 µs (direct C call, no marshalling queues)                                  | Micro-benchmark in S3                        |
| N-06 | S   | GC pauses on mobile ≤ 5 ms p99 under UI-typical allocation                                                       | Instrumentation                              |

## 5.6 Stability & governance (non-functional)

| ID   | Pri | Requirement                                                                                                                                              |
|------|-----|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| S-01 | M   | Semantic versioning; renderer contract is a versioned, documented interface so third-party backends can exist.                                           |
| S-02 | M   | Generated bindings and shims are vendored in this repository; no single-maintainer third-party library in the runtime critical path without a fork plan. |
| S-03 | M   | CI builds and runs the gallery on all five platforms (macOS runner covers iOS/macOS; Linux runner covers Android emulator + GTK; Windows runner).        |
| S-04 | S   | Deprecation policy: one minor version of warnings before removal.                                                                                        |
| S-05 | S   | Licence: Apache-2.0 for framework; shims and bindings likewise; no runtime commercial dependency.                                                        |
| S-06 | C   | Long-term-support line once 1.0 ships.                                                                                                                   |

## 5.7 Explicitly out of scope for v1

3D/canvas-heavy UI, custom shader effects, background services/widgets/extensions (Live Activities, home-screen
widgets), push notification plumbing beyond a hook,
in-app purchase, maps. Each is a "plugin" concern once the core exists.
