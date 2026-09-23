package scalaui.s9

import _root_.zio.*
import scala.collection.mutable
import scala.scalanative.runtime.{Intrinsics, toRawPtr}
import scala.scalanative.unsafe.*
import scalaui.core.RemoteData
import scalaui.s9.generated.aliases.*
import scalaui.s9.generated.functions.*
import scalaui.signals.{Owner, Signal, Var}
// `*`, not `given`: asSignal/launch are top-level extensions in the package, so a
// givens-only import compiles and then cannot see them.
import scalaui.zio.*

/** Issue #2: does `modules/effect-zio` behave on a real UIKit run loop?
  *
  * The JVM suites install `UiThread` as "run it here", so every post is synchronous. That is exactly what this cannot
  * do: the question is what happens when posts are genuinely asynchronous and land on a thread that spends its life
  * parked in CFRunLoop.
  *
  * What follows is **a bisection, not a test suite**. Probes 1–5 pass; probe 6 does not, and 1–5 exist to rule out the
  * obvious explanations for 6. Keeping them all in the binary is the point — each one is a control for the one that
  * fails.
  */
object Main {

  private var label: sui_handle = null.asInstanceOf[sui_handle]
  private val report = mutable.ArrayBuffer.empty[String]

  private def say(line: String): Unit = {
    println(s"[S9] $line")
    report += line
    Zone(sui_label_set_text(label, toCString(report.takeRight(14).mkString("\n"))))
  }

  /** Approximate stack pointer of the current frame, for measuring whether main-queue callbacks unwind. Taking the
    * address of a stack local is the cheapest probe there is.
    */
  private def stackMark(): Long = {
    val p = stackalloc[Byte]()
    Intrinsics.castRawPtrToLong(toRawPtr(p))
  }

  private var firstMark = 0L

  private def postToMain(f: () => Unit): Unit = {
    val id = Handles.registerOneShot(f)
    sui_run_on_main(sui_main_cb(Handles.mainTrampoline), id)
  }

  /** The same post without `GcState.guarded`, used to show the guard is not what fails. */
  private def postUnguarded(f: () => Unit): Unit = {
    val id = Handles.registerOneShot(f)
    sui_run_on_main(sui_main_cb(Handles.unguardedTrampoline), id)
  }

  @exported("scalaui_s9_start")
  def scalaui_s9_start(rootPtr: Ptr[Byte]): Unit = GcState.guarded(begin(rootPtr))

  private def begin(rootPtr: Ptr[Byte]): Unit = {
    val root = rootPtr.asInstanceOf[sui_handle]
    label = sui_label_new()
    sui_view_set_frame(label, 16.0, 70.0, 360.0, 580.0)
    sui_view_add_child(root, label)

    AppleUiThread.install()
    say("--- ZIO bridge on iOS (issue #2) ---")
    probe1RepostLoop()
  }

  // ------------------------------------------------------------------ 1. the run loop

  private var loopN = 0

  /** Control: a self-reposting main-queue chain, so the harness is cleared before anything else is blamed.
    */
  private def probe1RepostLoop(): Unit =
    if loopN < 600 then {
      loopN += 1
      val m = stackMark()
      if firstMark == 0L then firstMark = m
      if loopN % 150 == 0 then say(s"  callback $loopN: stack used since first = ${firstMark - m} bytes")
      postToMain(() => probe1RepostLoop())
    } else {
      say(s"PASS 1 run-loop repost chain ($loopN)")
      probe1bPostsFromThread()
    }

  // ------------------------------------------- 1b. posts issued from another thread

  private var fromThreadN = 0
  private var fromThreadBase = 0L

  /** Same 600 posts as probe 1, but enqueued by a Scala-created thread rather than from inside the callback. S8 posted
    * this way — from ZIO threads — 37 504 times without exhausting anything, which is the fact that makes probe 1's
    * growth suspicious.
    */
  private def probe1bPostsFromThread(): Unit = {
    val t = new Thread(() => {
      var i = 0
      while i < 600 do {
        postToMain(() => markFromThread())
        i += 1
        Thread.sleep(1)
      }
    })
    t.setName("s9-poster")
    t.start()
  }

  private def markFromThread(): Unit = {
    fromThreadN += 1
    val m = stackMark()
    if fromThreadBase == 0L then fromThreadBase = m
    if fromThreadN % 150 == 0 then say(s"  posted-from-thread $fromThreadN: stack used = ${fromThreadBase - m} bytes")
    if fromThreadN == 600 then {
      say(s"PASS 1b posts issued from another thread ($fromThreadN)")
      probe2Signals()
    }
  }

  // --------------------------------------------------------------------- 2. signals

  /** Control: the reactive core on the main thread, with no effect system involved. */
  private def probe2Signals(): Unit = {
    val v = Var(0)
    v.set(5)
    say(s"PASS 2 signals Var set/read on the main thread (${v.now})")
    probe3Contention()
  }

