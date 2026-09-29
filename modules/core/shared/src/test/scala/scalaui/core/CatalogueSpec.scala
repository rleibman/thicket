package scalaui.core

import zio.test.*
import scalaui.signals.Checks

import scalaui.core.dsl.*
import scalaui.renderer.WidgetKind
import scalaui.signals.{Owner, Var}

/** The widgets added as the catalogue widens, and the one rule that keeps them honest: a
  * widget is done when it is mounted, driven by a signal, and read back.
  */

object CatalogueSpec extends ZIOSpecDefault {

  def spec = suite("Catalogue")(

  test("Toggle carries the same state as Checkbox, and writes back") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r    = TestRenderer()
    val dark = Var(false)
    val m    = Reconciler.mount(r, Toggle(dark)(dark.set))

    chk.eq(r.kind(m.handle), WidgetKind.Toggle)
    chk.eq(r.nodes(m.handle).props("checked"), "false")

    // App -> widget.
    dark.set(true)
    chk.eq(r.nodes(m.handle).props("checked"), "true")

    // Widget -> app: the user flicks it off.
    r.nodes(m.handle).onCheckedChange.foreach(_(false))
    chk.eq(dark.now, false)
    o.dispose()
    chk.result
  },

  test("Toggle has no label, deliberately") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    val m = Reconciler.mount(r, Toggle(Var(true))(_ => ()))
    // A GtkSwitch and a UISwitch have nowhere to put one; only Android's does. The caption
    // is a sibling, which is what a settings row looks like on all of them.
    chk.yes(!r.nodes(m.handle).props.contains("text"), r.nodes(m.handle).props.toString)
    o.dispose()
    chk.result
  },

  test("a settings row: label, spacer, switch") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r    = TestRenderer()
    val dark = Var(false)
    val m = Reconciler.mount(
      r,
      Row(spacing = 8)(Label("Dark mode"), Spacer(), Toggle(dark)(dark.set))
    )
    val kids = r.childrenOf(m.handle)
    chk.eq(kids.map(r.kind), Seq(WidgetKind.Label, WidgetKind.Spacer, WidgetKind.Toggle))
    // A Spacer's whole job is to take the room its siblings do not, which it does through
    // Grow — so a Spacer that is not growing is not a Spacer.
    chk.eq(r.nodes(kids(1)).props("grow"), "true")
    o.dispose()
    chk.result
  },

  test("ProgressBar distinguishes indeterminate from zero") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r    = TestRenderer()
    val work = Var[Option[Double]](None)
    val m    = Reconciler.mount(r, ProgressBar(work))

    chk.eq(r.kind(m.handle), WidgetKind.ProgressBar)
    // None is "the work is happening and we cannot say how far", which is a different
    // thing from "nothing has happened yet" and must not render the same.
    chk.eq(r.nodes(m.handle).props("progress"), "indeterminate")

    work.set(Some(0.0))
    chk.eq(r.nodes(m.handle).nums("progress"), 0.0)

    work.set(Some(0.42))
    chk.eq(r.nodes(m.handle).nums("progress"), 0.42)
    o.dispose()
    chk.result
  },

  test("a Spinner stops by being unmounted, not by a prop") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r       = TestRenderer()
    val loading = Var(true)
    val m       = Reconciler.mount(r, Column()(Show(loading)(Spinner())))

    chk.eq(r.childrenOf(m.handle).map(r.kind), Seq(WidgetKind.ActivityIndicator))
    loading.set(false)
    chk.eq(r.childrenOf(m.handle), Seq.empty)
    loading.set(true)
    chk.eq(r.childrenOf(m.handle).map(r.kind), Seq(WidgetKind.ActivityIndicator))
    o.dispose()
    chk.result
  },

  test("Slider speaks the app's units, not a fraction") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r      = TestRenderer()
    val volume = Var(7.0)
    val m      = Reconciler.mount(r, Slider(volume, min = 0, max = 11)(volume.set))

    chk.eq(r.kind(m.handle), WidgetKind.Slider)
    chk.eq(r.nodes(m.handle).nums("rangeMin"), 0.0)
    chk.eq(r.nodes(m.handle).nums("rangeMax"), 11.0)
    // 7, not 0.636. A renderer whose control is integral underneath converts; the app
    // never sees that.
    chk.eq(r.nodes(m.handle).nums("value"), 7.0)

    volume.set(11.0)
    chk.eq(r.nodes(m.handle).nums("value"), 11.0)

    // And back: the user drags it.
    r.nodes(m.handle).onValueChange.foreach(_(3.5))
    chk.eq(volume.now, 3.5)
    o.dispose()
    chk.result
  },

  test("a Slider's range reaches the renderer before its value") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    // A value outside the bounds is meaningless and every toolkit clamps silently, so the
    // order is part of the contract rather than an accident of how the DSL is written.
    Reconciler.mount(r, Slider(5.0, min = 0, max = 10)(_ => ()))
    val order = r.appliedProps.map(_.getClass.getSimpleName)
    chk.yes(
      order.indexOf("Range") < order.indexOf("Value"),
      s"Range must precede Value, got $order"
    )
    o.dispose()
    chk.result
  },

  test("SecureField is a TextField that does not show its value") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r    = TestRenderer()
    val pass = Var("")
    val m    = Reconciler.mount(r, SecureField(pass, placeholder = "Password")(pass.set))

    chk.eq(r.kind(m.handle), WidgetKind.SecureField)
    chk.eq(r.nodes(m.handle).props("placeholder"), "Password")

    pass.set("hunter2")
    chk.eq(r.nodes(m.handle).props("text"), "hunter2")
    r.nodes(m.handle).onTextChange.foreach(_("swordfish"))
    chk.eq(pass.now, "swordfish")
    o.dispose()
    chk.result
  },

  test("the new widgets reconcile inside a keyed region like any other") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val items = Var(Seq(1, 2, 3))
    val m = Reconciler.mount(
      r,
      Column()(ForEach(items, key = (i: Int) => i) { item =>
        Row()(Label(item.map(_.toString)), Spacer(), Toggle(Var(false))(_ => ()))
      })
    )
    chk.eq(r.childrenOf(m.handle).length, 3)
    items.set(Seq(3, 1))
    val rows = r.childrenOf(m.handle)
    chk.eq(rows.length, 2)
    chk.eq(rows.map(row => r.text(r.childrenOf(row).head)), Seq("3", "1"))
    o.dispose()
    chk.result
  }
  ) @@ TestAspect.sequential
}
