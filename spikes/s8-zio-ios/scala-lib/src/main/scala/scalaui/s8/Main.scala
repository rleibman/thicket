package scalaui.s8

import scala.scalanative.unsafe.*
import scalaui.s8.generated.aliases.*
import scalaui.s8.generated.functions.*
import zio.*
import zio.stream.*

/** S8: does ZIO 2 run inside a Scala Native static library on iOS, and can fibers drive UI updates
  * through a main-thread executor at 60 Hz?
  */
object Main:

  private var label: sui_handle = null.asInstanceOf[sui_handle]
  private val report = scala.collection.mutable.ArrayBuffer.empty[String]
  private val reportLock = new Object

  /** Called from ZIO fibers on several threads, so the buffer needs guarding; the UI write itself
    * only ever happens on the main thread, via UiExecutor.
    */
  private def say(line: String): Unit =
    println(s"[S8] $line")
    reportLock.synchronized(report += line)

  private def renderOnUi(): Unit =
    val text = reportLock.synchronized(report.takeRight(13).mkString("\n"))
    Zone(sui_label_set_text(label, toCString(text)))

  private def sayUi(line: String): UIO[Unit] =
    ZIO.succeed(say(line)) *> ZIO.succeed(renderOnUi())

  /** `import zio.*` brings `zio.System` into scope, which shadows `java.lang.System`. */
  private def nanoTime: Long = java.lang.System.nanoTime()

  private def cpuSeconds: Double = sui_cpu_seconds()

  // ------------------------------------------------------------------- entry point

  @exported("scalaui_zio_start")
  def scalaui_zio_start(rootPtr: Ptr[Byte], tickSeconds: Int): Unit =
    GcState.guarded(start(rootPtr, tickSeconds))

  private def start(rootPtr: Ptr[Byte], tickSeconds: Int): Unit =
    val root = rootPtr.asInstanceOf[sui_handle]
    label = sui_label_new()
    sui_view_set_frame(label, 16.0, 70.0, 360.0, 560.0)
    sui_view_add_child(root, label)

    val rssBefore = sui_rss_mb()
    say(f"RSS before ZIO runtime: $rssBefore%.2f MB")

    // Runtime.default first, as the brief asks: it is the thing that either starts or does
    // not, independent of any custom executor.
    val t0 = nanoTime
    val defaultRuntime = Runtime.default
    val warm = Unsafe.unsafe { implicit u =>
      defaultRuntime.unsafe.run(ZIO.succeed(1)).getOrThrowFiberFailure()
    }
    val defaultInitMs = (nanoTime - t0) / 1e6
    say(f"Runtime.default init: $defaultInitMs%.2f ms (warmup=$warm)")

    val t1 = nanoTime
    val uiRuntime = Unsafe.unsafe { implicit u =>
      Runtime.unsafe.fromLayer(Runtime.setExecutor(UiExecutor))
    }
    val uiInitMs = (nanoTime - t1) / 1e6
    say(f"UiExecutor runtime init: $uiInitMs%.2f ms")
    say(
      f"RSS after runtimes: ${sui_rss_mb()}%.2f MB (ZIO delta ${sui_rss_mb() - rssBefore}%+.2f MB)"
    )

    // Forked, not run: `run` would block the main thread inside scene setup and the UI
    // would never appear. The program drives itself and posts back through UiExecutor.
    Unsafe.unsafe { implicit u =>
      defaultRuntime.unsafe.fork(program(uiRuntime, tickSeconds))
    }

  // ---------------------------------------------------------------------- the tests

  private def program(uiRuntime: Runtime[Any], tickSeconds: Int): UIO[Unit] =
    (for
      _ <- sayUi("--- ZIO 2 on Scala Native / iOS ---")
      _ <- guard("fiberTest", fiberTest)
      _ <- guard("clockAccuracy", clockAccuracy)
      _ <- guard("blockingTest", blockingTest)
      _ <- guard("interruptionTest", interruptionTest)
      _ <- guard("subscriptionRefTest", subscriptionRefTest)
      _ <- tickTest(uiRuntime, tickSeconds)
      _ <- sayUi("ALL S8 TESTS COMPLETE")
    yield ()).catchAllCause(c => sayUi(s"FAILED: ${c.prettyPrint.take(300)}"))

  /** A silently hanging test is indistinguishable from a crashed one in a screenshot, so every step
    * carries a deadline and says so when it blows through it.
    */
  private def guard(name: String, test: UIO[Unit]): UIO[Unit] =
    test.timeout(30.seconds).flatMap {
      case Some(_) => ZIO.unit
      case None    => sayUi(s"$name TIMED OUT after 30s")
    }

  /** 100 fibers, each sleeping then computing. The checksum is scheduling-independent so it can be
    * verified against an independent computation.
    */
  private def fiberTest: UIO[Unit] =
    for
      t0 <- ZIO.succeed(nanoTime)
      results <- ZIO.foreachPar(1 to 100) { i =>
        ZIO.sleep(5.millis) *> ZIO.succeed((i * i) % 97)
      }
      ms = (nanoTime - t0) / 1e6
      sum = results.sum
      _ <- sayUi(f"100 fibers: checksum=$sum (expect 4670) in $ms%.0f ms")
    yield ()

  /** The brief asks whether javalib's clock is accurate on iOS. ZIO's Clock is built on
    * `System.nanoTime`/`currentTimeMillis`, so a wrong clock shows up as wrong sleeps.
    */
  private def clockAccuracy: UIO[Unit] =
    for
      t0 <- ZIO.succeed(nanoTime)
      _ <- ZIO.sleep(1.second)
      ms = (nanoTime - t0) / 1e6
      _ <- sayUi(f"ZIO.sleep(1s) measured $ms%.1f ms (drift ${ms - 1000}%+.1f ms)")
    yield ()

  /** Does the blocking pool actually get threads on iOS? */
  private def blockingTest: UIO[Unit] =
    for
      before <- ZIO.succeed(Thread.activeCount())
      out <- ZIO.attemptBlocking {
        Thread.sleep(50)
        Thread.currentThread().getName
      }.orDie
      after <- ZIO.succeed(Thread.activeCount())
      _ <- sayUi(s"attemptBlocking ran on '$out'; threads $before -> $after")
    yield ()

  /** Interruption has to be prompt, or a UI that cancels work on navigation leaks fibers. */
  private def interruptionTest: UIO[Unit] =
    for
      ref <- Ref.make(0)
      fiber <- (ref.update(_ + 1) *> ZIO.sleep(1.milli)).forever.fork
      _ <- ZIO.sleep(100.millis)
      t0 <- ZIO.succeed(nanoTime)
      _ <- fiber.interrupt
      ms = (nanoTime - t0) / 1e6
      iterations <- ref.get
      // A Scope closing must also interrupt what it owns.
      scoped <- Ref.make(false)
      _ <- ZIO.scoped {
        ZIO.acquireRelease(ZIO.unit)(_ => scoped.set(true)) *> ZIO.sleep(10.millis)
      }
      released <- scoped.get
      _ <- sayUi(f"interrupt after $iterations iters took $ms%.2f ms; scope released=$released")
    yield ()

  /** `changes` emits the current value on subscription and then each update. The fork needs a head
    * start: without it the first `set` can land before the subscription exists, the stream then
    * yields one value fewer than `take` wants, and the test hangs rather than fails — which is
    * exactly what happened the first time.
    */
  private def subscriptionRefTest: UIO[Unit] =
    for
      ref <- SubscriptionRef.make(0)
      seen <- Ref.make(List.empty[Int])
      watcher <- ref.changes.take(5).foreach(v => seen.update(v :: _)).fork
      _ <- ZIO.sleep(100.millis)
      _ <- ZIO.foreachDiscard(1 to 4)(i => ref.set(i) *> ZIO.sleep(20.millis))
      _ <- watcher.join
      values <- seen.get
      _ <- sayUi(s"SubscriptionRef.changes saw ${values.reverse.mkString(",")}")
    yield ()

  /** The 60 Hz question. Every tick's body runs on the UIKit main thread through UiExecutor, which
    * is the arrangement a real renderer would use.
    */
  private def tickTest(uiRuntime: Runtime[Any], seconds: Int): UIO[Unit] =
    val expected = seconds * 1000 / 16
    for
      _ <- sayUi(s"tick test: ${seconds}s at 16ms, expecting ~$expected frames")
      cpu0 <- ZIO.succeed(cpuSeconds)
      t0 <- ZIO.succeed(nanoTime)
      counter <- Ref.make(0)
      _ <- ZIO.attempt {
        Unsafe.unsafe { implicit u =>
          uiRuntime.unsafe
            .run(
              ZStream
                .tick(16.millis)
                .zipWithIndex
                .take(expected.toLong)
                .foreach { case (_, i) =>
                  counter.update(_ + 1) *> ZIO.succeed {
                    if i % 60 == 0 then
                      reportLock.synchronized {
                        if report.nonEmpty && report.last.startsWith("frame ") then
                          report.remove(report.length - 1)
                      }
                      say(f"frame $i rss=${sui_rss_mb()}%.1f MB")
                      renderOnUi()
                  }
                }
            )
            .getOrThrowFiberFailure()
        }
      }.orDie
      delivered <- counter.get
      ms = (nanoTime - t0) / 1e6
      cpu = cpuSeconds - cpu0
      pct = delivered * 100.0 / expected
      _ <- sayUi(f"ticks delivered $delivered/$expected ($pct%.1f%%) in ${ms / 1000}%.1f s")
      _ <- sayUi(
        f"tick CPU $cpu%.1f s of ${ms / 1000}%.1f s wall; UiExecutor tasks=${UiExecutor.submittedCount}"
      )
      _ <- sayUi(f"RSS after ticks: ${sui_rss_mb()}%.2f MB; handles=${Handles.count}")
    yield ()
