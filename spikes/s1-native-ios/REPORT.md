# S1 — Scala Native on iOS — REPORT

**Date:** 2026-09-19
**Machine:** macOS (Darwin 27.0.0), Apple Silicon arm64, MacBook laptop
**Target:** iOS **simulator** only (user decision 2026-09-18 — no iPhone available)

| Tool              | Version used                                                                                            |
|-------------------|---------------------------------------------------------------------------------------------------------|
| Xcode             | 27.0 (build 27A266a)                                                                                    |
| iOS SDK           | iPhoneSimulator27.0.sdk (`--show-sdk-version` → 27.0)                                                   |
| Simulator runtime | iOS 27.0 (27.0 – 24A434), device "iPhone 17"                                                            |
| clang             | **Apple clang 21.0.0** (clang-2100.3.34.2), from `/Applications/Xcode.app/.../XcodeDefault.xctoolchain` |
| sbt               | 2.0.9                                                                                                   |
| Scala             | 3.9.0                                                                                                   |
| Scala Native      | 0.5.12 (`sbt-scala-native`, `nscplugin_3.9.0`)                                                          |
| JDK running sbt   | OpenJDK 23.0.2 (temurin 21.0.12.1+1 also installed via `cs`)                                            |

Versions re-resolved at spike start per `docs/decisions.md`: `cs complete-dep org.scala-lang:scala3-compiler_3:`
confirmed 3.9.0 is latest stable (3.10.0 is RC); `nscplugin_3.9.0` publishes 0.5.12.

---

## Result: PASS-WITH-RISK

**The gate question is answered YES.** A Scala Native 0.5.12 static library cross-compiles
for iOS, links into a SwiftUI app, starts its runtime on the iOS simulator, and survives
10 minutes of allocation churn and threading load. Every pass criterion in the brief was met.

It is **PASS-WITH-RISK**, not a clean PASS, for three reasons, each detailed below:

1. `java.lang.Thread` **does not link for iOS at all** without a build workaround for an
   upstream Scala Native bug. One line of config; but without it, nothing threaded links.
2. **Only one GC works.** `immix` succeeds; `commix` and `boehm` do not compile for iOS.
   There is no fallback if immix ever misbehaves on a real device.
3. **Scala code cannot be called from a GCD queue.** Doing so segfaults inside the GC
   allocator. This is an architectural constraint on every Apple renderer we build.

**Device coverage is explicitly absent.** Everything below is the simulator. Code signing,
arm64-only codegen and real memory pressure are deferred to M1, per the 2026-09-18 decision.
The device *triple* does link (see below), which is a positive signal but not a run.

---

## Measurements

### Pass criteria from the brief

| Criterion                         | Target        | Measured                                               | How                                                                                   |
|-----------------------------------|---------------|--------------------------------------------------------|---------------------------------------------------------------------------------------|
| All five exported functions work  | all           | **all 5 pass**                                         | CLI harness + SwiftUI app self-test, both on simulator                                |
| Alloc stress without crash        | 10 min, ≥1 GC | **600.0 s, 779,511,000 objects, no crash** (immix)     | `run-sim.sh immix 600`                                                                |
| Threads work                      | correct       | **checksum 1399990**, matches independent computation  | `scalaui_thread_test(8)`; expected value computed separately in Python                |
| Runtime init                      | ≤ 50 ms       | **≈0.6 ms** (0.147 init + 0.468 first call), cold boot | `clock_gettime(CLOCK_MONOTONIC)` around `ScalaNativeInit()` / first `scalaui_hello()` |
| App binary after strip            | ≤ 6 MB (N-03) | **1.90 MB** (1,987,848 B) release, stripped            | `strip -x`, `stat -f%z`                                                               |
| Scala frames readable in debugger | readable      | **yes, mangled but legible**                           | `nm`; crash report backtrace                                                          |

### Memory under 10-minute stress (immix)

RSS was sampled between twenty 30-second chunks. It did not move:

| elapsed  | 30 s   | 120 s   | 300 s   | 450 s   | 600 s   |
|----------|--------|---------|---------|---------|---------|
| RSS (MB) | 13.53  | 13.53   | 13.53   | 13.53   | 13.53   |
| objects  | 38.9 M | 155.3 M | 390.5 M | 586.8 M | 779.5 M |

