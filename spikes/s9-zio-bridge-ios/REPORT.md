# S9 — the ZIO bridge on iOS — REPORT

**Date:** 2026-09-22
**Machine:** macOS (Darwin 27.0.0), Apple Silicon arm64, Xcode 27.0
**Target:** iOS simulator (iPhone 17, iOS 27.0)
**Issue:** #2 — "Verify the ZIO bridge on iOS"
**Modules under test:** `modules/{signals,renderer-api,core,effect-zio}` — the real sources,
compiled from `modules/`, not copies.

---

## Result: FAIL — the bridge does not yet work on iOS

The bridge works when an effect is started from the host's initial entry point, and does
not work when one is started from inside a run-loop callback. The second case is the one
that matters: it is what every user interaction is. A tap arrives through the run loop and
the handler calls `launch` or `asSignal`.

The cause is **not** in `effect-zio`. The process dies of main-thread stack exhaustion —
`EXC_BAD_ACCESS` / `"Thread stack size exceeded"` — and a **self-reposting main-queue chain
consumes ~415 bytes per hop and never unwinds**, which at iOS's 1 MB main stack is fatal
after a couple of thousand hops.

**What is not yet established is why, and one piece of evidence cuts against the obvious
reading.** S8 delivered **37 504** main-queue callbacks through this identical
`registerOneShot` + `sui_run_on_main` + `mainTrampoline` path and did not die. At 415 bytes
a hop that would have needed 15 MB. So "every callback leaks stack" cannot be the whole
story, and this report deliberately stops short of claiming it.

