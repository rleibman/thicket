# S2 — Scala 3 on Android with R8 (2026) — REPORT

**Date:** 2026-09-18 · **Machine:** Linux (Ubuntu), KVM present **Versions:** Scala **3.9.0** · sbt **2.0.9** · JDK
**temurin 21.0.12.1** · Gradle **9.7.1**
· AGP **9.4.1** · compileSdk/targetSdk **36** · minSdk **26** · build-tools 37.0.0
· emulator: `system-images;android-36;google_apis;x86_64`, Pixel 6 profile, headless

## Result: **PASS-WITH-RISK**

Scala 3 runs correctly on modern Android and the cost is far smaller than the plan
assumed — **75 KB** of APK against a 4 MB budget. But it does **not** work out of the box:
two non-obvious build settings are mandatory, and without them the app crashes on launch
with a reflective-field error that gives no hint about its cause. Cold start is 40% slower
than the Kotlin twin, which misses the brief's relative sub-criterion while meeting the
absolute one.

## Measurements

| Criterion                      | Target                   | Measured                                      | How                                                                                 |
|--------------------------------|--------------------------|-----------------------------------------------|-------------------------------------------------------------------------------------|
| Release APK size (Scala)       | —                        | **102 230 B**                                 | `stat` on `app-scala-release.apk`                                                   |
| Release APK size (Kotlin twin) | —                        | **26 910 B**                                  | same                                                                                |
| **APK delta**                  | **≤ 4 MB (N-03)**        | **75 320 B (≈ 74 KB)** — **53× under budget** | difference                                                                          |
| Dex methods (Scala / Kotlin)   | —                        | **1 783 / 187** → +1 596                      | `apkanalyzer dex references`                                                        |
| **Cold start, Scala**          | **≤ 500 ms (N-01)**      | **457 ms** median of 10                       | `am start -W -S`, `TotalTime`, force-stop between runs                              |
| Cold start, Kotlin twin        | —                        | **327 ms** median of 10                       | same                                                                                |
| Cold start ratio               | within 20% of Kotlin     | **1.40× (+130 ms)** — **misses**              | derived                                                                             |
| **Edit → running on device**   | **≤ 30 s (D-01 tier 3)** | **17.2 s**                                    | one-line edit, `sbt package` + copy jar + `gradlew installDebug`, all `--no-daemon` |
| Runtime correctness            | no errors                | **clean**                                     | see below                                                                           |
| Release build (Scala, warm)    | —                        | 14–16 s                                       | `gradlew assembleRelease --no-daemon`                                               |

Emulator, not a physical device. Absolute cold-start numbers on real hardware will differ;
the **ratio** is the transferable figure.

### Runtime correctness

The activity renders and updates correctly. Read back from the live UI via `uiautomator`:

```
on launch:        count=0 mood=Calm    fib=0 sorted=Calm,Curious,Excited
after 4 taps:     count=4 mood=Curious fib=3 sorted=Calm,Curious,Excited
logcat:           I S2: Future completed: 500500
```

That single line exercises, and validates on ART: `opaque type` (`Count`), `enum` + exhaustive
matching (`Mood`), a user-supplied `given Ordering[Mood]` driving `.sorted`, `lazy val`,
`LazyList` (the self-referential Fibonacci definition), `TreeMap`, `BigInt`, `Try`, string
interpolation, and `Future` on `ExecutionContext.global`. No `VerifyError`, no
`NoClassDefFoundError`, no `NoSuchMethodError`.

## The two mandatory fixes

Both are required. Each alone still crashes, with a *different* class failing.

### 1. `android.enableR8.fullMode=false`

R8 full mode is the **default** since AGP 8. With it on, the app dies at launch:

```
java.lang.ExceptionInInitializerError
Caused by: java.lang.NoSuchFieldException: No field cache$lzy1 in class Ly0;
  at java.lang.invoke.MethodHandles$Lookup.findVarHandle
```