Peak RSS 13.53 MB (`ru_maxrss`), i.e. the high-water mark equals the steady state — immix
collected everything it should, with zero drift over three quarters of a billion objects.

### GC matrix (iOS simulator, arm64)

| GC                  | Result                   | Evidence                                                                              |
|---------------------|--------------------------|---------------------------------------------------------------------------------------|
| **immix** (default) | **WORKS**                | 10 min stress, RSS flat at 13.53 MB                                                   |
| commix              | **fails to compile**     | `gc/commix/Phase.c:33:10: fatal error: 'sys/posix_sem.h' file not found`              |
| boehm               | **fails to compile**     | `gc/boehm/gc.c:7:10: fatal error: 'gc/gc.h' file not found` (bdwgc not built for iOS) |
| none                | links and runs, unusable | RSS 9.06 MB → **4,026 MB in 10 s**, peak 4,300 MB                                     |

### Target triples

All four built; platform verified per object file with `vtool -show-build` (not `lipo`, which
cannot distinguish macOS-arm64 from iOS-simulator-arm64).

| Project                  | Triple                           | Mach-O platform | arch   | minos | `.a` size   |
|--------------------------|----------------------------------|-----------------|--------|-------|-------------|
| `host` (control)         | *(none)*                         | MACOS           | arm64  | 27.0  | 4,513,264 B |
| `simArm64` **(primary)** | `arm64-apple-ios17.0-simulator`  | IOSSIMULATOR    | arm64  | 17.0  | 4,493,968 B |
| `simX86`                 | `x86_64-apple-ios17.0-simulator` | IOSSIMULATOR    | x86_64 | 17.0  | 4,197,320 B |
| `device`                 | `arm64-apple-ios17.0`            | **IOS**         | arm64  | 17.0  | 4,532,432 B |

**The device triple links cleanly.** The brief expected a link failure there to be a finding;
the opposite is the finding. Nothing ran it, so this says nothing about device *runtime*.

### Sizes and speed

| Thing                    | debug        | releaseFast               |
|--------------------------|--------------|---------------------------|
| `.a`                     | 4,493,968 B  | 4,103,616 B               |
| app binary, unstripped   | 3,054,072 B  | 2,599,128 B               |
| app binary, **stripped** | 2,685,080 B  | **1,987,848 B (1.90 MB)** |
| allocation rate          | ~1.3 M obj/s | ~3.1 M obj/s              |

### Link time (the DX risk in `04` §4.3)

| Operation                                          | Time      |
|----------------------------------------------------|-----------|
| clean `nativeLink`, sbt server warm                | **2.5 s** |
| incremental after a real one-line source change    | **4.1 s** |
| incremental, previously-seen content (sbt CAS hit) | 1.7 s     |
| `releaseFast` link                                 | 11 s      |

For a library this size the DX risk is **not** realised. This is 88 lines of Scala, so these
are floor numbers, not a projection for the full framework — most of the link time here is
Scala Native's own runtime, which is a fixed cost the framework will also pay.

### Debugger / symbols

Scala symbols survive into the archive and into crash reports. Scala Native's mangling keeps
the class and method name readable:

```
__SM19scalaui.s1.Exports$D5helloL28scala.scalanative.unsafe.PtrEO
__SM19scalaui.s1.Exports$D10threadTestiiEO
__SM19scalaui.s1.Exports$D11allocStressijEO
```

47 `scalaui`-prefixed symbols in total. A real crash backtrace (below) named
`Allocator_Alloc` in the Scala Native runtime, so Apple's crash reporter symbolicates the
runtime too. **DWARF debug info is off by default** (`sourceLevelDebuggingConfig: Disabled`);
`withSourceLevelDebuggingConfig` is needed for line-level stepping — not exercised here.

---

## What was built

```
spikes/s1-native-ios/
  build.sbt                          four sbt projects, one per target triple
  project/{build.properties,plugins.sbt}
  run-sim.sh                         build .a for a GC, link harness, run in simulator
  scala-lib/src/main/scala/scalaui/s1/Exports.scala    the five @exported functions
  harness/main.c                     C harness: timings, RSS sampling, chunked stress
  ios-app/
    Sources/App.swift                SwiftUI app; runs a self-test on launch
    Sources/ScalaUI-Bridging-Header.h
    Info.plist
    build-app.sh                     swiftc + simctl install/launch
```

