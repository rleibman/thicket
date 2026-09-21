# 10. Phase 0 findings and the go/no-go decision

**Date:** 2026-09-19, calibration added 2026-09-20 · **Spikes run:** all eight

## 10.1 Verdict: **GO**

`docs/08` §8.1 set the gate as **S1 ∧ S2 ∧ S3**. All three pass:

| Spike | Question | Result |
|---|---|---|
| **S1** | Does Scala Native run on iOS? | **PASS-WITH-RISK** |
| **S2** | Scala 3 on Android with R8? | **PASS-WITH-RISK** |
| **S3** | Scala Native ⇄ Swift shim viable? | **PASS** |
| S4 | Yoga from Scala? | PASS-WITH-RISK |
| S5 | Cross-platform signals? | PASS |
| S7 | GTK4 renderer? | PASS |
| S8 | ZIO 2 on iOS? | PASS |
| S6 | How do the alternatives actually measure up? | PASS (partial) |

The project is **technically feasible**. Not one of the toolchain risks the plan was most
afraid of actually materialised. But the shape of the risk has *inverted*, and that changes
what should be built first — see §10.5.

### The recommendation survived calibration

S6 built the same hello app three ways and measured it on one rig (iOS 27 simulator,
Apple Silicon). Option B — the recommendation — is **0.53 MB** against React Native's
**26.0 MB** and Gluon's **60.06 MB**, starts in **425 ms** against RN's 702 ms, and uses
51 MB less RSS. Two findings go beyond the numbers:

- **`slinky-native` has no Scala 3 build at all.** Option A is not merely unmaintained for
  Scala; it is unavailable, and taking it would mean writing React Native facades from
  scratch. `docs/06` scored A's effort 4 on the opposite assumption.
- **Gluon's iOS toolchain has been frozen for two years** and its simulator target is
  x86_64-only, so Option D could not be run on the available Mac at all.

`docs/06` §6.1 is re-scored accordingly, with a size row added — size being both an N-03
requirement and the most discriminating property measured. B now leads 67/80 to F's 65.

## 10.2 Every budget, measured

| Requirement | Budget | Measured | Margin |
|---|---|---|---|
| iOS runtime init | ≤ 50 ms | **0.6 ms** | 83× |
| ZIO runtime init | ≤ 50 ms | **1.2 ms** | 42× |
| Callback round-trip (N-05) | ≤ 5 µs | **433 ns** | 11× |
| iOS binary, bare runtime (N-03) | ≤ 6 MB | **1.90 MB** | 3.2× |
| iOS binary, + shim + ZIO + java-time | ≤ 6 MB | **5.75 MB** (releaseFast + LTO.full) | 1.04× |
| Android APK delta (N-03) | ≤ 4 MB | **74 KB** | 53× |
| Android cold start (N-01) | ≤ 500 ms | **386 ms** ‡ | 1.30× |
| Yoga, 1 000 nodes | < 2 ms | **0.344 ms** | 5.8× |
| Signals, per node update | ≤ 1 µs | **77 ns** JVM / **219 ns** Native | 13× / 4.6× |
| ZIO RSS overhead | ≤ 10 MB | **+3.25 MB** | 3× |
| Tick delivery | ≥ 95 % | **100 %** (but at 55–58 Hz, not 60) | — |
| Scala Native link, small module | < 15 s | **2.5–4.3 s** debug | 3.5× |
| Android edit → device | ≤ 30 s | **17.2 s** | 1.7× |
| iOS RSS, 779 M allocations | no drift | **flat at 13.53 MB** | — |

‡ **Corrected 2026-09-21.** S2 originally reported 457 ms and a 1.40× ratio against a Kotlin
twin. Re-measured with all variants installed together and launched round-robin in one
emulator session, Scala is 386 ms and *Kotlin is slower* at 418 ms; the within-app spread
exceeds the gap. The original figure compared two emulator sessions, which is not a valid
comparison. See the correction appended to `spikes/s2-android/REPORT.md`.

One budget is *close* rather than comfortable: the iOS binary at 5.75/6 MB, and only with
`releaseFast` + `LTO.full`. It is a floor measurement on a trivial app and will move the wrong
way as the framework grows, so it is the number to instrument from M0 onward. Android cold
start, once measured properly, has ample headroom.

