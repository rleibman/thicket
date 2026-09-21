package scalaui.core

import scalaui.core.dsl.*
import scalaui.signals.Owner

/** Navigation as a value: the back stack is a `List` of a route ADT, so these are ordinary
  * data assertions — no emulator, no screenshots, no "is the right fragment attached".
  */
class NavSuite extends munit.FunSuite:

  enum Route:
    case Home
    case Detail(id: Int)
    case Settings

  import Route.*

  private def texts(r: TestRenderer, root: Int): Seq[String] =
    r.childrenOf(root).flatMap(c => r.childrenOf(c)).map(r.text)

  /** The app's screens. Being a plain function over an ADT, the compiler checks that every
    * route has one — the property §11.2 claims and the reason there is no route registry.
    */
  private def screenFor(nav: Nav[Route]): Route => Screen =
    case Home      => Screen("Home", Column()(Label("home"), Button("open")(nav.push(Detail(1)))))
    case Detail(i) => Screen(s"Detail $i", Column()(Label(s"detail $i")))
    case Settings  => Screen("Settings", Column()(Label("settings")))

  // -- the stack ------------------------------------------------------------

  test("push and pop"):
    val nav = Nav[Route](Home)
    assertEquals(nav.routes.now, List(Home))
    assert(!nav.canGoBack.now)

    nav.push(Detail(1))
    assertEquals(nav.routes.now, List(Detail(1), Home))
    assert(nav.canGoBack.now)
    assertEquals(nav.current.now, Detail(1))

    assert(nav.pop())
    assertEquals(nav.routes.now, List(Home))

  test("the root cannot be popped, and pop reports that"):
    val nav = Nav[Route](Home)
    assert(!nav.pop(), "pop at the root returns false so the host can defer to the system")
    assertEquals(nav.routes.now, List(Home))

  test("replace swaps the top without growing the stack"):
    val nav = Nav[Route](Home)
    nav.push(Detail(1))
    nav.replace(Detail(2))
    assertEquals(nav.routes.now, List(Detail(2), Home))
    assertEquals(nav.depth.now, 2)

  test("reset clears down to one screen"):
    val nav = Nav[Route](Home)
    nav.push(Detail(1))
    nav.push(Settings)
    nav.reset(Home)
    assertEquals(nav.routes.now, List(Home))
    assert(!nav.canGoBack.now)

  test("popTo unwinds to a route on the stack, and reports when it is absent"):
    val nav = Nav[Route](Home)
    nav.push(Detail(1))
    nav.push(Settings)
    assert(nav.popTo(Home))
    assertEquals(nav.routes.now, List(Home))
    assert(!nav.popTo(Settings), "not on the stack")
    assertEquals(nav.routes.now, List(Home))

  test("the same route pushed twice makes two distinct entries"):
    val nav = Nav[Route](Home)
    nav.push(Detail(1))
    nav.push(Detail(1))
    assertEquals(nav.depth.now, 3)
    assertEquals(nav.stack.now.map(_.id).distinct.size, 3, "ids are unique per entry")

  test("a stack is restorable from its routes"):
    val nav = Nav[Route](Home)
    nav.push(Detail(7))
    nav.push(Settings)
    val saved = nav.routes.now // this is all a host needs to persist

    val fresh = Nav[Route](Home)
    fresh.restore(saved)
    assertEquals(fresh.routes.now, saved)
    assert(fresh.canGoBack.now)

  // -- NavHost --------------------------------------------------------------

  test("NavHost renders only the top screen, and follows the stack"):
    val o = Owner(); given Owner = o
    val r    = TestRenderer()
    val nav  = Nav[Route](Home)
    val host = NavHost(nav)(screenFor(nav))
    val m    = Reconciler.mount(r, host.element)

    assertEquals(texts(r, m.handle), Seq("home", "open"))

    nav.push(Detail(1))
    assertEquals(texts(r, m.handle), Seq("detail 1"), "only the top screen is mounted")

    assert(nav.pop())
    assertEquals(texts(r, m.handle), Seq("home", "open"))
    o.dispose()

  test("navigating from inside a screen works"):
    val o = Owner(); given Owner = o
    val r    = TestRenderer()
    val nav  = Nav[Route](Home)
    val host = NavHost(nav)(screenFor(nav))
    val m    = Reconciler.mount(r, host.element)

    val openButton = r.childrenOf(r.childrenOf(m.handle).head)(1)
    r.tap(openButton)

    assertEquals(nav.routes.now, List(Detail(1), Home))
    assertEquals(texts(r, m.handle), Seq("detail 1"))
    o.dispose()

  test("title tracks the top screen"):
    val nav  = Nav[Route](Home)
    val host = NavHost(nav)(screenFor(nav))
    assertEquals(host.title.now, "Home")
    nav.push(Detail(42))
    assertEquals(host.title.now, "Detail 42")
    nav.push(Settings)
    assertEquals(host.title.now, "Settings")
    assert(nav.pop())
    assertEquals(host.title.now, "Detail 42")

  test("leaving a screen disposes the effects it created"):
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val nav   = Nav[Route](Home)
    val ticks = scalaui.signals.Var(0)

    val host = NavHost(nav):
      case Home      => Screen("Home", Column()(Label(ticks.map(n => s"home $n"))))
      case Detail(i) => Screen("Detail", Column()(Label(s"detail $i")))
      case Settings  => Screen("Settings", Column()(Label("settings")))

    val _ = Reconciler.mount(r, host.element)
    ticks.set(1)
    val opsOnHome = r.opCount
    ticks.set(2)
    assertEquals(r.opCount - opsOnHome, 1, "home's label is live")

    nav.push(Detail(1))
    val opsOnDetail = r.opCount
    ticks.set(3)
    assertEquals(r.opCount, opsOnDetail, "home's effect is gone once it is off screen")
    o.dispose()

  test("pushing the same route twice gives a fresh screen, not the old widgets"):
    val o = Owner(); given Owner = o
    val r    = TestRenderer()
    val nav  = Nav[Route](Home)
    val host = NavHost(nav)(screenFor(nav))
    val m    = Reconciler.mount(r, host.element)

    nav.push(Detail(1))
    val first = r.childrenOf(m.handle).head
    assert(nav.pop())
    nav.push(Detail(1))
    val second = r.childrenOf(m.handle).head

    assertNotEquals(first, second, "a re-pushed route must not reuse the popped screen")
    o.dispose()
