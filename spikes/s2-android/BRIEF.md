# S2 — Scala 3 on Android with R8 (2026) — BRIEF

**Machine:** Linux. **Budget:** ~2 weeks. **Depends on:** nothing. **Gate:** YES
(go/no-go for the Android renderer path in Option B).

## Question
Can a Scala 3 (LTS) library be compiled, shrunk with R8 and run inside a modern
Android app (AGP 9.x, min SDK 26), with acceptable APK size, cold start and an
edit→run loop? Which integration shape gives the least-surprising developer
experience?

## Prerequisites (ask the user before each install)
1. JDK 21: `cs java --jvm temurin:21 --setup` or use `cs java-home --jvm temurin:21`
   for `JAVA_HOME` in the spike's `gradlew` invocation. Do not use the JDK 23-ea on PATH.
2. Android SDK command-line tools → `~/Android/Sdk`; `sdkmanager "platform-tools"
   "platforms;android-<latest>" "build-tools;<latest>" "emulator"
   "system-images;android-<latest>;google_apis;x86_64"`. Accept licences.
   Set `ANDROID_HOME`. Create an AVD (x86_64, API latest).
3. KVM available? (`ls /dev/kvm`). If not, the emulator will be unusably slow —
   report and coordinate with the user for a physical Android device over `adb`
   (the user has one).

## Deliverables
1. `spikes/s2-android/` containing:
   - `scala-lib/` — **sbt** project, Scala 3 LTS, `-release 8` target (or the lowest
     that works — report), producing a JAR. Contents: a `MainActivity` written in
     Scala (extends `android.app.Activity`; compile against `android.jar` from the
     SDK as `Provided`), building a `LinearLayout` with a `TextView` and a `Button`
     programmatically; a counter in a plain `var`; deliberately use Scala 3 features
     that stress the runtime: `lazy val`, `enum` with pattern matching, an `opaque
     type`, a `given`, a `LazyList`, a `scala.collection.immutable.TreeMap`,
     string interpolation, a `Future` on the global EC, `Ordering`, and a
     `scala.util.Try`. No reflection.
   - `app/` — **Gradle** Android application (Kotlin DSL) with AGP latest stable,
     that consumes the JAR (`implementation(files(...))` or a local Maven repo
     published by sbt — try both, recommend one). R8 enabled for `release`,
     `isMinifyEnabled = true`, `isShrinkResources = true`. Keep rules in
     `scala3.pro` (start empty; add only what R8 demands; every rule commented with why).
   - Also try shape B: Gradle `scala` plugin compiling the Scala sources directly
     inside the Android module (via a JVM library module). Report whether it works
     with AGP 9 or is blocked (historically it is). One paragraph is enough if blocked.
2. Measurements in `REPORT.md`:
   - release APK size **delta** vs the same app with the Activity written in Kotlin
     (build the Kotlin twin in `app-kotlin/`); target ≤ 4 MB (N-03).
   - dex method count contributed by Scala after R8.
   - cold start: `adb shell am start -W` ×10, median `TotalTime`; compare with Kotlin twin. Target ≤ 500 ms (N-01) on the emulator *and* note emulator ≠ device.
   - edit→run: change the button label in Scala, re-run `sbt --error package` + `gradlew installDebug` (or Apply Changes from Android Studio if installed); wall-clock seconds. Target ≤ 30 s (D-01 tier 3).
   - runtime: does `lazy val` work (Scala 3 `LazyVals` uses `Unsafe`/`VarHandle` depending on version)? Does `Future` on the global EC run? Any `NoClassDefFoundError`/`VerifyError` in `logcat`?
3. A minimal `README.md` in the spike: exact commands to reproduce from a clean machine.

## Pass criteria
- Release APK installs and runs on emulator (and device if available); button works; all stress features behave; `logcat` clean of Scala-related errors.
- APK delta ≤ 4 MB; cold start within 20% of Kotlin twin; edit→run ≤ 30 s.

## PASS-WITH-RISK if
- Delta 4–8 MB, or specific features needed keep-rules/workarounds — document each.

## FAIL if
- R8/ART cannot handle the Scala 3 stdlib at all, or cold start > 2× Kotlin.
  Then write the root cause and check whether `-release 11`/`-release 17` or a
  newer/older Scala 3 changes it, before concluding.

## Do not
- Use any Scala UI library, ZIO, or cats. Stdlib only — this measures the *floor*.
- Spend time on IDE integration; note observations only.
