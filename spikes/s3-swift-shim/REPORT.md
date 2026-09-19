# S3 — Scala Native ⇄ Swift C-ABI shim — REPORT

**Date:** 2026-09-19
**Machine:** macOS (Darwin 27.0.0), Apple Silicon arm64
**Target:** iOS **simulator** only (iPhone 17, iOS 27.0) — no physical device available
**Depends on:** S1 PASS

| Tool | Version |
|---|---|
| Xcode / iOS SDK | 27.0 (27A266a) / iPhoneSimulator27.0.sdk |
| Swift | 6.4 (swiftlang-6.4.0.34.1), compiled in Swift 5 language mode |
| sbt / Scala / Scala Native | 2.0.9 / 3.9.0 / 0.5.12 |
| sn-bindgen | 0.4.5 |

---

## Result: PASS

Every pass criterion in the brief is met, with margin:

- Tap → Scala closure → label update works, through **real UIKit event dispatch**.
- Background Scala thread → main-thread update works.
- Round-trip **433 ns**, against an N-05 target of ≤ 5 µs — **11× inside budget**.
- **No leak**: RSS +0.06 MB across 100,000 taps.
- sn-bindgen consumed the header completely (18 functions, all typedefs, the struct).

The design in `07` §7.6 is sound. But getting here surfaced one constraint that is *not*
in the plan and that every Apple renderer will hit — the GC/run-loop rule in "Problems"
below. It has a verified 40-line fix, which is why this is a PASS and not PASS-WITH-RISK.