`y0` de-obfuscates (via `mapping.txt`) to **`scala.math.BigDecimal$`**, and R8 had stripped *every* field from it. Scala
3 compiles `lazy val x` into a backing field `x$lzy1` plus a
`VarHandle` obtained in `<clinit>` by **reflective lookup on the field's name**. R8 full mode
cannot see that reference, removes the field, and keeps the `<clinit>` that looks for it.
Crucially, `-keepclassmembers` **does not save it in full mode** — the rule is present in
`configuration.txt` and is ignored.

### 2. Keep rule for lazy-val backing fields

```proguard
-keepclassmembers class ** {
    *** *$lzy*;
}
```

Without it (even with full mode off) the app crashes on *our own* class:
`No field moodNames$lzy1 in class Lscalaui/s2/MainActivity`.

`-keepclassmembernames` is **not** sufficient: it only prevents renaming, so R8 still removes
a field that nothing references statically, and the library-side crash returns. Class names may
still be obfuscated — `findVarHandle` takes a `Class` object, not a name.

Cost of the two fixes: APK 81 998 B → **102 230 B** (+20 KB, +25%). Still trivially within budget.

### 3. Not mandatory, but you will ship 3.8 MB of garbage without it

`scala3-library` ships **~7.5 MB of `.tasty` files as jar resources**. R8 shrinks *code*;
`isShrinkResources` only touches Android `res/`. Nothing removes them, so the first working
release APK was **3 885 561 B**, of which 3.8 MB was TASTy metadata (`scala/quoted/Quotes.tasty`
alone is 254 KB) — while the dex was only 141 KB. TASTy is compile-time only:

```kotlin
packaging { resources { excludes += setOf("**/*.tasty", "rootdoc.txt", "library.properties") } }
```

**3 885 561 B → 81 314 B, a 48× reduction.** Anyone doing Scala-on-Android naively ships this.

## Other findings

- **Scala 3.9 cannot target Java 8, 9, 11 or 16.** `-java-output-version` accepts **17 minimum**
  (`"11 is not a valid choice for -java-output-version"`). Emitted bytecode is major version 61.
  This is fine for AGP 9 with `JavaVersion.VERSION_17` and did not require core library
  desugaring at minSdk 26, but it forecloses supporting old toolchains.
