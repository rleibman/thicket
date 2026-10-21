/*
 * Copyright 2026 Roberto Leibman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package thicket.effect.zio

import zio.test.*
import thicket.signals.Checks

import _root_.zio.*
import _root_.zio.stream.{SubscriptionRef, ZStream}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.mutable
import thicket.core.{RemoteData, UiThread}
import thicket.signals.{ErrorSink, Owner, ThreadGuard, Var}

/** The bridge's contract, exercised without a device.
  *
  * `UiThread` is installed as "run it here", so posts are synchronous and the tests are deterministic; on a real host
  * the same posts land on the platform's main thread.
  */

object EffectZioSpec extends ZIOSpecDefault {

  given UiRuntime[Any] = UiRuntime.default

  /** Waits for a condition the bridge will satisfy from another fibre.
    *
    * `java.lang.System` spelled out because `import zio.*` brings `zio.System` into scope and shadows it — S8 finding
    * 2, met in the wild.
    */
  private def eventually(what: String)(cond: => Boolean): Unit = TestUiThread.eventually(what)(cond)

  private val bridge = zio.test.suite("Bridge")(
    test("a succeeding effect goes Loading -> Done") {
      val chk = Checks()
      val o = Owner(); given Owner = o
      val signal = ZIO.succeed(41).map(_ + 1).asSignal
      eventually("Done(42)")(signal.now == RemoteData.Done(42))
      o.dispose()
      chk.result
    },
    test("a failing effect goes Loading -> Failed, carrying the typed error") {
      val chk = Checks()
      val o = Owner(); given Owner = o
      val signal = ZIO.fail("nope").asSignal
      eventually("Failed")(signal.now == RemoteData.Failed("nope"))
      o.dispose()
      chk.result
    },
    test("Loading is observable before completion") {
      val chk = Checks()
      val o = Owner(); given Owner = o
      val gate = new AtomicBoolean(false)
      val signal = ZIO.attemptBlocking { while (!gate.get()) Thread.sleep(1); 7 }.orDie.asSignal
      chk.eq(signal.now, RemoteData.Loading)
      gate.set(true)
      eventually("Done(7)")(signal.now == RemoteData.Done(7))
      o.dispose()
      chk.result
    },
    test("disposing the owner interrupts the fibre") {
      val chk = Checks()
      val o = Owner(); given Owner = o
      val started = new AtomicBoolean(false)
      val finished = new AtomicBoolean(false)
      val _ = (ZIO.succeed(started.set(true)) *> ZIO.sleep(5.seconds) *>
        ZIO.succeed(finished.set(true))).asSignal
      eventually("fibre started")(started.get())

      o.dispose()
      Thread.sleep(100)
      chk.yes(!finished.get(), "an interrupted fibre must not run its continuation")
      chk.result
    },
    test("launch reports a typed failure through the ErrorPresenter") {
      val chk = Checks()
      val o = Owner(); given Owner = o
      val seen = Var(Option.empty[String])
      given ErrorPresenter[String] = ErrorPresenter.into(seen)

      ZIO.fail("boom").launch
      eventually("presenter saw the error")(seen.now.contains("boom"))
      o.dispose()
      chk.result
    },
    test("launch runs the effect for its side effects") {
      val chk = Checks()
      val o = Owner(); given Owner = o
      given ErrorPresenter[Nothing] = summon[ErrorPresenter[Nothing]]
      val counter = new AtomicInteger(0)
      ZIO.succeed(counter.incrementAndGet()).unit.launch
      eventually("side effect ran")(counter.get() == 1)
      o.dispose()
      chk.result
    },
    test("a stream mirrors into a signal") {
      val chk = Checks()
      val o = Owner(); given Owner = o
      val signal = ZStream(1, 2, 3, 4).asSignal(0)
      eventually("last element")(signal.now == 4)
      o.dispose()
      chk.result
    },
    test("disposing the owner stops a live stream") {
      val chk = Checks()
      val o = Owner(); given Owner = o
      val signal = ZStream.iterate(0)(_ + 1).schedule(Schedule.spaced(5.millis)).asSignal(-1)
      eventually("stream producing")(signal.now > 2)
      o.dispose()
      val atDispose = signal.now
      Thread.sleep(150)
      chk.eq(signal.now, atDispose, "a disposed owner's stream must stop writing")
      chk.result
    },
    test("a SubscriptionRef becomes a signal, seeded with its current value") {
      val chk = Checks()
      val o = Owner(); given Owner = o
      val rt = summon[UiRuntime[Any]]
      val ref = Unsafe.unsafe { implicit u =>
        rt.runtime.unsafe.run(SubscriptionRef.make("a")).getOrThrow()
      }
      val signal = ref.asSignal
      chk.eq(signal.now, "a", "seeded from the ref's current value")

      Unsafe.unsafe(implicit u => rt.runtime.unsafe.run(ref.set("b")).getOrThrow())
      eventually("saw the update")(signal.now == "b")
      o.dispose()
      chk.result
    },
    test("signal updates are posted through UiThread, not written from the fibre") {
      val chk = Checks()
      val o = Owner(); given Owner = o
      val posts = new AtomicInteger(0)
      UiThread.install { f =>
        posts.incrementAndGet(); f()
      }

      val signal = ZIO.succeed(1).asSignal
      eventually("Done")(signal.now == RemoteData.Done(1))
      chk.yes(posts.get() >= 1, "the bridge must marshal onto the UI thread")
      o.dispose()
      chk.result
    }
  )

