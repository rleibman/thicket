# S8 — ZIO 2 on Scala Native on iOS — BRIEF

**Machine:** macOS. **Budget:** 1 week. **Depends on:** S1 PASS (S3 helpful).
**Gate:** no — failure downgrades "ZIO on iOS" to a risk; the bridge design stands.

## Question
Does the ZIO 2 runtime start and run correctly inside a Scala Native static
library on the iOS simulator/device, and can fibers drive UI updates through a
main-thread executor at 60 Hz (`07` §7.13, A-05c)?

## Deliverables
1. `scala-lib/` — from S1/S3's build plus `dev.zio::zio` and `zio-streams` (Native
   artefacts; confirm they resolve for the chosen Scala Native version — if not,
   that is the first finding).
   - `@exported scalaui_zio_start()`: create `Runtime.default` (then a custom one
     with `Runtime.setExecutor` pointing at a **UiExecutor** that wraps
     `sui_run_on_main` from S3, or a simple pthread-based main-queue post if S3 is
     not available); measure init time.
   - Fork 100 fibers doing `ZIO.sleep`+arithmetic; join; checksum.
   - `ZStream.tick(16.millis).zipWithIndex.foreach(i => setLabel(i.toString))` for
     10 minutes on `UiExecutor`; count frames actually delivered (should be ~37 500).
   - `SubscriptionRef` updated from a background fiber, `.changes` consumed on UI executor.
   - `ZIO.attemptBlocking` — does the blocking pool create threads on iOS?
   - Interrupt a fiber via a `Scope` close — does interruption work promptly?
2. `ios-app/` — host as in S1/S3 showing the ticking label.
3. Measurements in `REPORT.md`: runtime init ms (target ≤ 50), RSS before/after
   runtime (target: ZIO adds ≤ 10 MB), frames delivered vs expected, CPU while idle,
   any crash/hang with stack.

## Pass criteria
- Runtime starts; fibers, streams, refs, interruption behave; ≥ 95% of ticks delivered; no crash over 10 min.

## PASS-WITH-RISK
- Needs a single-threaded executor, or a specific GC, or init > 50 ms.

## Report also
- Whether `Clock` (javalib `System.nanoTime`/`currentTimeMillis`) is accurate on iOS.
- Whether the ZIO scheduler thread and UIKit's main thread interact badly (priority inversion, hangs at app backgrounding — test by backgrounding the simulator app).