- **Shape B (Gradle's `scala` plugin inside an Android module) is impossible**, as suspected:
  ```
  Failed to apply plugin class 'org.gradle.api.plugins.JavaPlugin'.
  Caused by: Cannot add a configuration with name 'implementation' as a configuration with
             that name already exists.
  ```
  Gradle's `scala` plugin applies `JavaPlugin`, which collides with AGP's own configurations.
  So **the sbt-builds-a-JAR, Gradle-consumes-it shape is the only one available**, and the
  framework's tooling (P-05) must own that hand-off rather than hoping for a Gradle plugin.
- **AGP 9 rejects the Kotlin plugin.** `org.jetbrains.kotlin.android` now errors out ("no longer required for Kotlin
  support since AGP 9.0", issuetracker 438678642); Kotlin
  support is built in.
- **sbt 2: `unmanagedJars` cannot take a `File`.** Classpath entries are `HashedVirtualFileRef`,
  so neither `Attributed.blank(file(...))` nor `fileConverter.toVirtualFile(...)` type-checks.
  Fell back to sbt's `lib/` convention with a symlink created by `setup.sh`.
- **sbt 2 packages artifacts as symlinks into a content-addressed store**
  (`~/.cache/sbt/v2/cas/...`). Any script copying a built JAR must `readlink -f` it, and a
  `package` immediately after a failed compile can hand back a stale 276-byte empty jar.
- **The emulator worked fine on x86_64 with KVM.** Note this is only true because this spike has
  no native code; S4 found the Yoga AAR ships no x86_64 ABI, so a Yoga-using app will need an
  arm64 device or a self-built native library.

## What was built

```
spikes/s2-android/
  scala-lib/                       sbt 2 project -> plain JAR (9 classes, 23 KB)
    build.sbt, setup.sh            setup.sh symlinks android.jar into lib/
    src/main/scala/scalaui/s2/MainActivity.scala
  android/                         Gradle 9.7.1 + AGP 9.4.1
    settings.gradle.kts, build.gradle.kts, gradle.properties  (enableR8.fullMode=false)
    libs/s2-scala-lib.jar          copied from the sbt build
    app-scala/     build.gradle.kts, scala3.pro (the keep rule), AndroidManifest.xml
    app-kotlin/    the size/startup twin
  measure.sh                       installs both APKs, 10x cold start each, logcat checks
```

## Recommendations for the plan

1. **`docs/04` §4.2 — replace the Android section's speculation with these facts.** The three
   settings (`enableR8.fullMode=false`, the `*$lzy*` keep rule, the `.tasty` exclusion) are not
   optional and must be generated by the framework's Gradle shell (P-04), never left to users.
   Two of the three fail at *runtime*, not build time, with errors that name no Scala concept.
2. **`docs/05` N-03 is comfortably met and N-01 is met on the emulator.** Update the risk table:
   "Scala 3 stdlib on Android bloats APK" was rated Medium likelihood — measured at **74 KB**, it
   should be downgraded to Low, *conditional on the TASTy exclusion*.
3. **Open the cold-start ratio as a real question.** 1.40× Kotlin (+130 ms) meets the absolute
   500 ms bar but not the "within 20%" bar. Likely causes: Scala's class-init graph and
   `lazy val` VarHandle setup running at `<clinit>`. **Measure on a physical device at M2**, and
   consider Baseline Profiles (AGP supports them) before treating this as inherent.
4. **`docs/07` §7.9 — the sbt→AAR/JAR hand-off is the only viable shape.** Record that Gradle's
   `scala` plugin cannot coexist with AGP, so the tooling story is "sbt/mill builds the artifact,
   a generated Gradle project consumes it", exactly as §7.9 proposed. This is now verified, not assumed.
5. **Watch `lazy val` in framework code.** Every `lazy val` the framework ships adds a reflective
   `findVarHandle` at class-init. That is both the crash risk above and a cold-start cost. The
   signals module (S5) uses none; keep it that way where practical, and consider a lint rule.
6. **Add to `docs/decisions.md`:** minimum Java output version for Scala 3.9 is **17**.

## Go/no-go contribution

S2 says **go** for the Android renderer: the runtime is correct, the size cost is negligible,
the build shape is workable, and every problem found has a known, mechanical fix that the
framework's own tooling can apply. The residual risks are cold-start ratio (measurable, likely
improvable) and the fact that a naive setup fails loudly in three different ways — which is a
documentation and tooling burden, not a technical blocker.


---

## Correction (2026-09-21): the cold-start ratio was measurement noise

This report concluded that Scala's cold start is **1.40× the Kotlin twin** (457 ms vs 327 ms)
and flagged it as a risk. **That conclusion does not hold.** Re-measured with both APKs plus
the scala-ui todo app installed together and launched **round-robin in a single emulator
session**, 10 rounds each:

| app | median | mean | min | max |
|---|---|---|---|---|
| S2 Kotlin (bare) | 418 ms | 417.7 | 355 | 462 |
| S2 Scala (bare) | **386 ms** | 383.4 | 368 | 396 |
| scala-ui todo (framework + list + conditional) | 388 ms | 390.1 | 358 | 411 |

Kotlin came out **slowest**. The spread within a single app (355–462 ms for Kotlin) is wider
than the gap this report attributed to Scala, so the original 327-vs-457 figure was
**between-session emulator variance**, not a language cost. Re-running after
`cmd package compile --reset`, i.e. in the fresh-install `verify` state, changed nothing —
so it is not an ART dexopt warm-up effect either.

**Methodology lesson, which is the durable part:** cold-start numbers from an Android emulator
are only comparable when the variants are **installed together and launched interleaved in one
session**. Measuring app A, then rebuilding, then measuring app B — which is what this report
did — compares two emulator states, not two apps. Any future N-01 claim must be interleaved,
and ideally taken on a physical device.

What survives: the *absolute* figure is comfortably inside N-01's 500 ms, and every other
measurement in this report (APK size, dex counts, the three mandatory build settings, the
runtime-correctness findings) was a single-session or build-time measurement and is unaffected.
