package scalaui.zio

import _root_.zio.*
import scalaui.core.*
import scalaui.core.dsl.*
import scalaui.signals.{Owner, ThreadGuard}
import java.util.concurrent.atomic.AtomicBoolean

/** What the bridge is actually for: a screen whose content is an exhaustive match on the
  * state of an effect. The `RemoteData(...)` call below does not compile if a case is missing.
  */
class RemoteScreenSuite extends munit.FunSuite {

  given UiRuntime[Any] = UiRuntime.default

  override def beforeEach(context: BeforeEach): Unit = {
    UiThread.install(f => f())
    // `ThreadGuard` is process-global, and sbt runs several projects' suites in one JVM, so
    // a guard another module's tests installed would reject the bridge's signal writes from
    // a ZIO fibre. A suite has to establish the global state it depends on, not assume it.
    ThreadGuard.install(ThreadGuard.off)
  }

  final case class Recipe(id: Int, name: String)
  final case class LoadFailed(message: String)

  /** A screen written the way an app would write it. */
  private def recipesScreen(
      load: IO[LoadFailed, List[Recipe]]
  )(using UiRuntime[Any], Owner): Element = {
    val recipes = load.asSignal
    Column(spacing = 8, padding = 16)(
      Label("Recipes"),
      RemoteData(recipes) {
        case RemoteData.Loading      => Label("Loading…")
        case RemoteData.Failed(e)    => Label(s"Could not load: ${e.message}")
        case RemoteData.Done(items)  =>
          Column(spacing = 4)(
            ForEach(scalaui.signals.Signal.const(items), key = (r: Recipe) => r.id)(r =>
              Label(r.map(_.name))
            )
          )
      }
    )
  }

  private def eventually(what: String)(cond: => Boolean): Unit = {
    val deadline = java.lang.System.currentTimeMillis() + 3000
    while (!cond && java.lang.System.currentTimeMillis() < deadline) { Thread.sleep(2) }
    assert(cond, s"timed out waiting for: $what")
  }

  private def texts(r: TestRenderer, root: Int): Seq[String] =
    r.childrenOf(root).flatMap { c =>
      val own = r.text(c)
      if own.nonEmpty then Seq(own) else r.childrenOf(c).map(r.text)
    }

  test("the screen shows Loading, then the loaded rows") {
    val o = Owner(); given Owner = o
    val r    = TestRenderer()
    val gate = new AtomicBoolean(false)
    val load = ZIO.attemptBlocking {
      while (!gate.get()) { Thread.sleep(1) }
      List(Recipe(1, "Tagine"), Recipe(2, "Dal"))
    }.orDie

    val m = Reconciler.mount(r, recipesScreen(load))
    assertEquals(texts(r, m.handle), Seq("Recipes", "Loading…"))

    gate.set(true)
    eventually("rows rendered")(texts(r, m.handle) == Seq("Recipes", "Tagine", "Dal"))
    o.dispose()
  }

  test("a failure renders the error branch, with the typed error in hand") {
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    val m = Reconciler.mount(r, recipesScreen(ZIO.fail(LoadFailed("offline"))))
    eventually("error shown")(texts(r, m.handle).contains("Could not load: offline"))
    o.dispose()
  }

  test("leaving the screen interrupts the load") {
    val o = Owner(); given Owner = o
    val r         = TestRenderer()
    val completed = new AtomicBoolean(false)
    val load = (ZIO.sleep(5.seconds) *> ZIO.succeed(completed.set(true)))
      .as(List.empty[Recipe])

    val m = Reconciler.mount(r, recipesScreen(load))
    assertEquals(texts(r, m.handle), Seq("Recipes", "Loading…"))

    // Unmounting is what a navigation push does.
    m.dispose()
    o.dispose()
    Thread.sleep(100)
    assert(!completed.get(), "the in-flight load must be interrupted with the screen")
  }
}
