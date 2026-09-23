# S9 — the ZIO bridge on iOS — REPORT

**Date:** 2026-09-22
**Machine:** macOS (Darwin 27.0.0), Apple Silicon arm64, Xcode 27.0
**Target:** iOS simulator (iPhone 17, iOS 27.0)
**Issue:** #2 — "Verify the ZIO bridge on iOS"
**Modules under test:** `modules/{signals,renderer-api,core,effect-zio}` — the real sources,
compiled from `modules/`, not copies.

---

## Result: PASS

`modules/effect-zio` works on a real UIKit run loop. Every check passes:

```
PASS effect.asSignal reaches Done across a real thread hop — state=Done(42)
PASS effect.asSignal carries a typed failure — state=Failed(nope)
PASS launch routes a failure through ErrorPresenter — presented=Some(boom)
PASS stream.asSignal mirrors 20 values — value=20
PASS SubscriptionRef.asSignal — blocking unsafe.run on the main thread — value=7
PASS a streaming fibre keeps producing while its Owner lives — value=7
PASS disposing the Owner interrupts it — froze at 12, now 12
     attemptBlocking ran on thread 'zio-default-blocking-1'
PASS ZIO.attemptBlocking runs on a ZIO pool thread, not a GCD queue
PASS the GC survives fibre churn while the main thread is in CFRunLoop — sum=Done(100000)
ALL S9 BRIDGE CHECKS PASSED
```

**The bridge needed no changes.** One thing outside it did, and that is the finding.

---

## The finding: a handle table must not use `synchronized` on Scala Native

S3 introduced the handle table as a `mutable.LongMap` guarded by a monitor. That is the
obvious design, it is correct on the JVM, and S8 and the first version of this spike
inherited it. Under this spike's load — registrations arriving from ZIO scheduler threads
and a driver thread while the main thread invokes from run-loop callbacks — it fails two
ways:

```
java.lang.IllegalMonitorStateException: Thread is not an owner of this object
  at scala.scalanative.runtime.monitor.ObjectMonitor.exit
  at scalaui.s9.Handles$.registerOneShot
```

thrown on *exit* from a monitor the thread had just entered; and, before that surfaced,
**~400 bytes of main-thread stack consumed per main-queue callback and never released**,
which on iOS's 1 MB main stack kills the process with `EXC_BAD_ACCESS` /
`"Thread stack size exceeded"` after a couple of thousand hops.

Replacing the monitor with `ConcurrentHashMap` + `AtomicLong` — no object monitors at all —
fixes both. The measurement, taken both ways in the same binary, before and after:

| posting style | with `synchronized` | with `ConcurrentHashMap` |
|---|---|---|
| issued from another thread | 0 bytes / 200 hops | **0 bytes / 200 hops** |
| reposted from inside the callback | 79 600 bytes / 200 hops | **0 bytes / 200 hops** |

That is why S8 never saw it: its 37 504 posts all originated on ZIO threads, which is the
column that was fine either way. Exposing it took posting *from inside a callback* — not an
exotic pattern, but what any poll-driven or self-scheduling UI work does.

**Recommendation: the Apple renderer's handle table (issue #3) must be lock-free from the
first line**, and `spikes/s3-swift-shim` should carry a pointer here. S3's own report is not
edited — it records what was measured then.

### A second-order lesson about `EXC_BAD_ACCESS` on iOS

Earlier runs of this same overflow were reported by the crash log as
`"Could not determine thread index for stack guard region"`, and once as a fault inside
`BasicMonitor.tryLock`. Both read like threading bugs. Both were stack exhaustion, seen by a
guard handler that could not attribute it to a thread. Worth recognising on sight.

---

## What was verified, against the issue's list

1. **`UiThread.install` wired to `sui_run_on_main`** — done, in `AppleUiThread`, and all nine
   checks exercise it. The bridge's design choice — post *only* signal writes and let ZIO
   schedule everything else — holds up: fibres run on ZIO's threads, every UI touch arrives
   on the main thread.