The live hypothesis is that the growth is specific to **posting from inside a callback**
(what the probes here do) as against **posting from another thread** (what S8's UiExecutor
did, from ZIO's scheduler threads). A control for exactly that was written — probe 1b — and
did not complete, so the hypothesis is untested. Resolving it is the first task in the
recommendations.

---

## The bisection

Every probe below lives in the same binary and runs in order, so each is a control for the
next. `spikes/s9-zio-bridge-ios/scala-lib/.../Main.scala`.

| # | Probe | Result |
|---|---|---|
| 1 | Self-reposting main-queue chain, 600 deep | **PASS** (600/600), but consumes 249 KB of stack |
| 1b | The same 600 posts issued from a Scala thread | **inconclusive** — did not complete |
| 2 | `signals` `Var` set/read on the main thread | **PASS** |
| 3 | Monitor contended between the main thread and a Scala thread | **PASS** (main 100 000, worker ~95 000) |
| 4 | Bare ZIO fibre forked from the entry point, posting back 200× | **PASS** in isolation |
| 5 | `ZIO.succeed(42).asSignal` from the entry point | **PASS** — `Done(42)` on the first poll |
| 6 | The same `asSignal` from inside a run-loop callback | **FAIL** — stays `Loading`, then the process dies |

Probe 5 passing is the important one: **the bridge itself is correct.** `asSignal` forks,
the fibre completes on a ZIO thread, the write is posted through `UiThread`, and the signal
reaches `Done` — across a genuine thread boundary, with `GcState` guarding the re-entry.

### What was ruled out, and how

- **`GcState`** is not the cause. Probe 6 posts through a deliberately *unguarded*
  trampoline (`Handles.unguardedTrampoline`) and behaves identically.
- **Monitor contention** is not the cause, though it was the first suspect: Scala Native
  identifies a thread by its registered stack bounds, and the main thread's were recorded at
  `ScalaNativeInit`, deep inside scene setup. Probe 3 contends that monitor 100 000 times
  from the main thread against a Scala thread without a fault.
- **Where `Runtime.default` is first constructed** is not the cause. Warming it in the
  initial block and forking later changes nothing.
- **The harness** is not the cause. Probe 1 is the harness with nothing else in it.
- **`modules/effect-zio` itself** is not the cause — see probe 5.

### The measurement

Probe 1 records the address of a stack local on each callback:

| callbacks | 150 | 300 | 450 | 600 |
|---|---|---|---|---|
| stack consumed since the first | 61 984 B | 124 384 B | 186 784 B | 249 184 B |

**~415 bytes per hop, linear, never released** — for a chain that reposts *itself* from
inside each callback. At 1 MB that is roughly 2 500 hops.

Two independent pieces of evidence agree that the stack is being exhausted: the address
arithmetic above, and the crash report's own `"Thread stack size exceeded"`. Earlier runs
reported the same fault as `"Could not determine thread index for stack guard region"`,
which is the same overflow seen by a guard handler that could not attribute it to a thread —
worth recognising, because it reads like a threading bug and is not one.

Two caveats, both material:

- `stackalloc` in Scala Native allocates in the enclosing function's frame, so the
  per-hop figure is approximate. The fact of exhaustion does not rest on it.
- **S8 contradicts the simple reading**, as above. Until probe 1b (posts issued from a
  separate thread) runs, "self-reposting chains nest" is a hypothesis, not a finding.

---

## What was built

```
spikes/s9-zio-bridge-ios/
  build.sbt                     compiles the REAL module sources (not copies) for iOS
  shim/                         copied from S8 (spikes never depend on each other)
  scala-lib/src/main/scala/scalaui/s9/
    AppleUiThread.scala         the deliverable: UiThread wired to sui_run_on_main
    Main.scala                  the six-probe bisection
    Handles.scala               + unguardedTrampoline, for isolating GcState
    GcState.scala, generated.scala
  ios-app/                      UIScene-based host
```

`build.sbt` points `unmanagedSourceDirectories` at `modules/{signals,renderer-api,core,effect-zio}`
so this tests the shipped code. It is a standalone build rather than a project in the root
`build.sbt` because every iOS target needs `xcrun` at build-load time, which would break the
Linux box's build for everyone else.

**The modules compile clean for Native under the repo's full flags** — `-no-indent`,
`-Wunused:all`, `-Werror`. The only warnings in the whole build come from sn-bindgen's
generated bindings, which are silenced by file. That is worth knowing on its own: nothing in
`signals`, `core`, `renderer-api` or `effect-zio` is JVM-only by accident.

### `AppleUiThread`, the thing issue #2 asked for

Four lines, each of which is a spike finding:

```scala
def install(): Unit = UiThread.install { f =>
  val id = Handles.registerOneShot(() => f())
  sui_run_on_main(sui_main_cb(Handles.mainTrampoline), id)
}
```

- `sui_run_on_main` targets the **main queue specifically**, never a worker queue — Scala on
  a GCD worker segfaults in `Allocator_Alloc` (S1).
- The task travels as an `int64_t` handle-table id: a C function pointer cannot carry a
  closure (S4), and an `int64_t` context avoids the `Long`⇄`Ptr` laundering (S7).
- `mainTrampoline` wraps the call in `GcState.guarded` (S3).
- `registerOneShot`, not `register`, or the table leaks at the rate of UI activity (S8).

This is correct as far as it goes, and probe 5 shows it works. It is the *accumulation* over
many posts that fails.

---

## Answers to the issue's specific questions

1. **`UiThread.install` wired to `sui_run_on_main`** — done, above, and it works for the
   posts it delivers. The bridge's choice to post *only* signal writes and let ZIO schedule
   everything else is sound: probe 5 confirms it end to end.
2. **The S1 constraint still holds** — unverified. Probe 8 (`ZIO.attemptBlocking`) never ran,
   because the battery cannot get past probe 6. Nothing observed contradicts S8, which
   showed the blocking pool is real ZIO threads named `zio-default-blocking-N`.
3. **The S3 constraint still holds** — confirmed as far as tested. `GcState.guarded` is on
   every host→Scala entry the bridge creates, and removing it does not change probe 6's
   outcome, so the guard is neither the problem nor currently load-bearing in this path.
4. **Interruption** — **not verified.** Blocked behind probe 6.
5. **`scala-java-time` 2.7.0 is still required** — confirmed. It is in `build.sbt` for the
   same reason as S8: `zio.Duration` *is* `java.time.Duration`, and Scala Native has no
   `java.time`.

---

## Recommendations

0. **Run probe 1b first — it decides what this report means.** It is already written
   (`probe1bPostsFromThread`): the same 600 posts, issued by a Scala-created thread instead
   of from inside the callback. If it is flat, the rule is "never repost to the main queue
   from inside a main-queue callback", the bridge is fine because it posts from ZIO threads,
   and probe 6's failure is an artifact of this harness's polling loop rather than a bridge
   defect. If it also grows, then S8 got lucky and the posting path needs real work. Until
   this runs, **do not treat "the bridge fails on iOS" as settled** — treat it as "the
   bridge could not be verified, and here is exactly where it stopped".

1. **Fix the leak before anything else in the Apple line.** Nothing above `sui_run_on_main`
   can be trusted until a main-queue callback costs nothing. The next step is to find where
   the 415 bytes go — the candidates, in order of suspicion, are Scala Native's `CFuncPtr`
   entry path, the `Zone` in the shim's string handling, and `dispatch_async` block capture.
   A pure-Swift control — the same repost chain with no Scala in it — was attempted here
   and did not run past its first callback, so it settled nothing and was removed rather
   than shipped as a broken diagnostic. Getting that control working is the single most
   useful next step: if a Swift-only chain also grows, the leak is GCD or the simulator and
   nothing in this repo can fix it; if it is flat, the leak is on the Scala side of the
   `@_cdecl` boundary. It belongs in `spikes/s3-swift-shim/`, where the mechanism lives.

2. **`docs/07` §7.13 — record that the run-loop *position* of an effect matters on Apple,**
   at least until (1) is fixed. "Start effects from the host entry point" is not a design a
   UI framework can live with, but it is the current state and should be written down rather
   than rediscovered.

3. **Re-run this spike after the fix.** Probes 1–6 are ordered and cheap; probes for
   `attemptBlocking`, interruption on `Owner.dispose`, `SubscriptionRef.asSignal` and GC
   churn were written and are in the file's history but could not be reached. In particular
   `SubscriptionRef.asSignal` calls `runtime.unsafe.run` for its initial value, which
   **blocks the calling thread** — on iOS that is the main thread, in GC-Managed state, which
   is the exact shape of the S3 deadlock. That one should be tested first once probe 6 passes.

4. **Do not read this as "ZIO does not work on iOS".** S8 stands: the runtime starts in
   1.2 ms, delivers 37 500/37 500 ticks, and interrupts promptly. What is broken is the
   shim's posting path under sustained use, and it would break a GTK or Android renderer
   posting at the same rate too if they shared the mechanism — they do not, which is why this
   has not been seen before.
