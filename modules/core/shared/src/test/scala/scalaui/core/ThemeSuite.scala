package scalaui.core

import scalaui.core.dsl.*
import scalaui.signals.Owner

/** App-level theming.
  *
  * The property that matters most is the *negative* one: an app that sets no theme must
  * produce widgets with no colour set at all, so every one of them keeps following the
  * user's platform theme, dark mode and contrast settings.
  */
class ThemeSuite extends munit.FunSuite {

  override def afterEach(context: AfterEach): Unit = Theme.install(Theme.platform)

  test("the default theme sets no colours at all") {
    Theme.install(Theme.platform)
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    val m = Reconciler.mount(r, Column()(Label("hello"), Button("go")(())))

    r.childrenOf(m.handle).foreach { h =>
      assert(!r.nodes(h).props.contains("tint"), "no tint means the platform decides")
      assert(!r.nodes(h).props.contains("fill"), "no fill means the platform decides")
    }
    o.dispose()
  }

  test("an overridden role reaches the widgets that use it") {
    Theme.install(
      Theme.platform
        .withColor(ColorRole.OnSurface, Rgb(0x11, 0x22, 0x33))
        .withColor(ColorRole.Accent, Rgb(0xff, 0x00, 0x66))
    )
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    val m = Reconciler.mount(r, Column()(Label("hello"), Button("go")(())))
    val kids = r.childrenOf(m.handle)

    assertEquals(r.nodes(kids.head).props.get("tint"), Some("17,34,51"))
    assertEquals(r.nodes(kids(1)).props.get("fill"), Some("255,0,102"))
    o.dispose()
  }

  test("roles are independent: overriding one leaves the others to the platform") {
    Theme.install(Theme.platform.withColor(ColorRole.Accent, Rgb(1, 2, 3)))
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    val m = Reconciler.mount(r, Column()(Label("hello"), Button("go")(())))
    val kids = r.childrenOf(m.handle)

    assert(!r.nodes(kids.head).props.contains("tint"), "OnSurface was not overridden")
    assertEquals(r.nodes(kids(1)).props.get("fill"), Some("1,2,3"))
    o.dispose()
  }

  test("secondary text resolves a different role from primary text") {
    Theme.install(
      Theme.platform
        .withColor(ColorRole.OnSurface, Rgb(0, 0, 0))
        .withColor(ColorRole.OnSurfaceSecondary, Rgb(128, 128, 128))
    )
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    val m = Reconciler.mount(
      r,
      Column()(
        Label("primary"),
        Label("secondary", emphasis = scalaui.renderer.Emphasis.Secondary)
      )
    )
    val kids = r.childrenOf(m.handle)
    assertEquals(r.nodes(kids.head).props.get("tint"), Some("0,0,0"))
    assertEquals(r.nodes(kids(1)).props.get("tint"), Some("128,128,128"))
    o.dispose()
  }

  test("a button may opt into a different role, such as Danger") {
    Theme.install(Theme.platform.withColor(ColorRole.Danger, Rgb(200, 0, 0)))
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    val m = Reconciler.mount(r, Column()(Button("Delete", role = ColorRole.Danger)(())))
    assertEquals(r.nodes(r.childrenOf(m.handle).head).props.get("fill"), Some("200,0,0"))
    o.dispose()
  }

  test("branding a background derives a readable foreground") {
    // The failure this prevents: dark text on a dark brand colour, because the app set a
    // background and the platform's text colour was chosen for the platform's background.
    Theme.install(Theme.platform.withColor(ColorRole.Accent, Rgb(0x2E, 0x6F, 0x40)))
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    val m = Reconciler.mount(r, Column()(Button("go")(())))
    val b = r.childrenOf(m.handle).head

    assertEquals(r.nodes(b).props.get("fill"), Some("46,111,64"))
    assertEquals(r.nodes(b).props.get("tint"), Some("255,255,255"), "white on a dark accent")
    o.dispose()
  }

  test("a light accent gets black text, a dark accent white") {
    assertEquals(Rgb(0x2E, 0x6F, 0x40).contrasting, Rgb(255, 255, 255))
    assertEquals(Rgb(0xFF, 0xE0, 0x66).contrasting, Rgb(0, 0, 0))
    assertEquals(Rgb(255, 255, 255).contrasting, Rgb(0, 0, 0))
    assertEquals(Rgb(0, 0, 0).contrasting, Rgb(255, 255, 255))
  }

  test("an explicit OnAccent wins over the derived one") {
    Theme.install(
      Theme.platform
        .withColor(ColorRole.Accent, Rgb(0, 0, 0))
        .withColor(ColorRole.OnAccent, Rgb(255, 0, 0))
    )
    assertEquals(Theme.active.get(ColorRole.OnAccent), Some(Rgb(255, 0, 0)))
  }

  test("with no accent there is no OnAccent either") {
    Theme.install(Theme.platform)
    assertEquals(Theme.active.get(ColorRole.OnAccent), None)
  }

  test("Rgb renders as hex for stylesheet-based renderers") {
    assertEquals(Rgb(0xff, 0x00, 0x66).hex, "#ff0066")
    assertEquals(Rgb(1, 2, 3).hex, "#010203")
  }
}