  /** Collects what reaches `ErrorSink` during `body`, then restores the previous sink. */
  private def reported(body: => Unit): List[Throwable] = {
    val seen = mutable.ArrayBuffer.empty[Throwable]
    val previous = ErrorSink.installed
    ErrorSink.install(e => seen += e)
    try body
    finally ErrorSink.install(previous)
    seen.toList
  }

  /** Review 2026-10-20: P1-5, P2-13, P2-15, P0-5 — each test failed, or could not be written, before the fix. */
  private val failures = zio.test.suite("Failures and lifetimes")(
    test("a defect is reported to ErrorSink, not swallowed") {
      val chk = Checks()
      val o = Owner(); given Owner = o
      var signal: thicket.signals.Signal[RemoteData[Nothing, Int]] = null
      val seen = reported {
        signal = ZIO.die(RuntimeException("kaboom")).as(1).asSignal
        Thread.sleep(50)
        TestUiThread.drain()
      }
      chk.yes(seen.exists(_.getMessage.contains("kaboom")), seen.toString)
      chk.eq(signal.now, RemoteData.Loading, "there is no typed error to show")
      o.dispose()
      chk.result
    },
    test("launch reports a defect too") {
      val chk = Checks()
      val o = Owner(); given Owner = o
      given ErrorPresenter[Nothing] = summon[ErrorPresenter[Nothing]]
      val seen = reported {
        ZIO.succeed(throw RuntimeException("thrown inside")).unit.launch
        Thread.sleep(50)
        TestUiThread.drain()
      }
      chk.yes(seen.exists(_.getMessage.contains("thrown inside")), seen.toString)
      o.dispose()
      chk.result
    },
    test("the interruption disposal sends is not reported") {
      val chk = Checks()
      val o = Owner(); given Owner = o
      val started = AtomicBoolean(false)
      val seen = reported {
        val _ = (ZIO.succeed(started.set(true)) *> ZIO.never).as(1).asSignal
        eventually("started")(started.get())
        o.dispose()
        Thread.sleep(50)
        TestUiThread.drain()
      }
      chk.eq(seen, Nil)
      chk.result
    },
    test("a result that arrives after the owner is disposed is not delivered") {
      val chk = Checks()
      val o = Owner(); given Owner = o
      val gate = AtomicBoolean(false)
      // attemptBlocking is not interruptible, so the result is produced after disposal.
      val signal = ZIO.attemptBlocking { while !gate.get() do Thread.sleep(1); 7 }.orDie.asSignal
      o.dispose()
      gate.set(true)
      Thread.sleep(50)
      TestUiThread.drain()
      chk.eq(signal.now, RemoteData.Loading)
      chk.result
    },
    test("a failing stream surfaces its typed error through asRemoteSignal") {
      val chk = Checks()
      val o = Owner(); given Owner = o
      val signal = (ZStream(1, 2) ++ ZStream.fail("dropped")).asRemoteSignal
      eventually("Failed")(signal.now == RemoteData.Failed("dropped"))
      o.dispose()
      chk.result
    },
    test("a stream that fails under asSignal is reported, keeping its last value") {
      val chk = Checks()
      val o = Owner(); given Owner = o
      var signal: thicket.signals.Signal[Int] = null
      val seen = reported {
        signal = (ZStream(1, 2) ++ ZStream.fail("dropped")).asSignal(0)
        Thread.sleep(50)
        TestUiThread.drain()
      }
      chk.yes(seen.nonEmpty, "the failure is not silent")
      chk.eq(signal.now, 2)
      o.dispose()
      chk.result
    },
    test("a fast stream is conflated: the newest value, not one post per element") {
      val chk = Checks()
      val o = Owner(); given Owner = o
      val posts = AtomicInteger(0)
      val previous = UiThread.installed
      UiThread.install { f => posts.incrementAndGet(); previous(f) }
      val signal = ZStream.range(1, 10001).asSignal(0)
      eventually("last element")(signal.now == 10000)
      UiThread.install(previous)
      chk.yes(posts.get() < 10000, s"${posts.get()} posts for 10000 elements")
      o.dispose()
      chk.result
    },
    test("fromLayer keeps its scope open, and shutdown runs the layer's finalizers") {
      val chk = Checks()
      val released = AtomicBoolean(false)
      val layer = ZLayer.scoped(ZIO.acquireRelease(ZIO.succeed("service"))(_ => ZIO.succeed(released.set(true))))
      val rt = UiRuntime.fromLayer(layer)
      chk.no(released.get(), "acquired, and still held")
      rt.shutdown()
      chk.yes(released.get(), "released on shutdown")
      rt.shutdown()
      chk.result
    },
    test("fromLayer throws when the layer cannot be built") {
      val chk = Checks()
      val thrown =
        try { val _ = UiRuntime.fromLayer(ZLayer.fail(RuntimeException("no database"))); None }
        catch { case e: Throwable => Some(e) }
      chk.yes(thrown.exists(_.toString.contains("no database")), thrown.toString)
      chk.result
    },
    test("ErrorPresenter.into and printing") {
      val chk = Checks()
      val target = Var(Option.empty[String])
      ErrorPresenter.into(target).show("into")
      chk.eq(target.now, Some("into"))
      ErrorPresenter.printing[String].show("printed, on purpose, by a test")
      chk.result
    },
    test("with no host, UiThread fails loudly instead of running the post where it was made") {
      val chk = Checks()
      val previous = UiThread.installed
      UiThread.install(UiThread.notInstalled)
      val thrown =
        try { UiThread.run(() => ()); None }
        catch { case e: IllegalStateException => Some(e) }
      UiThread.install(previous)
      chk.yes(thrown.exists(_.getMessage.contains("Host.install")))
      chk.result
    }
  )

  /** **One spec, deliberately, holding both suites.**
    *
    * `UiThread` and `ThreadGuard` are process-global, and both suites install them. zio-test orders tests *within* a
    * spec — that is what `sequential` buys — but gives no ordering *between* specs, and runs them concurrently. As two
    * `ZIOSpecDefault` objects these raced: `RemoteScreen`'s error test timed out waiting for a state the other suite's
    * fibres were competing for. munit never showed it, because it runs test classes one at a time under
    * `parallelExecution := false`.
    *
    * Suites that share process-global state cannot be separate specs. Keeping them in one file-pair but a single spec
    * is what makes `sequential` actually cover them.
    */
  def spec =
    zio.test.suite("effect-zio")(
      bridge,
      failures,
      RemoteScreenTests.suite
    ) @@ TestAspect.sequential @@ TestAspect.before(ZIO.succeed {
      TestUiThread.install()
      // `ThreadGuard` is process-global, and sbt runs several projects' suites in one JVM, so
      // a guard another module's tests installed would reject the bridge's signal writes from
      // a ZIO fibre. A suite has to establish the global state it depends on, not assume it.
      ThreadGuard.install(ThreadGuard.off)
    })

}
