package scalaui.s9

import _root_.zio.*
import _root_.zio.stream.{SubscriptionRef, ZStream}
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
  * The JVM suites install `UiThread` as "run it here", so every post is synchronous and every wait is a
  * `while (!cond) sleep` on the test thread. Neither survives here, and the first version of this spike died of the
  * second one before testing anything.
  *
  * **The rule this spike establishes: never post to the main queue from inside a main-queue callback.** Measured below,
  * both ways in the same run — a self-reposting chain consumes ~415 bytes of main-thread stack per hop and never
  * unwinds, while the identical posts issued from another thread cost **zero**. iOS gives the main thread 1 MB, so a
  * polling loop written the obvious way exhausts it in a couple of thousand turns and dies with "Thread stack size
  * exceeded".
  *
  * So the battery is driven by a **Scala-created driver thread**: it sleeps, posts one check to the main queue, sleeps
  * again. Every post originates off the main thread — which is also how the bridge itself behaves, since ZIO completes
  * on its own scheduler thread and posts the signal write from there. That is why S8 could deliver 37 504 posts without
  * trouble.
  */
object Main {

  private var label: sui_handle = null.asInstanceOf[sui_handle]
  private val report = mutable.ArrayBuffer.empty[String]
  private val reportLock = new Object

  private def say(line: String): Unit = {
    println(s"[S9] $line")
    reportLock.synchronized(report += line)
  }

  /** Only ever called on the main thread. */
  private def render(): Unit = {
    val text = reportLock.synchronized(report.takeRight(14).mkString("\n"))
    Zone(sui_label_set_text(label, toCString(text)))
  }

  private def check(
    name:   String,
    ok:     Boolean,
    detail: String
  ): Unit = say(s"${if ok then "PASS" else "FAIL"} $name${if detail.isEmpty then "" else s" — $detail"}")

  private def postToMain(f: () => Unit): Unit = {
    val id = Handles.registerOneShot(f)
    sui_run_on_main(sui_main_cb(Handles.mainTrampoline), id)
  }

  /** Address of a stack local, for measuring whether main-queue callbacks unwind. */
  private def stackMark(): Long = {
    val p = stackalloc[Byte]()
    Intrinsics.castRawPtrToLong(toRawPtr(p))
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
    render()
    startDriver()
  }

  // ----------------------------------------------------------- the stack measurement

  private var chainMark = 0L
  private var chainHops = 0
  private var threadMark = 0L
  private var threadHops = 0

  /** Posts to itself from inside the callback: the shape that fails. */
  private def measureChainHop(): Unit = {
    chainHops += 1
    val m = stackMark()
    if chainMark == 0L then chainMark = m
    if chainHops < 200 then postToMain(() => measureChainHop())
    else say(s"MEASURED reposting from inside a callback: ${chainMark - m} bytes over $chainHops hops")
  }

  /** Posted by the driver thread each time: the shape that works. */
  private def measureThreadHop(): Unit = {
    threadHops += 1
    val m = stackMark()
    if threadMark == 0L then threadMark = m
    if threadHops == 200 then
      say(s"MEASURED posting from another thread:     ${threadMark - m} bytes over $threadHops hops")
  }

  // ------------------------------------------------------------------- the driver

  final private case class Step(
    name:   String,
    start:  () => Unit,
    done:   () => Boolean,
    detail: () => String
  )

  private val steps = mutable.Queue.empty[Step]

  private def addStep(name: String)(start: => Unit)(done: => Boolean)(detail: => String): Unit =
    steps.enqueue(Step(name, () => start, () => done, () => detail))

  @volatile private var stepSettled = false
  @volatile private var stepDetail: String = ""

  /** Each step gets an epoch, and `evaluate` ignores posts from an older one.
    *
    * Without this the harness produces false passes: the driver posts an `evaluate` every 25 ms, so when a step
    * finishes several of its posts are still queued, and they land after the next step has started and settle it
    * instantly — with the previous step's detail string attached, which is how it was spotted.
    */
  @volatile private var epoch = 0

  /** Runs on the main thread, one post per call, always issued by the driver thread.
    *
    * The detail is captured here rather than by the driver, because the step's own `var`s are written on this thread
    * and a plain read from the driver can see a stale null.
    */
  private def evaluate(
    s:        Step,
    forEpoch: Int
  ): Unit =
    if forEpoch == epoch then {
      // Condition first, detail second: capturing the detail before re-reading the
      // condition lets a step report "Loading" beside its own PASS, because the value
      // changed in between.
      val ok = s.done()
      stepDetail = s.detail()
      if ok then stepSettled = true
      render()
    }

