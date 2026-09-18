# S1 — Scala Native on iOS — BRIEF

**Machine:** macOS laptop with Xcode. **Budget:** 2 weeks (+ up to 4 weeks of upstream
work if close). **Depends on:** nothing. **Gate:** YES — the single most important
spike. If this fails, Option B loses iOS and the plan changes.

## Question
Does a Scala Native 0.5.x static library, cross-compiled for iOS, link into an
Xcode app, start its runtime (GC, threads, javalib) on the iOS **simulator** and
(if a device is available) on an iPhone, and stay alive under allocation and
threading load?

## Setup on the Mac
- Xcode latest stable + iOS SDK + a simulator runtime (iOS 17 or 18). `xcode-select -p` sane.
- `brew install sbt coursier llvm` is **not** needed for clang — Scala Native uses the
  clang it finds; make sure it picks Apple's or Homebrew's and record which.
- Clone this repo; work in `spikes/s1-native-ios/`.

## Deliverables
1. `scala-lib/` — sbt project, Scala 3 LTS, Scala Native latest 0.5.x, build target
   `libraryStatic`, with `@exported` C functions:
   - `scalaui_hello(): CString` — returns a static string
   - `scalaui_alloc_stress(seconds: Int): Long` — allocates short-lived objects
     (Lists, Strings, case classes) in a loop for N seconds, returns count
   - `scalaui_thread_test(n: Int): Int` — spawns n Scala threads doing work,
     joins, returns a checksum
   - `scalaui_time(): CString` — uses `java.time.Instant.now`, `java.util.Locale`,
     `String.format` (exercises javalib on iOS)
   - `scalaui_register_callback(cb: CFuncPtr1[CString, Unit]): Unit` and
     `scalaui_fire(): Unit` — calls the Swift callback from Scala (prepares S3)
   Cross-compile via `nativeConfig ~= { _.withTargetTriple(...) }` for
   `arm64-apple-ios17.0-simulator` (primary) and, if the Mac is Intel,
   `x86_64-apple-ios17.0-simulator`. Also *attempt* the device triple
   `arm64-apple-ios17.0` and report whether it links, even though nothing will run it —
   a link failure there is itself a finding. Pass the iOS SDK via
   `-isysroot $(xcrun --sdk iphonesimulator --show-sdk-path)` in compile and link
   options. Expect to fight: `-target` vs triple naming (Scala Native's triple
   parser may not know `-simulator`), missing `libunwind`/`zlib` for the target,
   Immix GC using `mmap` flags or `pthread` APIs not permitted on iOS. Record each obstacle.
2. `ios-app/` — Xcode project, SwiftUI single view, links the `.a` and a bridging
   header, buttons that call each exported function, shows results in the UI.
   Build settings: `OTHER_LDFLAGS` for the archive, `ENABLE_BITCODE = NO`.
3. Try GC variants: `immix` (default), `boehm` (if libgc can be built for iOS),
   `none`. Record which start, which survive the stress test.
4. Measurements in `REPORT.md`:
   - `.a` size and final `.app` binary size after Xcode strip (hello-world) — target ≤ 6 MB
     (N-03). Note: simulator slices are not a fair size proxy for a shipped device binary;
     record the number but flag the caveat.
   - time from `scalaui_hello` first call to return (runtime init) — target ≤ 50 ms
   - `scalaui_alloc_stress(600)` on the simulator without crash; peak memory from Instruments/Xcode gauge
   - `scalaui_thread_test(8)` correct
   - Scala Native **link time** for the debug build (this is the DX risk in `04` §4.3)
   - Does LLDB/Xcode show Scala frames with readable names when you breakpoint in `scalaui_hello`?
5. **Physical device: out of scope** (user decision 2026-09-18 — no iPhone available).
   Report device numbers as "not measured". A device check moves to M1, where it
   matters for real startup/size figures and for exercising code signing.

## Pass criteria
- Simulator: all five functions work; alloc stress runs 10 min without crash under at
  least one GC; threads work. **This alone is enough to decide go/no-go**: the question
  is whether the Scala Native runtime functions on iOS at all, and the simulator answers
  it. Device-specific risks (signing, arm64-only codegen, real memory pressure) are
  deferred, and the report must say so explicitly rather than implying full coverage.

## PASS-WITH-RISK
- Works only with `boehm` or `none` GC; or needs a Scala Native snapshot; or needs a patched runtime. Document precisely.

## FAIL
- Cannot link, or runtime cannot start, and the root cause is in Scala Native's
  runtime/toolchain with no bounded fix. Before declaring FAIL: search
  scala-native issues/discussions for iOS, and write a minimal repro + issue text
  (do not file it; give it to the user).

## Do not
- Build UI widgets (that is S3). Do not use ZIO (that is S8).
- Spend more than one day on Homebrew/Xcode environment problems before asking the user.
