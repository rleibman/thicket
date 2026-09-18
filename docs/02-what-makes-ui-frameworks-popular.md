# 2. What makes a UI framework popular

The question the user asked: what do popular cross-platform UI frameworks get right
about **usability, platform fidelity, stability and performance**, and what does that
imply for a Scala framework? Below is the evidence and the lessons.

## 2.1 Data points (2025–2026)

- Cross-platform market share is roughly Flutter ~46% vs React Native ~35% by one
  2026 tally; a 2025 Statista enterprise survey had RN 42% / Flutter 38% — i.e. the
  two are neck and neck and everything else is a distant third.
- Stated satisfaction drivers: Flutter → *hot reload, widget composition model,
  multi-platform output*. React Native → *JS ecosystem familiarity, code sharing with
  web, large community*. Both report ~4.0–4.2/5 satisfaction in the JetBrains 2025
  Developer Ecosystem survey.
- Kotlin Multiplatform's stable-since-2023 pitch is *share the logic, keep the UI
  native (SwiftUI / Jetpack Compose)*; Compose Multiplatform for iOS went stable in
  1.8.0 (May 2025) and its release notes lead with *native feel*: native text
  selection, scrolling physics, drag-and-drop, respecting system font size/contrast,
  native navigation gestures.
- React Native for Windows and macOS are maintained by Microsoft but lag mainline
  RN by 3 minor releases (Windows) and 6 (macOS) as of Aug 2026 — evidence that
  desktop is a second-class citizen in mobile-first frameworks and users notice.
- Tauri 2 (stable Oct 2024, 2.11.x by mid-2026) added iOS/Android; community
  assessment is "functional for internal tools, less mature than desktop".

## 2.2 Usability (developer experience)

What the winners share:

1. **A declarative, compositional API.** Every framework that grew after 2015 —
   React, Flutter, SwiftUI, Compose — describes UI as a function of state and lets
   the framework reconcile. Imperative toolkits (Swing, GTK direct, UIKit direct) are
   what people are escaping from.
2. **Hot reload / fast refresh, in the single digits of seconds.** Named first by
   Flutter developers and a headline feature of RN, Compose and SwiftUI previews.
   A UI framework without it feels a generation old regardless of its merits.
3. **A single command to scaffold, run and ship.** `flutter create/run/build`,
   `npx create-expo-app`, Xcode/Android Studio integration. The Scala equivalent
   must be `sbt`/`mill`/`scala-cli` plus, ideally, a `scala-ui` CLI.
4. **Batteries in the box**: navigation, lists, forms, theming, images, storage,
   networking, permissions. Flutter's "pub.dev has a package for that" and RN's
   Expo SDK matter as much as the core.
5. **Debuggability**: widget inspector, state inspection, readable stack traces.
6. **Docs and examples that assume nothing.**

Scala-specific implications: Scala 3's context functions, `given`s, extension
methods, enums and opaque types can produce a DSL as terse as Compose or SwiftUI *without* a compiler plugin (see
`07-technical-design-ideas.md`). The known
liabilities are compile/link times (Scala Native linking in particular) and the
"sbt is scary" reputation; both are DX problems this plan must solve, not paper over.

## 2.3 Looking like the platform

Three strategies exist:

| Strategy                                   | Examples                                                                             | Pros                                                                      | Cons                                                                                                                                                                   |
|--------------------------------------------|--------------------------------------------------------------------------------------|---------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Native widgets** wrapped by a common API | React Native, NativeScript, Xamarin.Forms/MAUI, Uno (native mode), Titanium          | Free fidelity, accessibility, IME, RTL, dynamic type, OS updates for free | Lowest-common-denominator API; per-platform bugs; "bridge" cost; some widgets don't exist everywhere                                                                   |
| **Custom-drawn, mimic the platform**       | Flutter (Cupertino/Material), Compose Multiplatform (Skia/Skiko), Avalonia, Qt Quick | Pixel control, identical behaviour everywhere, one renderer to maintain   | Perpetual catch-up with each OS release; accessibility, IME and text editing take years to get right (Flutter and Compose both needed several years); "uncanny valley" |
| **Web view with CSS mimicry**              | Ionic/Capacitor, Tauri, Electron                                                     | Fastest to build; largest ecosystem                                       | Least native feel; startup and memory cost; platform-behaviour gaps (scroll, gestures)                                                                                 |