Exact console output from the final run (`[S3]` lines are the app's own):

```
struct by value : 0.0 x 0.0 WRONG (S4 bug)
struct out-param: 111.0 x 222.0 OK
set_text x100000 (pre-encoded): 216 ns/call
set_text x100000 (+Zone encode): 3420 ns/call
tap round-trip x100000: 433 ns/tap (target <= 5000)
taps delivered: 100000 OK
RSS 151.72 -> 151.78 MB (delta +0.06)
tap #1 handled in Scala          <- real UIControl event dispatch
tap #2 handled in Scala
background thread → main thread OK (sum=599997)
soak round 400/400 delivered (garbage=19901, RSS=193.3 MB)
SOAK COMPLETE: 400 rounds, no GC stall
```

---

## Measurements

### Pass criteria

| Criterion | Target | Measured | How |
|---|---|---|---|
| Tap → Scala → label update | visible | **works** | `sui_button_send_ui_action` → UIControl → ObjC target/action → C fn ptr → trampoline → closure |
| Background → main-thread update | works | **works**, `sum=599997` | Scala thread → `sui_run_on_main` → `DispatchQueue.main.async`; sum verified independently in Python |
| Callback round-trip | ≤ 5 µs (N-05) | **433 ns** | 100,000 iterations of `sui_simulate_taps`, `System.nanoTime` |
| Leak after 100k taps | RSS stable | **+0.06 MB** | `task_info(MACH_TASK_BASIC_INFO)` before/after |
| sn-bindgen consumes header | yes, or small gaps | **yes, complete** | 18 externs, 4 fn-ptr typedefs, opaque handle, struct — all correct |

### Cost of crossing the boundary

| Operation | ns/call | What it includes |
|---|---|---|
| `sui_label_set_text`, pre-encoded `CString` | **216** | C ABI + Swift `String(cString:)` + `UILabel.text` setter |
| `sui_label_set_text`, `Zone` + `toCString` per call | **3420** | the above, plus Scala `String` → UTF-8 encoding |
| tap round-trip (Swift → Scala closure) | **433** | fn ptr + trampoline + handle-table lookup + closure |

**The interesting number is the 16× gap between 216 and 3420 ns.** Crossing the C ABI is
cheap; *encoding a Scala string to pass across it* is not, and it dominates by an order of
magnitude. Allocating a `Zone` per call is the wrong shape for a property setter.

### GC state handling — measured, not assumed

| Configuration | `set_text` | 400-round soak | Result |
|---|---|---|---|
| No GC state management | — | — | **SIGABRT**, GC gives up waiting for safepoint |
| `GcState` only (**shipped**) | **216 ns** | completes, 0 warnings | correct |
| `GcState` + `@blocking` on all 19 externs | 444 ns | completes, 0 warnings | correct but **2× slower for no benefit** |

### Soak: background thread forcing GC while the main thread services UI posts

400 rounds, ~8 M short-lived strings each. RSS flat, no stalls, no safepoint warnings:

| round | 50 | 150 | 250 | 350 | 400 |
|---|---|---|---|---|---|
| RSS (MB) | 192.6 | 194.0 | 193.8 | 193.5 | 193.3 |

(~150 MB of that is UIKit itself; S1's UI-less harness sat at 13.5 MB.)

### Size and shim cost

| Thing | Lines | Note |
|---|---|---|
| `Shim.swift` | 182 total / **121 non-comment** | **21 `@_cdecl` functions** |
| C headers | 96 | split into types + functions, see below |
| Generated Scala bindings | 255 | `./regen-bindings.sh`, not hand-written |
| Hand-written Scala | 314 | handle table 60, GC state 42, demo + benchmarks 212 |
| App binary, stripped | 3,128,440 B (2.98 MB) | debug Scala Native + UIKit host |

**≈ 5.8 non-comment lines of Swift per exported function.** §7.10's v1 catalogue is ~40
components; at roughly 6 C functions each (construct, 2–4 property setters, 1–2 events)
that is ~240 functions ≈ **1,400 lines of Swift**, plus the ~120 already written for the
generic tree/frame/threading layer. That is a tractable, mostly mechanical body of code —
and a strong argument for generating it from a widget description rather than hand-writing.

---

## What was built

```
spikes/s3-swift-shim/
  shim/include/scalaui_shim_types.h   typedefs + struct (Swift imports only this)
  shim/include/scalaui_shim.h         function declarations (sn-bindgen reads this)
  shim/Sources/Shim.swift             21 @_cdecl functions over UIKit
  scala-lib/src/main/scala/scalaui/s3/
    generated.scala                   sn-bindgen output, checked in
    Handles.scala                     the handle table
    GcState.scala                     the Managed/Unmanaged guard
    Main.scala                        scalaui_main, struct probe, benchmarks, soak
  ios-app/Sources/Host.swift          UIKit scene-based host
  ios-app/build-app.sh                swiftc shim+host, link .a, install, launch
  regen-bindings.sh                   sn-bindgen with the libclang workaround
```

Reproduce: `sbt scalaLib/nativeLink && cd ios-app && ./build-app.sh`, then
`xcrun simctl launch --console-pty "iPhone 17" dev.scalaui.s3app`.

---

## Problems hit

### 1. The GC deadlocks against the UIKit run loop (**solved; the main finding**)

Symptom, after the first background thread started allocating:

```
[ScalaNative GC|Warning] Waiting for 1 thread(s) to reach safepoint (10.0s elapsed)
[ScalaNative GC|Warning]   Thread id=4350877248, state=Managed, alive=yes
  - Thread blocked in native code without @blocking annotation
```

then `SIGABRT`.

Cause: Scala Native stops the world by waiting for every **Managed** thread to reach a
safepoint. A thread that calls `ScalaNativeInit` is Managed for life unless told otherwise.
The UIKit main thread does exactly that, returns into `CFRunLoop`, and parks in `mach_msg` —
in native code, polling no safepoints until an event arrives. The first collection triggered
by any other Scala thread therefore waits on it indefinitely.

Fix (`GcState.scala`): call `scalanative_GC_set_mutator_thread_state(Unmanaged)` whenever
Scala returns into the host's run loop, and `Managed` on re-entry. Every host → Scala entry
point is wrapped in `GcState.guarded`. Verified over 400 soak rounds with zero warnings.

**This generalises past iOS.** Any renderer where a foreign event loop owns the main thread —
GTK, Win32, AppKit — has the same shape. S7 did not hit it only because its GTK app never
allocated from a second thread.

### 2. `@blocking` is the wrong default, and it costs 2× (**measured**)

The GC's own message suggests `@blocking` on extern calls, and it is easy to conclude that
every shim function needs it. Measured both ways: annotating all 19 externs takes
`sui_label_set_text` from **216 ns to 444 ns**, and the soak passes identically either way,
because UIKit calls return far inside the 10 s safepoint timeout.

So `@blocking` belongs on calls that can genuinely block — file and network I/O, modal
presentation — not on property setters. Blanket-annotating doubles the cost of the single
most frequent operation in the framework and buys nothing. `regen-bindings.sh` therefore
leaves it off and documents why.

### 3. S4's struct-by-value bug reproduces on arm64 — worse (**confirmed, avoided by design**)

S4 measured this on x86-64 SysV and saw shifted fields (`222.0 x 0.0` for `111.0 x 222.0`).
On arm64, where AAPCS64 returns a two-double homogeneous aggregate in `v0`/`v1`:

```
struct by value : 0.0 x 0.0 WRONG (S4 bug)
struct out-param: 111.0 x 222.0 OK
```

Not shifted — **entirely zero**. Same silent-wrong-answer failure, different wrong answer per
ABI. The shim's blanket "no structs by value across a Scala callback" rule is confirmed
necessary on the architecture that actually ships, and `sui_view_set_frame` taking four
doubles instead of a `CGRect` is vindicated rather than merely cautious.

### 4. Two Swift/iOS integration traps

- **`@_cdecl` collides with its own C declaration.** If Swift imports a header declaring
  `sui_view_new` *and* defines `@_cdecl("sui_view_new")`, it is a redeclaration error. But
  Swift still needs the typedefs and struct layout. Hence the header split: types in
  `scalaui_shim_types.h` (imported by Swift), functions in `scalaui_shim.h` (read by
  sn-bindgen only). Worth doing from the start in M1.
- **iOS 27 refuses the old app lifecycle.** A plain `UIApplicationDelegate` with a window
  traps at launch in
  `__UIApplicationEvaluateRuntimeIssueForNoSceneLifecycleAdoption` (`EXC_BREAKPOINT`). The
  host must adopt `UIScene` — a `UISceneConfiguration` plus `UIApplicationSceneManifest` in
  `Info.plist`. Any app template the framework ships has to be scene-based.

### 5. sn-bindgen's macOS binary pins Homebrew `llvm@17` (**worked around**)

```
dyld: Library not loaded: /opt/homebrew/opt/llvm@17/lib/libclang.dylib
```

sn-bindgen 0.4.5's prebuilt `aarch64-osx` binary is linked against that exact path. Rather
than install a ~1.5 GB Homebrew LLVM, pointing `DYLD_FALLBACK_LIBRARY_PATH` at the libclang
inside Xcode works (llvm@20 also works). Generation is kept out of `build.sbt` and the
bindings are checked in, so a plain `sbt compile` needs none of this.

Given the header, sn-bindgen's output was **correct and complete** — opaque `sui_handle`,
`CStruct2[Double, Double]` for `sui_size`, and all four function-pointer typedefs including
the struct-returning one. No hand-editing beyond the header split.

### 6. A self-inflicted diagnostic trap, recorded because it cost real time

The soak appeared to stall at round 350/400 across several runs. It was not stalling: the
`UILabel` has a fixed frame, and a rolling window of 22 lines exceeded it, so new lines were
appended off-screen and the display simply stopped changing. Chasing the phantom produced two
"fixes" (`@blocking`, a synchronised handle table) before the real cause surfaced. Lesson,
applied in the final code: **instrument through stdout and read it with `simctl --console-pty`;
do not read numbers off screenshots.** The synchronised handle table was kept — the race it
prevents is real (registration from a background thread, invocation on the main thread) even
though it fixed no observed symptom.

---

## Report also (brief's explicit questions)

**Swift `@_cdecl` ergonomics.** Straightforward. `UnsafePointer<CChar>` → `String(cString:)`
copies, so the shim never retains Scala memory and UTF-8 needs no special handling in that
direction. The reverse (Swift → Scala) is the awkward one: there is no safe way to hand back
a pointer into a Swift `String`'s storage, so `sui_label_get_text` has to `strdup`, which
means an ownership rule the header must state. For v1, prefer "caller supplies the buffer"
over "callee returns a pointer" wherever a string comes *out* of the shim.

**Error propagation.** Not exercised (no `NSError` path was needed). The shape that fits what
is here: an `int32_t` status return plus an out-param for a message buffer — anything richer
runs into the same string-ownership problem.

**Swift concurrency warnings.** None in Swift 5 language mode, as `decisions.md` anticipated.
The mutable globals the shim uses (`tapTargets`, `liveHandles`) would all be
`@MainActor`-or-`nonisolated(unsafe)` errors under Swift 6 strict concurrency. Since S1
already forbids calling Scala off the main thread, annotating the shim `@MainActor` when
Swift 6 mode arrives should be nearly mechanical.

**AppKit variants: `#if canImport(UIKit)` or a second package?** Neither, cleanly. The
divergence is not per-call, it is structural: `NSView` has a flipped coordinate system,
`NSButton` uses target/action with different enums, and there is no `UIControl.Event`.
`sui_view_add_child` and `sui_view_set_frame` would be `#if` branches of three lines; the
control constructors would be near-total rewrites. **Recommendation: one package, one header,
per-*file* separation** (`Shim+UIKit.swift` / `Shim+AppKit.swift`) with a small shared core,
rather than `#if` inside function bodies or two packages that duplicate the header.

---

## Recommendations for the plan

1. **`docs/07-technical-design-ideas.md` §7.6 and §7.13 — add the run-loop GC rule.** Scala
   must mark itself Unmanaged when returning to a host event loop and Managed on re-entry
   (`scalanative_GC_set_mutator_thread_state`). Every host → Scala entry point goes through
   one guarded trampoline. Without it the app aborts as soon as a background thread allocates.
   Reference implementation: `spikes/s3-swift-shim/.../GcState.scala`. **This applies to the
   GTK and Win32 renderers too**, not just Apple — S7 avoided it only by never allocating off
   the main thread.

2. **`docs/07` §7.6 — write the shim ABI rules down as rules, since all three are now
   measured rather than assumed.** (a) No struct passed or returned by value across a Scala
   callback — flatten to scalars or out-params; confirmed broken on both x86-64 and arm64,
   with *different* wrong answers. (b) Callback context is `int64_t`, never `void*` — it
   removes S7's `Long`⇄`Ptr` laundering entirely by construction. (c) Strings leaving the
   shim use caller-supplied buffers, not returned pointers.

3. **`docs/05-requirements.md` — N-05 is comfortably met, so re-aim the performance work.**
   The round trip is 433 ns against a 5 µs budget. The real cost is Scala `String` → `CString`
   at 3.2 µs per call, 16× the ABI crossing. M2 should spend its effort on string interning /
   a reusable encode buffer in the renderer's property path, not on the callback mechanism.

4. **`docs/08-roadmap-and-spikes.md` — plan to generate the shim.** ~5.8 lines of Swift per
   function, ~240 functions for the v1 catalogue ≈ 1,400 lines of highly repetitive code, which
   must stay in sync with the C header and the Scala bindings. Generating all three from one
   widget description is a better M1/M2 investment than hand-writing and hand-maintaining them.

5. **The app template must be `UIScene`-based**, and the header must be split types/functions
   so Swift can implement `@_cdecl` without redeclaration errors. Both are small decisions that
   are annoying to retrofit.

6. **Gate status: S1 ∧ S2 ∧ S3 all pass.** Per `08` §8.1 that is a **go**, with the risks each
   report records — S2's PASS-WITH-RISK on Android R8, S1's on the single iOS GC and the GCD
   restriction, and S3's run-loop rule now folded into the design.