## 10.3 What turned out easier than the plan assumed

- **Scala Native on iOS is not exotic.** It cross-compiles, links, runs, and its GC holds a
  flat heap across three quarters of a billion allocations. `docs/04` §4.3 treated this as the
  project's existential risk; it is a solved problem with one build-config workaround.
- **The device triple links.** `docs/04` expected a link failure to be a finding; the opposite
  happened. That is a positive signal for M1, though nothing has *run* on a device.
- **Scala on Android costs 74 KB**, not megabytes — once TASTy is excluded from packaging.
- **The C ABI is cheap.** 433 ns per callback round-trip means the "bridge cost" that
  defined React Native's first architecture is a non-issue here.
- **ZIO on iOS is a non-event.** 1.2 ms init, +3.25 MB RSS, 2.1 % CPU at 60 Hz. The §7.13
  decision to make the ZIO bridge first-class from M0 stands on measured ground.
- **sn-bindgen works.** It consumed Yoga's and the shim's headers completely — function
  pointer typedefs, opaque handles, enums, by-value structs — with no hand-editing.

## 10.4 The six risks that are now real

Ordered by how much they should change the plan.

### R1 — The renderer contract in §7.4 cannot be implemented as written (S7)

`setFrame(handle, Rect)` assumes the framework positions children and the toolkit obeys. A
`GtkBox` positions its own children; honouring a Yoga rectangle would require `GtkFixed`
everywhere, discarding GTK's sizing, RTL handling and baseline alignment. The same tension
exists on every native toolkit, and **GTK is the easiest one**.

S7 also found `insertChild`-by-index has no GTK primitive, and `measure` must return *min and
natural* sizes rather than one size.

**This is now the project's top risk.** It is a design problem, not a toolchain problem, and
nothing in phase 0 resolved it. Everything downstream — the reconciler, Yoga integration, the
whole "one layout model everywhere" premise — rests on it.

### R2 — Threading is tightly constrained on Apple, in two separate ways (S1, S3)

1. **Scala code cannot run on a GCD queue.** It segfaults in the GC allocator, because Scala
   Native has no API to attach a foreign thread. Scala runs on the main thread or on
   Scala-created threads, full stop.
2. **The GC deadlocks against a host run loop.** A thread that called `ScalaNativeInit` stays
   *Managed* forever; parked in `CFRunLoop`/`mach_msg` it polls no safepoints, so the first
   collection from any other thread hangs and aborts. Fixed by marking Unmanaged on return to
   the host loop and Managed on re-entry.

Both have verified fixes (~40 lines). Both must be designed in from the first line of every
renderer. **R2.2 applies to GTK and Win32 too** — S7 avoided it only by never allocating off
the main thread.

### R3 — javalib gaps fail at link time, not compile time (S1, S8)

Scala Native 0.5.12 has **no `java.time`, no `java.text`, no `java.util.Locale`**. Code using
them compiles and then fails `nativeLink` with an "unreachable symbols" dump. ZIO does not
link at all without `scala-java-time` 2.7.0, because `zio.Duration` *is* `java.time.Duration`.

Consequence for CI: **every module must run `nativeLink`, not just `compile`**, or portability
breakage lands in `main` unnoticed.

### R4 — Small structs by value are silently corrupted across Scala callbacks (S4, S3)

Confirmed on **both** ABIs, with **different wrong answers**: x86-64 shifts the fields
(`222.0 x 0.0` for `{111, 222}`), arm64 zeroes them entirely (`0.0 x 0.0`). No crash, no
warning. `CGSize`, `CGRect` and `CGPoint` are returned by value throughout UIKit/AppKit.

The rule — flatten to scalars or out-parameters, never a struct by value across a callback —
is now a measured requirement, not a precaution.

### R5 — Android needs three settings, none discoverable, two failing at runtime (S2)

`android.enableR8.fullMode=false`, `-keepclassmembers class ** { *** *$lzy*; }`, and excluding
`**/*.tasty` from packaging. Without the third the APK is 3.89 MB instead of 81 KB. Without
the first two the app crashes at launch with `NoSuchFieldException` naming no Scala concept.