2. **The S1 constraint still holds.** `ZIO.attemptBlocking` ran on `zio-default-blocking-1`,
   a real ZIO pool thread. Nothing in the bridge puts Scala on a GCD worker queue.
3. **The S3 constraint still holds.** `GcState.guarded` is on every host→Scala entry the
   bridge creates. The GC-churn check — 200 parallel fibres allocating ~100 000 strings
   while the main thread sits in CFRunLoop — completes with `Done(100000)` and no safepoint
   warning.
4. **Interruption works.** A streaming fibre advances while its `Owner` lives and stops dead
   when the `Owner` is disposed: frozen at 12, still 12 after several driver ticks. It is
   interrupted, not merely detached.
5. **`scala-java-time` 2.7.0 is still required**, for the same reason as S8: `zio.Duration`
   *is* `java.time.Duration` and Scala Native has no `java.time`.

Also worth recording: **`SubscriptionRef.asSignal` is safe**, which was not obvious. It calls
`runtime.unsafe.run` for its initial value, which blocks the calling thread — and here that
caller is the main thread in GC-Managed state, the exact shape of the S3 deadlock. It
completes correctly (`value=7`).

And: **the modules compile clean for Native under the repo's full flags** — `-no-indent`,
`-Wunused:all`, `-Werror`. The only warnings in the whole build come from sn-bindgen's
generated bindings, silenced by file. Nothing in `signals`, `core`, `renderer-api` or
`effect-zio` is accidentally JVM-only.

---

## What was built

```
spikes/s9-zio-bridge-ios/
  build.sbt                     compiles the REAL module sources (not copies) for iOS
  shim/                         copied from S8 (spikes never depend on each other)
  scala-lib/src/main/scala/scalaui/s9/
    AppleUiThread.scala         UiThread wired to sui_run_on_main
    Handles.scala               lock-free handle table — the fix
    Main.scala                  the measurement + the nine-check battery
    GcState.scala, generated.scala
  ios-app/                      UIScene-based host
```

`build.sbt` points `unmanagedSourceDirectories` at the module sources, so this tests shipped
code. It is a standalone build rather than a project in the root `build.sbt` because every
iOS target needs `xcrun` at build-load time, which would break the Linux box's build.

### How the battery is driven, and why that matters

A **Scala-created driver thread** runs the battery: it sleeps, posts one check to the main
queue, sleeps again. Never the other way round. The JVM suites' `while (!cond) sleep` idiom
cannot be used here — on the main thread it deadlocks by construction, because the posts it
waits for can only be delivered by the run loop it is refusing to return to.

Three harness bugs were found and fixed on the way, each of which produced plausible-looking
wrong answers:

- **Stale evaluations settling the next step.** The driver posts a check every 25 ms, so
  when a step finished several of its posts were still queued; they landed after the next
  step started and settled it instantly — carrying the previous step's detail string, which
  is how it was caught. Fixed with an epoch tag. Before the fix, three steps "passed"
  without running.
- **Details read from the wrong thread.** Step results are written on the main thread;
  reading them from the driver showed stale nulls beside genuine passes.
- **Condition and detail evaluated in the wrong order**, so a step could report `Loading`
  next to its own `PASS`.

None of these turned a real failure into a real pass, but two turned *nothing* into a pass,
which is worse. Recorded because the same shapes will recur in any device test.

---

## Recommendations

1. **`docs/07` §7.6 / §7.13 — specify the handle table as lock-free.** `ConcurrentHashMap` +
   `AtomicLong`, never `synchronized`. This is the one change the framework needs from this
   spike, and issue #3 should start from it rather than copying S3's version.
2. **`spikes/s3-swift-shim` — add a pointer to this report** beside its `Handles.scala`, so
   the next person copying it knows. S3's measurements stand as recorded.
3. **The bridge itself needs no changes.** `modules/effect-zio` is verified on the simulator;
   device verification moves to M1 with everything else.
4. **Port these checks to a device test at M1.** They are ordinary bridge semantics rather
   than iOS trivia, and the driver-thread shape is the reusable part.