  // --------------------------------------------------- 3. monitors under contention

  private val contended = new Object
  private var mainRounds = 0
  private var workerRounds = 0
  // The contender must stop before the next probe, or it competes for CPU and the later
  // probes stop being clean measurements. Found the hard way: probe 4 failed only when
  // probe 3's worker was still spinning.
  @volatile private var contendUntilStopped = true

  /** Control: a monitor genuinely contended between the UIKit main thread and a Scala-created thread. A plausible
    * suspect, because Scala Native identifies a thread by its registered stack bounds and the main thread's were
    * recorded at `ScalaNativeInit`, deep inside scene setup. It is not the cause.
    */
  private def probe3Contention(): Unit = {
    val t = new Thread(() => while contendUntilStopped do contended.synchronized(workerRounds += 1))
    t.setName("s9-contender")
    t.start()
    postToMain(() => contendOnMain())
  }

  private def contendOnMain(): Unit = {
    var i = 0
    while i < 2000 do {
      contended.synchronized(mainRounds += 1)
      i += 1
    }
    if mainRounds < 100000 then postToMain(() => contendOnMain())
    else {
      contendUntilStopped = false
      say(s"PASS 3 contended monitor main=$mainRounds worker=$workerRounds")
      probe4BareZio()
    }
  }

  // -------------------------------------------------------------------- 4. bare ZIO

  private var echoes = 0

  /** Control: S8's exact arrangement — fork from the host's initial entry point and post results back through the same
    * trampoline, with no module code involved.
    */
  private def probe4BareZio(): Unit = {
    val _ = Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.fork(
        ZIO.foreachDiscard(1 to 200)(_ => ZIO.succeed(postToMain(() => bumpEcho())) *> ZIO.sleep(2.millis))
      )
    }
  }

  private def bumpEcho(): Unit = {
    echoes += 1
    if echoes == 200 then {
      say(s"PASS 4 bare ZIO fibre posted $echoes times")
      probe5BridgeFromEntry()
    }
  }

  // --------------------------------------------- 5. the bridge, from the entry point

  private var pollsA = 0

  /** The bridge itself, started from the host's initial entry point. **It works here.** */
  private def probe5BridgeFromEntry(): Unit = {
    given UiRuntime[Any] = UiRuntime.default
    given Owner = Owner()
    val sig = ZIO.succeed(42).asSignal
    postToMain(() => pollA(sig))
  }

  private def pollA(sig: Signal[RemoteData[Nothing, Int]]): Unit = {
    pollsA += 1
    if sig.now == RemoteData.Done(42) then {
      say(s"PASS 5 bridge asSignal from the entry point (poll $pollsA)")
      probe6BridgeFromCallback()
    } else if pollsA < 2000 then postToMain(() => pollA(sig))
    else say(s"FAIL 5 bridge from the entry point stayed ${sig.now}")
  }

  // ----------------------------------------- 6. the bridge, from a run-loop callback

  private var pollsB = 0
  private var deadlineB = 0L

  /** THE FAILING CASE. Identical to probe 5 except the effect is started from inside a `sui_run_on_main` callback
    * rather than the host's initial entry.
    *
    * That is not an exotic position — it is what every user interaction is. A tap arrives through the run loop and the
    * handler calls `launch` or `asSignal`.
    *
    * Observed: the signal never leaves `Loading`, and the process then dies with `EXC_BAD_ACCESS` / **"Thread stack
    * size exceeded"** on the main thread. Earlier runs reported the same fault as "Could not determine thread index for
    * stack guard region", which is the same overflow seen by a handler that could not attribute it. iOS gives the main
    * thread 1 MB; Scala-created threads get far more, which is why probes 3 and 4 — both of which do their real work on
    * other threads — are unaffected.
    *
    * Ruled out: `GcState` (this posts unguarded and behaves identically) and where `Runtime.default` is first
    * constructed (probe 5 warms it; nothing changes).
    */
  private def probe6BridgeFromCallback(): Unit = {
    say("running 6: starting an effect from inside a run-loop callback")
    postUnguarded(() => forkInsideCallback())
  }

  private def forkInsideCallback(): Unit = {
    given UiRuntime[Any] = UiRuntime.default
    given Owner = Owner()
    val sig = ZIO.succeed(42).asSignal
    deadlineB = java.lang.System.currentTimeMillis() + 8000
    postToMain(() => pollB(sig))
  }

  private def pollB(sig: Signal[RemoteData[Nothing, Int]]): Unit = {
    pollsB += 1
    if sig.now == RemoteData.Done(42) then say(s"PASS 6 bridge asSignal from a run-loop callback (poll $pollsB)")
    else if java.lang.System.currentTimeMillis() < deadlineB then postToMain(() => pollB(sig))
    else say(s"FAIL 6 stayed ${sig.now} after $pollsB polls / 8 s")
  }

}