Observation: the two market leaders split across strategies 1 and 2, so either can
win. But strategy 2 requires a Skia-class renderer plus text shaping, IME and
accessibility — a multi-year effort for a large team (Flutter, JetBrains). Strategy 1
is the only one a small community can execute *and* it delivers G1 by construction.
Strategy 3 is the pragmatic fallback and the right choice for the dev-loop backend.

Fidelity details that users notice and that a native-widget strategy gets for free:
scroll physics and overscroll, pull-to-refresh, swipe-back navigation, context
menus, text selection handles, keyboard avoidance, dark mode, dynamic type,
VoiceOver/TalkBack, RTL, system fonts, haptics, share sheet, safe areas, per-OS
button/switch/date-picker appearance, and menu bar / window chrome on desktop.

## 2.4 Stability

What users of a framework mean by stability:

- **Upgrade safety**: semver, migration guides, deprecation cycles. RN's history of
  painful upgrades is its most-cited weakness; Flutter's `flutter upgrade` mostly
  just works.
- **OS-release resilience**: a new iOS/Android release should not break apps. Native
  widgets absorb most of this; custom renderers must chase it.
- **Runtime crash rate**: GC pauses, JNI/FFI errors, threading violations ("UI touched
  off the main thread") are the classic failure modes; the framework should make
  them impossible or loud.
- **Maintainer bus factor**: Slinky's React Native support has not moved
  materially since ~0.7.x; Airstream's author explicitly declined to make it
  cross-platform. A Scala framework must not hang off a single-maintainer dependency
  in the critical path.

## 2.5 Performance

Users measure:

| Metric                    | Bar (what native apps do)        | Notes                                                                                                                                                                           |
|---------------------------|----------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Cold start to first frame | < 400 ms on a mid-range phone    | AOT-compiled code (Flutter, Scala Native, GraalVM) hits this; JIT/JS runtimes struggle                                                                                          |
| Frame time                | 16.6 ms (60 Hz), 8.3 ms (120 Hz) | Layout + render must fit; list virtualisation mandatory                                                                                                                         |
| Memory                    | Tens of MB for a simple app      | Webviews and JS engines cost 50–150 MB; GC heap sizing matters                                                                                                                  |
| Binary/APK size           | Single-digit MB overhead         | Flutter ~5 MB, RN ~7 MB baseline; Scala stdlib on Android after R8 must be measured                                                                                             |
| Bridge latency            | Not perceptible                  | RN's old async bridge was its weak spot; the new JSI/Fabric architecture fixed it by going synchronous. A Scala↔native call must be a direct function call, not message passing |

Implications: Scala Native's AOT binaries and Android's R8 shrinking put the
targets within reach, but *nothing is assumed* — start-up, frame time and size are
spike deliverables in `08-roadmap-and-spikes.md`.

## 2.6 The "why would anyone switch" test

A framework only becomes popular if it offers something the incumbents do not.
The candidate differentiators for a Scala framework:

1. **Genuinely native look on all five platforms** including desktop, where Flutter
   and Compose are visibly non-native and RN desktop lags.
2. **Scala 3's type system** for UI state: exhaustive enums for UI state machines,
   opaque types for units, union types for props, compile-time checked resource
   references — Elm-style safety without leaving a mainstream ecosystem.
3. **Server/client code sharing** for teams already on Scala/ZIO/Caliban.
4. **No JS engine, no webview, no VM** in the shipped binary (except Android's ART,
   which is the platform's own runtime).

None of these matter if the dev loop is slow. Hot reload is therefore a P0
requirement, not a nice-to-have.

## Sources

- https://tech-insider.org/flutter-vs-react-native-2026/
- https://quashbugs.com/blog/flutter-vs-react-native-statistics
- https://www.techqware.com/blog/kotlin-multiplatform-vs-flutter-vs-react-native-what-to-choose
- https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/
- https://platform.uno/articles/react-native-windows-macos-versions-vs-dotnet-lts/
- https://microsoft.github.io/react-native-windows/support/
- https://v2.tauri.app/
- https://viadreams.cc/en/blog/tauri-guide/
- https://github.com/raquo/Airstream/discussions/84
