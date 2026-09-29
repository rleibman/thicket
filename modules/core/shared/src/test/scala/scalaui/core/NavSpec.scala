package scalaui.core

import zio.test.*
import scalaui.signals.Checks

import scalaui.core.dsl.*
import scalaui.signals.Owner

/** Navigation as a value: the back stack is a `List` of a route ADT, so these are ordinary
  * data assertions — no emulator, no screenshots, no "is the right fragment attached".
  */

object NavSpec extends ZIOSpecDefault {


  enum Route {
    case Home
    case Detail(id: Int)
    case Settings
  }


  import Route.*


  private def texts(r: TestRenderer, root: Int): Seq[String] =

    r.childrenOf(root).flatMap(c => r.childrenOf(c)).map(r.text)


  /** The app's screens. Being a plain function over an ADT, the compiler checks that every
    * route has one — the property §11.2 claims and the reason there is no route registry.
    */
  private def screenFor(nav: Nav[Route]): Route => Screen = {
    case Home      => Screen("Home", Column()(Label("home"), Button("open")(nav.push(Detail(1)))))
    case Detail(i) => Screen(s"Detail $i", Column()(Label(s"detail $i")))
    case Settings  => Screen("Settings", Column()(Label("settings")))
  }

  def spec = suite("Nav")(

  // -- the stack ------------------------------------------------------------

  test("push and pop") {
    val chk = Checks()
    val nav = Nav[Route](Home)
    chk.eq(nav.routes.now, List(Home))
    chk.yes(!nav.canGoBack.now)

    nav.push(Detail(1))
    chk.eq(nav.routes.now, List(Detail(1), Home))
    chk.yes(nav.canGoBack.now)
    chk.eq(nav.current.now, Detail(1))

    chk.yes(nav.pop())
    chk.eq(nav.routes.now, List(Home))
    chk.result
  },

  test("the root cannot be popped, and pop reports that") {
    val chk = Checks()
    val nav = Nav[Route](Home)
    chk.yes(!nav.pop(), "pop at the root returns false so the host can defer to the system")
    chk.eq(nav.routes.now, List(Home))
    chk.result
  },

  test("replace swaps the top without growing the stack") {
    val chk = Checks()
    val nav = Nav[Route](Home)
    nav.push(Detail(1))
    nav.replace(Detail(2))
    chk.eq(nav.routes.now, List(Detail(2), Home))
    chk.eq(nav.depth.now, 2)
    chk.result
  },

  test("reset clears down to one screen") {
    val chk = Checks()
    val nav = Nav[Route](Home)
    nav.push(Detail(1))
    nav.push(Settings)
    nav.reset(Home)
    chk.eq(nav.routes.now, List(Home))
    chk.yes(!nav.canGoBack.now)
    chk.result
  },

  test("popTo unwinds to a route on the stack, and reports when it is absent") {
    val chk = Checks()
    val nav = Nav[Route](Home)
    nav.push(Detail(1))
    nav.push(Settings)
    chk.yes(nav.popTo(Home))
    chk.eq(nav.routes.now, List(Home))
    chk.yes(!nav.popTo(Settings), "not on the stack")
    chk.eq(nav.routes.now, List(Home))
    chk.result
  },

  test("the same route pushed twice makes two distinct entries") {
    val chk = Checks()
    val nav = Nav[Route](Home)
    nav.push(Detail(1))
    nav.push(Detail(1))
    chk.eq(nav.depth.now, 3)
    chk.eq(nav.stack.now.map(_.id).distinct.size, 3, "ids are unique per entry")
    chk.result
  },

  test("a stack is restorable from its routes") {
    val chk = Checks()
    val nav = Nav[Route](Home)
    nav.push(Detail(7))
    nav.push(Settings)
    val saved = nav.routes.now // this is all a host needs to persist

    val fresh = Nav[Route](Home)
    fresh.restore(saved)
    chk.eq(fresh.routes.now, saved)
    chk.yes(fresh.canGoBack.now)
    chk.result
  },

  // -- NavHost --------------------------------------------------------------

  test("NavHost renders only the top screen, and follows the stack") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r    = TestRenderer()
    val nav  = Nav[Route](Home)
    val host = NavHost(nav)(screenFor(nav))
    val m    = Reconciler.mount(r, host.element)

    chk.eq(texts(r, m.handle), Seq("home", "open"))

    nav.push(Detail(1))
    chk.eq(texts(r, m.handle), Seq("detail 1"), "only the top screen is mounted")

    chk.yes(nav.pop())
    chk.eq(texts(r, m.handle), Seq("home", "open"))
    o.dispose()
    chk.result
  },

  test("navigating from inside a screen works") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r    = TestRenderer()
    val nav  = Nav[Route](Home)
    val host = NavHost(nav)(screenFor(nav))
    val m    = Reconciler.mount(r, host.element)

    val openButton = r.childrenOf(r.childrenOf(m.handle).head)(1)
    r.tap(openButton)

    chk.eq(nav.routes.now, List(Detail(1), Home))
    chk.eq(texts(r, m.handle), Seq("detail 1"))
    o.dispose()
    chk.result
  },

  test("title tracks the top screen") {
    val chk = Checks()
    val nav  = Nav[Route](Home)
    val host = NavHost(nav)(screenFor(nav))
    chk.eq(host.title.now, "Home")
    nav.push(Detail(42))
    chk.eq(host.title.now, "Detail 42")
    nav.push(Settings)
    chk.eq(host.title.now, "Settings")
    chk.yes(nav.pop())
    chk.eq(host.title.now, "Detail 42")
    chk.result
  },

  test("leaving a screen disposes the effects it created") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val nav   = Nav[Route](Home)
    val ticks = scalaui.signals.Var(0)

    val host = NavHost(nav) {
      case Home      => Screen("Home", Column()(Label(ticks.map(n => s"home $n"))))
      case Detail(i) => Screen("Detail", Column()(Label(s"detail $i")))
      case Settings  => Screen("Settings", Column()(Label("settings")))
    }

    val _ = Reconciler.mount(r, host.element)
    ticks.set(1)
    val opsOnHome = r.opCount
    ticks.set(2)
    chk.eq(r.opCount - opsOnHome, 1, "home's label is live")

    nav.push(Detail(1))
    val opsOnDetail = r.opCount
    ticks.set(3)
    chk.eq(r.opCount, opsOnDetail, "home's effect is gone once it is off screen")
    o.dispose()
    chk.result
  },

  test("pushing the same route twice gives a fresh screen, not the old widgets") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r    = TestRenderer()
    val nav  = Nav[Route](Home)
    val host = NavHost(nav)(screenFor(nav))
    val m    = Reconciler.mount(r, host.element)

    nav.push(Detail(1))
    val first = r.childrenOf(m.handle).head
    chk.yes(nav.pop())
    nav.push(Detail(1))
    val second = r.childrenOf(m.handle).head

    chk.ne(first, second, "a re-pushed route must not reuse the popped screen")
    o.dispose()
    chk.result
  }
  ) @@ TestAspect.sequential
}