Reproduce with `./run-sim.sh immix 600` and `cd ios-app && ./build-app.sh`.

**Deviation from the brief:** `ios-app/` is assembled with `swiftc` + `simctl` rather than an
`.xcodeproj`. A hand-written `.pbxproj` that cannot be verified by opening Xcode's GUI would
be worse evidence than a script anyone can re-run. The app is a genuine `.app` bundle,
installed and launched in the simulator, and it exercises the same path an Xcode build would.
If an `.xcodeproj` is wanted for M1, generate it with XcodeGen rather than by hand.

---

## Problems hit

### 1. `java.lang.Thread` does not link for iOS — upstream Scala Native bug (**solved**)

```
Undefined symbols for architecture arm64:
  "_pthread_condattr_setclock", referenced from:
      __SM27java.lang.impl.PosixThread$RE in libs1-simarm64.a
```

Root cause, traced through the sources:

- `TargetTriple.scala:322` parses `...-ios17.0-simulator` to `target.os == "ios"`.
- javalib's `LinktimeInfo.isMac` is `target.os == "darwin" || target.os == "macosx"` → **false**.
- `PosixThread.scala:405` then takes the non-Apple branch:
  `if (isMac || isFreeBSD) false else { pthread_condattr_setclock(...); true }`.
- No Apple platform provides `pthread_condattr_setclock` — it is absent from both the iOS
  and macOS SDK headers.

Scala Native **contradicts itself** here: its own toolchain-side `Config.targetsMac` is
`Seq("mac","apple","darwin").exists(triple.contains)`, which is correctly `true` for the iOS
triple. Only the javalib-side check disagrees. The macOS control build links fine, which
isolates it to the iOS triple rather than to Apple platforms generally.

**Fix applied.** `LinktimeValueResolver.scala` ends with `predefined ++ conf.linktimeProperties`,
so a user property wins:

```scala
.withLinktimeProperties(c.linktimeProperties ++
  Map("scala.scalanative.meta.linktimeinfo.target.os" -> "darwin"))
```

iOS genuinely is Darwin, so this makes the two checks agree rather than papering over them.
**Upstream fix should be `isMac` accepting `ios`/`tvos`/`watchos`, or an `isApple` predicate.**
Minimal repro and issue text: any `libraryStatic` build with an iOS triple that touches
`java.lang.Thread`. Not filed — handing to the user per the brief.

### 2. Calling Scala from a GCD queue segfaults (**not solved — architectural**)

The SwiftUI app crashed when the self-test ran `scalaui_alloc_stress` on
`DispatchQueue.global()`. From the crash report:

```
exception: EXC_BAD_ACCESS (SIGSEGV), KERN_INVALID_ADDRESS at 0x00000000000000f0
faultingThread: 1, queue = com.apple.root.default-qos
  S1App  Allocator_Alloc  0xf5a40
  S1App  thunk for @escaping @callee_guaranteed @Sendable () -> ()
  libdispatch.dylib  _dispatch_call_block_and_release
```

Scala Native's GC keeps per-thread allocator state and only knows threads it created itself.
`ScalaNativeGC.h` is explicit — `scalanative_GC_pthread_create` exists to "register newly
created thread upon startup", and **there is no API to attach an already-running foreign
thread**. A GCD worker has no mutator state, so `Allocator_Alloc` dereferences null.

What works, verified: the **main thread** (the one that ran `ScalaNativeInit`), and **threads
Scala created** (`scalaui_thread_test(8)` spawns 8 `java.lang.Thread`s and returns the correct
checksum, including after the 10-minute stress).

This is the single most consequential finding for the plan. See recommendations.

### 3. javalib gaps, invisible until link time (**worked around**)

The brief asked `scalaui_time` to use `java.time.Instant.now`, `java.util.Locale` and
`String.format(Locale, …)`. None of it links. Scala Native 0.5.12's javalib jar contains only
`java/{io,lang,math,net,nio,security,util}` — **no `java.time`, no `java.text`, and no
`java.util.Locale`**. The `Locale`-taking `String.format` overload drags in
`java.text.NumberFormat` and `java.text.DecimalFormatSymbols` and so fails too.

