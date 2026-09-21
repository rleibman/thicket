package scalaui.signals

/** The single-threaded contract (A-06). JVM and Native only; JS has no threads. */
class ThreadGuardSuite extends munit.FunSuite {

  override def afterEach(context: AfterEach): Unit =
    ThreadGuard.install(ThreadGuard.off)

  test("writing from another thread is rejected") {
    ThreadGuard.install(ThreadGuard.owningThread)
    val a = Var(0)
    a.set(1) // binds the guard to this thread
    assertEquals(a.now, 1)

    @volatile var caught: Option[Throwable] = None
    val t = new Thread(() => caught = scala.util.Try(a.set(2)).failed.toOption)
    t.start()
    t.join()

    caught match {
      case Some(e: IllegalStateException) =>
        assert(e.getMessage.contains("single-threaded"), e.getMessage)
      case other => fail(s"expected IllegalStateException, got $other")
    }
    assertEquals(a.now, 1, "the rejected write must not have taken effect")
  }

  test("ThreadGuard.off allows cross-thread writes") {
    ThreadGuard.install(ThreadGuard.off)
    val a = Var(0)
    val t = new Thread(() => a.set(42))
    t.start()
    t.join()
    assertEquals(a.now, 42)
  }
}
