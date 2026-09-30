package thicket.zio

import thicket.signals.Checks

import _root_.zio.*
// `test` is ambiguous here: `import zio.*` brings in the *package* `zio.test`, and
// `import zio.test.*` brings in the *method* of the same name. Scala 3 treats both
// wildcards as equal precedence whichever order they appear in, so the call sites below
// are qualified rather than the imports reshuffled.
import zio.test.Spec
import thicket.core.*
import thicket.core.dsl.*
import thicket.signals.Owner
import java.util.concurrent.atomic.AtomicBoolean

/** What the bridge is actually for: a screen whose content is an exhaustive match on the state of an effect. The
  * `RemoteData(...)` call below does not compile if a case is missing.
  */

object RemoteScreenTests {

  given UiRuntime[Any] = UiRuntime.default

  final case class Recipe(
    id:   Int,
    name: String
  )

  final case class LoadFailed(message: String)

  /** A screen written the way an app would write it. */
  private def recipesScreen(
    load: IO[LoadFailed, List[Recipe]]
  )(using
    UiRuntime[Any],
    Owner
  ): Element = {
    val recipes = load.asSignal
    Column(spacing = 8, padding = 16)(
      Label("Recipes"),
      RemoteData(recipes) {
        case RemoteData.Loading   => Label("Loading…")
        case RemoteData.Failed(e) => Label(s"Could not load: ${e.message}")
        case RemoteData.Done(items) =>
          Column(spacing = 4)(
            ForEach(thicket.signals.Signal.const(items), key = (r: Recipe) => r.id)(r => Label(r.map(_.name)))
          )
      }
    )
  }

  private def eventually(what: String)(cond: => Boolean): Unit = TestUiThread.eventually(what)(cond)

  private def texts(
    r:    TestRenderer,
    root: Int
  ): Seq[String] =

    r.childrenOf(root).flatMap { c =>
      val own = r.text(c)
      if own.nonEmpty then Seq(own) else r.childrenOf(c).map(r.text)
    }

  val suite: Spec[Any, Throwable] = zio.test.suite("RemoteScreen")(
    zio.test.test("the screen shows Loading, then the loaded rows") {
      val chk = Checks()
      val o = Owner(); given Owner = o
      val r = TestRenderer()
      val gate = new AtomicBoolean(false)
      val load = ZIO.attemptBlocking {
        while (!gate.get()) Thread.sleep(1)
        List(Recipe(1, "Tagine"), Recipe(2, "Dal"))
      }.orDie

      val m = Reconciler.mount(r, recipesScreen(load))
      chk.eq(texts(r, m.handle), Seq("Recipes", "Loading…"))

      gate.set(true)
      eventually("rows rendered")(texts(r, m.handle) == Seq("Recipes", "Tagine", "Dal"))
      o.dispose()
      chk.result
    },
    zio.test.test("a failure renders the error branch, with the typed error in hand") {
      val chk = Checks()
      val o = Owner(); given Owner = o
      val r = TestRenderer()
      val m = Reconciler.mount(r, recipesScreen(ZIO.fail(LoadFailed("offline"))))
      eventually("error shown")(texts(r, m.handle).contains("Could not load: offline"))
      o.dispose()
      chk.result
    },
    zio.test.test("leaving the screen interrupts the load") {
      val chk = Checks()
      val o = Owner(); given Owner = o
      val r = TestRenderer()
      val completed = new AtomicBoolean(false)
      val load = (ZIO.sleep(5.seconds) *> ZIO.succeed(completed.set(true)))
        .as(List.empty[Recipe])

      val m = Reconciler.mount(r, recipesScreen(load))
      chk.eq(texts(r, m.handle), Seq("Recipes", "Loading…"))

      // Unmounting is what a navigation push does.
      m.dispose()
      o.dispose()
      Thread.sleep(100)
      chk.yes(!completed.get(), "the in-flight load must be interrupted with the screen")
      chk.result
    }
  )

}
