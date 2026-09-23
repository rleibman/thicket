package scalaui.zio

import _root_.zio.*
import _root_.zio.stream.{SubscriptionRef, ZStream}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scalaui.core.{RemoteData, UiThread}
import scalaui.signals.{Owner, ThreadGuard, Var}

/** The bridge's contract, exercised without a device.
  *
  * `UiThread` is installed as "run it here", so posts are synchronous and the tests are
  * deterministic; on a real host the same posts land on the platform's main thread.
  */
class BridgeSuite extends munit.FunSuite {

  given UiRuntime[Any] = UiRuntime.default

  override def beforeEach(context: BeforeEach): Unit = {
    UiThread.install(f => f())
    // `ThreadGuard` is process-global, and sbt runs several projects' suites in one JVM, so
    // a guard another module's tests installed would reject the bridge's signal writes from
    // a ZIO fibre. A suite has to establish the global state it depends on, not assume it.
    ThreadGuard.install(ThreadGuard.off)
  }

  /** Waits for a condition the bridge will satisfy from another fibre.
    *
    * `java.lang.System` spelled out because `import zio.*` brings `zio.System` into scope
    * and shadows it — S8 finding 2, met in the wild.
    */
  private def eventually(what: String)(cond: => Boolean): Unit = {
    val deadline = java.lang.System.currentTimeMillis() + 3000
    while (!cond && java.lang.System.currentTimeMillis() < deadline) { Thread.sleep(2) }
    assert(cond, s"timed out waiting for: $what")
  }

  test("a succeeding effect goes Loading -> Done") {
    val o = Owner(); given Owner = o
    val signal = ZIO.succeed(41).map(_ + 1).asSignal
    eventually("Done(42)")(signal.now == RemoteData.Done(42))
    o.dispose()
  }

  test("a failing effect goes Loading -> Failed, carrying the typed error") {
    val o = Owner(); given Owner = o
    val signal = ZIO.fail("nope").asSignal
    eventually("Failed")(signal.now == RemoteData.Failed("nope"))
    o.dispose()
  }

  test("Loading is observable before completion") {
    val o = Owner(); given Owner = o
    val gate   = new AtomicBoolean(false)
    val signal = ZIO.attemptBlocking { while (!gate.get()) { Thread.sleep(1) }; 7 }.orDie.asSignal
    assertEquals(signal.now, RemoteData.Loading)
    gate.set(true)
    eventually("Done(7)")(signal.now == RemoteData.Done(7))
    o.dispose()
  }

  test("disposing the owner interrupts the fibre") {
    val o = Owner(); given Owner = o
    val started  = new AtomicBoolean(false)
    val finished = new AtomicBoolean(false)
    val _ = (ZIO.succeed(started.set(true)) *> ZIO.sleep(5.seconds) *>
      ZIO.succeed(finished.set(true))).asSignal
    eventually("fibre started")(started.get())

    o.dispose()
    Thread.sleep(100)
    assert(!finished.get(), "an interrupted fibre must not run its continuation")
  }

  test("launch reports a typed failure through the ErrorPresenter") {
    val o = Owner(); given Owner = o
    val seen = Var(Option.empty[String])
    given ErrorPresenter[String] = ErrorPresenter.into(seen)

    ZIO.fail("boom").launch
    eventually("presenter saw the error")(seen.now.contains("boom"))
    o.dispose()
  }

  test("launch runs the effect for its side effects") {
    val o = Owner(); given Owner = o
    given ErrorPresenter[Nothing] = summon[ErrorPresenter[Nothing]]
    val counter = new AtomicInteger(0)
    ZIO.succeed(counter.incrementAndGet()).unit.launch
    eventually("side effect ran")(counter.get() == 1)
    o.dispose()
  }

  test("a stream mirrors into a signal") {
    val o = Owner(); given Owner = o
    val signal = ZStream(1, 2, 3, 4).asSignal(0)
    eventually("last element")(signal.now == 4)
    o.dispose()
  }

  test("disposing the owner stops a live stream") {
    val o = Owner(); given Owner = o
    val signal = ZStream.iterate(0)(_ + 1).schedule(Schedule.spaced(5.millis)).asSignal(-1)
    eventually("stream producing")(signal.now > 2)
    o.dispose()
    val atDispose = signal.now
    Thread.sleep(150)
    assertEquals(signal.now, atDispose, "a disposed owner's stream must stop writing")
  }

  test("a SubscriptionRef becomes a signal, seeded with its current value") {
    val o = Owner(); given Owner = o
    val rt = summon[UiRuntime[Any]]
    val ref = Unsafe.unsafe { implicit u =>
      rt.runtime.unsafe.run(SubscriptionRef.make("a")).getOrThrow()
    }
    val signal = ref.asSignal
    assertEquals(signal.now, "a", "seeded from the ref's current value")

    Unsafe.unsafe { implicit u => rt.runtime.unsafe.run(ref.set("b")).getOrThrow() }
    eventually("saw the update")(signal.now == "b")
    o.dispose()
  }

  test("signal updates are posted through UiThread, not written from the fibre") {
    val o = Owner(); given Owner = o
    val posts = new AtomicInteger(0)
    UiThread.install { f => posts.incrementAndGet(); f() }

    val signal = ZIO.succeed(1).asSignal
    eventually("Done")(signal.now == RemoteData.Done(1))
    assert(posts.get() >= 1, "the bridge must marshal onto the UI thread")
    o.dispose()
  }
}
