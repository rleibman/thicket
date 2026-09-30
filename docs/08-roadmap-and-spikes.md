# 8. Roadmap and go/no-go spikes

Nothing in Option B should be built until the spikes pass. Each spike has a
bounded budget and an explicit exit criterion. Budgets assume one experienced
person part-time; treat as rough.

## 8.1 Spikes (phase 0, ~6–8 weeks total, parallelisable)

| ID     | Question                                    | Method                                                                                                                                                                                                                                                    | Pass criterion                                                                                                                                                | Budget | If it fails                                                                                           |
|--------|---------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|-------------------------------------------------------------------------------------------------------|
| **S1** | Does Scala Native run on iOS?               | Build `libraryStatic` with `@exported thicket_main` for `arm64-apple-ios` and `arm64-apple-ios-simulator`; link into a SwiftUI hello app; call Scala from the main thread; allocate heavily to exercise Immix; spawn a Scala thread and post back to main | Runs on a physical iPhone and the simulator; no GC crash after 10 min allocation loop; `.a` size recorded; LLDB shows Scala frames                            | 2 wk   | Try Boehm/none GC, then upstream issue with repro; if unfixable in 4 more weeks → Option A for mobile |
| **S2** | Is Scala 3 on Android viable in 2026?       | Gradle app with a Scala 3 library module (Zinc via Gradle `scala` plugin or sbt-built AAR); R8 on; Scala `Activity` with a Button; min SDK 26; AGP 9                                                                                                      | Release APK builds; runs on emulator + device; APK delta vs Kotlin hello ≤ 4 MB (N-03); cold start ≤ 500 ms; Apply Changes works after editing a Scala method | 2 wk   | Document blockers; evaluate Scala Native via NDK + JNI as fallback; else Option A                     |
| **S3** | Scala Native ⇄ Swift shim ergonomics & cost | Extend S1: `sui_button_new/on_tap`; Scala closure fired from UIKit; measure round-trip; check GC handle/pinning story for callback contexts                                                                                                               | Button tap → Scala → label update visible; round-trip ≤ 5 µs (N-05); no leaks over 100k taps                                                                  | 1 wk   | Redesign callback scheme (handle table)                                                               |
| **S4** | Yoga from Scala                             | sn-bindgen over Yoga's C API on Native; Java Yoga on Android; lay out a 1 000-node tree                                                                                                                                                                   | Bindings compile on macOS/Linux; layout of 1 000 nodes < 2 ms; JVM path chosen                                                                                | 1 wk   | Write a minimal Flexbox in Scala (Yoga is ~10k lines; a subset is feasible)                           |
| **S5** | Cross-platform signals prototype            | Implement Var/computed/effect/batch; property tests for glitch-freedom; benchmark 10k-signal graph on JVM/JS/Native                                                                                                                                       | Passes tests on all three backends; propagation ≤ 1 µs/node on Native                                                                                         | 1 wk   | n/a (design choice, not a gate)                                                                       |
| **S6** | Calibration baseline                        | Option D: ScalaFX hello via GraalVM + Gluon Substrate on iPhone; and Option A: Slinky-native/Expo hello                                                                                                                                                   | Sizes, startup, effort recorded for comparison tables                                                                                                         | 1 wk   | n/a                                                                                                   |
| **S7** | GTK4 renderer smoke                         | Use `com.indoorvivants.gnome::gtk4`; counter app                                                                                                                                                                                                          | Runs on Linux; informs renderer contract                                                                                                                      | 3 d    | n/a                                                                                                   |

Go decision = S1 ∧ S2 ∧ S3 pass. Partial pass (e.g. S1 passes only with Boehm GC)
is a *go with risk noted*.

## 8.2 Milestones (post-go)

| Milestone                                   | Scope                                                                                                                                             | Exit criteria                                                                                                     |
|---------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| **M0 — Skeleton** (1 mo)                    | Repo, cross-build, CI matrix, `renderer-api`, `signals` (from S5), `core` element tree with Column/Row/Text/Button, `renderer-dom` dev canvas     | Counter app runs in the browser with < 1 s reload; API ergonomics reviewed with 3 external Scala devs             |
| **M1 — First native pixel** (3 mo)          | `renderer-apple` (iOS) with the v1 layout + 6 controls; `layout-yoga` on Native; Xcode project generator; Option F usable (call Scala from Swift) | Counter + form app on a physical iPhone; startup/size within N-01/N-03; VoiceOver reads controls                  |
| **M2 — Android parity** (3 mo)              | `renderer-android`, Gradle shell generator, Yoga on JVM, R8 rules                                                                                 | Same gallery app on Android; TalkBack works; Apply Changes loop ≤ 30 s                                            |
| **M3 — Desktop** (3 mo)                     | macOS (reuse shim), GTK4, Win32 proof; menus, windows, toolbar; desktop packaging                                                                 | Gallery on 3 desktops; native menu bar on macOS; GTK header bar                                                   |
| **M4 — Catalogue & navigation** (4 mo)      | Full 7.10 catalogue; NavigationStack/TabView/List virtualisation/Sheet/Alert; images; ZIO & cats-effect adapters                                  | 10k-row list at 60 fps everywhere (N-02); a real sample app (e.g. a meal planner client against a Caliban server) |
| **M5 — DX & 0.1 release** (2 mo)            | `thicket` CLI, `doctor`, docs site (built with the dev canvas), inspector v0, semver policy, contributor guide                                   | 15-minute getting started verified by a newcomer per platform; 0.1.0 on Maven Central                             |
| **M6 — Windows WinUI 3 & hardening** (4 mo) | WinUI 3 shim; perf work; crash symbolication; LTS planning                                                                                        | Feature parity across five platforms; 1.0 candidate                                                               |

> **Estimate recalibration (2026-09-19).** The spike budgets above were wrong by a large
> factor: phase 0 was budgeted at 6–8 weeks and took roughly one working day across two
> machines. The milestone figures below were produced by the same reasoning and should be
> treated as equally inflated. They are left in place only as *relative* sizing — M4 really is
> larger than M1 — and the project should be steered by working software rather than by this
> table. Concretely: build the smallest thing that runs, on one platform, and re-estimate from
> the actual slope.

Total ≈ 20 months to a 1.0 candidate with 2–3 consistent contributors; roughly
half that to a credible 0.1 on iOS + Android + macOS. **See the recalibration note above —
do not plan against these numbers.**

## 8.3 Measurement plan

The gallery app is instrumented from M0: startup timers, frame-time histograms,
binary size, RSS; CI publishes them per commit so regressions are visible.
Fidelity acceptance = the Compose-Multiplatform-iOS checklist (native text
selection, scroll physics, gestures, dynamic type, dark mode, RTL) run manually
each milestone and automated where the platform allows (XCUITest, Espresso).

## 8.4 What to do this month

1. Run S1 and S2 in parallel — they are independent and they decide everything.
2. Run S5 (signals) as the design-warm-up; it has no external dependencies.
3. Write the renderer contract draft while S1/S2 run; validate it against S7 (GTK).
4. Book S6 calibration builds so the comparison tables have numbers instead of
   adjectives.
