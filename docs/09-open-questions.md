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

## 9.4 Not yet researched (deliberately deferred)

- Push notifications, deep links, background tasks, app extensions.
- Localisation pipeline and typed resources (A-09).
- Testing story for app authors (headless renderer? snapshot tests on the dev canvas?).
- Distribution of the shims: SwiftPM package + prebuilt XCFramework vs source.
- Wasm as a fourth Scala backend, should Scala.js's Wasm output mature.