Substituted `java.util.Date` + root-locale `String.format`, both of which work.

The DX problem is worse than the gap itself: **these compile fine and only fail at
`nativeLink`**, with an "Unreachable symbols found after classloading run" dump. This is a
portability trap for the whole framework, not an iOS issue — it would fail identically on Linux.

### 4. Two sbt 2 traps that silently built the wrong thing (**solved**)

Both produced *successful* builds of the wrong target, which is the dangerous kind of failure.

- **Env vars are captured when the sbt server starts.** `S1_TARGET=sim-arm64 sbt …` reused a
  server started earlier with `S1_TARGET=host` and cheerfully rebuilt the host target. The
  artifact was byte-identical to the host build; only `vtool` caught it.
- **Building projects from a helper `def` makes them share one project's settings.** With
  `def targetProject(id, triple, sdk)`, all four projects reported
  `targetTriple = Some(arm64-apple-ios17.0-simulator)` — including the one that should have
  been `None`. The `:=` macro does not capture the `def`'s parameters per call.

Fixed by giving each target its own explicitly-spelled-out `Project` with literal values.
Note also that `lipo -info` reports plain `arm64` for both macOS and iOS-simulator slices —
**`vtool -show-build` is the only reliable platform check.**

### 5. `-target`/`-isysroot` must go in `compileOptions`, not just the triple

`withTargetTriple` steers LLVM codegen for Scala, but Scala Native compiles its own bundled
C and assembly (GC, unwinding, libc glue) by invoking clang directly, and those ignore it.
Both flags must be added to `compileOptions` *and* `linkingOptions`.

---

## Recommendations for the plan

1. **`docs/decisions.md` — record that iOS builds require the linktime `target.os` override.**
   Add to the iOS/macOS row: iOS targets must set
   `scala.scalanative.meta.linktimeinfo.target.os = "darwin"`, or `java.lang.Thread` will not
   link. This belongs beside the existing Android "three mandatory settings" note, because it
   is exactly the same class of thing.

2. **`docs/07-technical-design-ideas.md` §7.13 and the S3/S8 briefs — adopt a hard rule:
   Scala code runs only on the main thread or on Scala-created threads, never on a GCD queue.**
   Concretely, the Swift shim's `sui_run_on_main` must be the *only* direction that crosses
   threads: Swift → Scala calls happen on the main thread; Scala → UI work is posted via
   `dispatch_async(main)`. Any design that hands a Scala callback to a GCD worker, a
   `Task.detached`, or a Swift concurrency executor will segfault. **S8 is directly affected:**
   ZIO's default executor must be replaced by one backed by Scala-created threads, and
   `ZIO.attemptBlocking`'s pool needs checking against this constraint before it is used.

3. **`docs/09-open-questions.md` — add the javalib coverage question.** `java.time` is absent
   from Scala Native, so any public API in `scala-ui-core` that exposes dates/times cannot use
   it portably. Decide at M0 between: a minimal `java.time` subset, a platform-abstracted clock
   in `scala-ui-core`, or depending on a cross-published date-time library. Also note that
   javalib gaps surface only at link time — CI for every module must run `nativeLink`, not just
   `compile`, or these land in `main` unnoticed.

4. **Record the single-GC risk.** immix is the only working collector on iOS; boehm and commix
   need `gc/gc.h` and `sys/posix_sem.h` respectively, neither in the iOS SDK. There is no
   fallback. M1 should re-verify immix on a physical device early, since a GC problem there
   has no configuration-level escape hatch.

5. **Do not treat the 1.90 MB figure as the shipped size.** It is a simulator slice of 88
   lines of Scala. It is comfortably inside N-03's 6 MB, and the *runtime* floor it implies
   (~2 MB for GC + javalib + a handful of functions) is the number worth carrying forward.

6. **Nothing here blocks S3 or S8.** S1 passes, so both are unblocked — but S3 should adopt
   recommendation 2 as a design constraint from the first line of the shim, not discover it.
   S3 should additionally verify the S4 finding (small structs by value from callbacks) against
   real `CGRect`/`CGSize`, since that remains untested on Apple.