Mechanical to fix, but the framework's generated Gradle shell must apply them — a user will
never find them.

### R6 — Only one GC works on iOS, and nothing has run on a device (S1)

`immix` works; `commix` and `boehm` fail to compile for iOS (missing `sys/posix_sem.h` and
`gc/gc.h`). There is no fallback if immix misbehaves under real device memory pressure — and
device behaviour is entirely unmeasured. Everything in this document is a simulator or
emulator number.

## 10.5 The risk profile has inverted

The plan was built around one question: *can Scala reach these platforms at all?* Phase 0
answers yes, with margin. What it also shows is that the remaining hard part is the part the
plan treated as settled:

| | `docs/06` assumed | Phase 0 found |
|---|---|---|
| Toolchain reach | the existential risk | solved, with workarounds |
| Binary size / startup | likely to bite | comfortable (two close calls) |
| Bridge performance | needs care | 11× inside budget |
| **Renderer abstraction** | **a sketch in §7.4** | **the top risk; already broken on the easiest toolkit** |
| Shim volume | not estimated | ~1 400 lines of Swift for v1 — should be generated |

## 10.6 What must change in the plan

1. **`docs/07` §7.4 — rewrite the renderer contract** per S7: a renderer *declares*
   `layoutMode(kind): FrameBased | ToolkitManaged`; `insertChild` is specified by preceding
   sibling, not index; `measure` returns min and natural. Audit for name collisions
   (`Size`, `Rect`, `Point`, `Tag`, `Zone` all clash with `scalanative.unsafe`).
2. **`docs/07` §7.6 — promote three shim rules to requirements**: no structs by value across
   a callback; callback context is `int64_t`, never `void*`; strings leaving the shim use
   caller-supplied buffers.
3. **`docs/07` §7.7 / §7.13 — add the two threading rules** (no GCD; Unmanaged/Managed around
   the host run loop) and correct the 60 Hz claim: `ZStream.tick` is fixed-*delay* and
   delivers 55–58 Hz.
4. **`docs/05` — re-aim performance work.** N-05 is met 11× over; the real cost is Scala
   `String` → `CString` at 3.4 µs, **16× the ABI crossing**. Property-path string handling is
   where M2's performance effort belongs.
5. **`docs/08` — insert a contract milestone before M1** (see §10.7) and plan to *generate*
   the shim, header and bindings from one widget description.
6. **`docs/decisions.md`** — record the iOS linktime `target.os=darwin` override, the
   `scala-java-time` dependency, and `releaseFast + LTO.full + -lc++` as the Apple shipping
   configuration.

## 10.7 Recommended next step: M0.5, a contract spike

Before building anything, resolve R1 — it determines the reconciler, the layout model and
every renderer.

**Question:** can one renderer contract express a real screen on two structurally different
toolkits — a scrolling list with 1 000 rows, a navigation stack, a text field, and a
flex-laid-out row — on **GTK4** and **UIKit**?

**Why those two:** GTK is the cheapest to iterate (S7: 4.3 s link, no device, no signing) and
UIKit is the most constrained. If the contract survives both, it will survive Android and
Win32. If it cannot, the "Yoga everywhere" premise needs replacing before M1, not after.

**Budget:** 4–6 weeks. **Exit:** a contract that both renderers implement without
`asInstanceOf` escapes or per-toolkit special cases in the reconciler, plus the 1 000-row list
scrolling at 60 fps on both.

That is a far cheaper way to find out the design is wrong than discovering it in M3.

## 10.8 Honest scope assessment

Phase 0 changes the *feasibility* answer from "unknown" to "yes". It does not change the
*effort* answer. `docs/08` estimated ~20 months to a 1.0 candidate with 2–3 consistent
contributors, and nothing here makes that cheaper:

- The v1 catalogue (§7.10) is ~40 components × 5 platforms.
- The shim alone is ~1 400 lines of Swift, plus equivalents for Android, GTK and Win32.
- The reconciler, navigation, list virtualisation and text input do not exist in any form.
- Everything measured so far is a label and a button.

What phase 0 *does* provide is a much cheaper intermediate deliverable — see the options the
user should choose between in the covering discussion, not repeated here.