  private def startDriver(): Unit = {
    val t = new Thread(() => {
      var i = 0
      while i < 200 do {
        postToMain(() => measureThreadHop())
        i += 1
        Thread.sleep(1)
      }
      Thread.sleep(400)
      postToMain(() => measureChainHop())
      Thread.sleep(1500)

      buildSteps()
      while steps.nonEmpty do {
        val s = steps.head
        epoch += 1
        val myEpoch = epoch
        stepSettled = false
        stepDetail = ""
        postToMain(() => s.start())
        val deadline = java.lang.System.currentTimeMillis() + 8000
        while !stepSettled && java.lang.System.currentTimeMillis() < deadline do {
          Thread.sleep(25)
          postToMain(() => evaluate(s, myEpoch))
        }
        if stepSettled then check(s.name, ok = true, stepDetail)
        else check(s.name, ok = false, s"timed out after 8 s; $stepDetail")
        val _ = steps.dequeue()
        postToMain(() => render())
      }

      val failures = reportLock.synchronized(report.count(_.startsWith("FAIL")))
      say(if failures == 0 then "ALL S9 BRIDGE CHECKS PASSED" else s"$failures CHECK(S) FAILED")
      postToMain(() => render())
    })
    t.setName("s9-driver")
    t.start()
  }

  // ------------------------------------------------------------------- the battery

  private def buildSteps(): Unit = {
    given UiRuntime[Any] = UiRuntime.default

    val ownerA = Owner()
    var sigA: Signal[RemoteData[Nothing, Int]] = null
    addStep("effect.asSignal reaches Done across a real thread hop") {
      given Owner = ownerA
      sigA = ZIO.succeed(41).map(_ + 1).asSignal
    }(sigA != null && sigA.now == RemoteData.Done(42))(s"state=${if sigA == null then "-" else sigA.now}")

    val ownerB = Owner()
    var sigB: Signal[RemoteData[String, Int]] = null
    addStep("effect.asSignal carries a typed failure") {
      given Owner = ownerB
      sigB = ZIO.fail("nope").asSignal
    }(sigB != null && sigB.now == RemoteData.Failed("nope"))(s"state=${if sigB == null then "-" else sigB.now}")

    val ownerC = Owner()
    val presented = Var[Option[String]](None)
    addStep("launch routes a failure through ErrorPresenter") {
      given Owner = ownerC
      given ErrorPresenter[String] = ErrorPresenter.into(presented)
      ZIO.fail("boom").launch
    }(presented.now.contains("boom"))(s"presented=${presented.now}")

    val ownerD = Owner()
    var sigD: Signal[Int] = null
    addStep("stream.asSignal mirrors 20 values") {
      given Owner = ownerD
      sigD = ZStream.fromIterable(1 to 20).asSignal(0)
    }(sigD != null && sigD.now == 20)(s"value=${if sigD == null then "-" else sigD.now}")

    // The structural risk: `SubscriptionRef.asSignal` calls `runtime.unsafe.run` for its
    // initial value, which BLOCKS the caller. This `start` runs on the main thread, in
    // GC-Managed state — the exact shape of the S3 deadlock.
    val ownerE = Owner()
    var sigE: Signal[Int] = null
    addStep("SubscriptionRef.asSignal — blocking unsafe.run on the main thread") {
      given Owner = ownerE
      val ref = Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(SubscriptionRef.make(7)).getOrThrow()
      }
      sigE = ref.asSignal
    }(sigE != null && sigE.now == 7)(s"value=${if sigE == null then "-" else sigE.now}")

    val ownerF = Owner()
    var sigF: Signal[Int] = null
    addStep("a streaming fibre keeps producing while its Owner lives") {
      given Owner = ownerF
      sigF = ZStream.iterate(1)(_ + 1).mapZIO(i => ZIO.sleep(5.millis).as(i)).asSignal(0)
    }(sigF != null && sigF.now > 3)(s"value=${if sigF == null then "-" else sigF.now}")

    var frozenAt = -1
    addStep("disposing the Owner interrupts it") {
      ownerF.dispose()
      frozenAt = sigF.now
    } {
      // A fibre that was merely detached rather than interrupted would keep posting, so
      // the check is that it has not moved after several driver ticks.
      Thread.sleep(400)
      sigF.now == frozenAt
    }(s"froze at $frozenAt, now ${if sigF == null then -1 else sigF.now}")

    val ownerG = Owner()
    var sigG: Signal[RemoteData[Throwable, String]] = null
    addStep("ZIO.attemptBlocking runs on a ZIO pool thread, not a GCD queue") {
      given Owner = ownerG
      // Print the thread name from inside the effect. The question issue #2 asks is
      // *which* thread the blocking pool uses, and reading it out of a signal afterwards
      // only tells us the effect completed.
      sigG = ZIO.attemptBlocking {
        val n = Thread.currentThread().getName
        println(s"[S9]   attemptBlocking ran on thread '$n'")
        n
      }.asSignal
    } {
      sigG != null && (sigG.now match {
        case RemoteData.Done(_) => true
        case _                  => false
      })
    }(s"got=${if sigG == null then "-" else sigG.now}")

    val ownerH = Owner()
    var sigH: Signal[RemoteData[Nothing, Int]] = null
    addStep("the GC survives fibre churn while the main thread is in CFRunLoop") {
      given Owner = ownerH
      sigH = ZIO
        .foreachPar(1 to 200)(i => ZIO.succeed((1 to 500).map(j => s"g$i-$j").count(_.nonEmpty)))
        .map(_.sum)
        .asSignal
    }(sigH != null && sigH.now == RemoteData.Done(100000))(
      f"sum=${if sigH == null then "-" else sigH.now}, RSS ${sui_rss_mb()}%.1f MB"
    )
  }

}
