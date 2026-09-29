package scalaui.core

import zio.test.*
import scalaui.signals.Checks

import scalaui.core.dsl.*
import scalaui.renderer.WidgetKind
import scalaui.signals.{Owner, Var}

object ReconcilerSpec extends ZIOSpecDefault {

  def spec = suite("Reconciler")(

  test("mounts the tree in declaration order") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    val m = Reconciler.mount(r, Column(spacing = 4)(Label("a"), Label("b"), Label("c")))

    chk.eq(r.kind(m.handle), WidgetKind.Column)
    val kids = r.childrenOf(m.handle)
    chk.eq(kids.length, 3)
    chk.eq(kids.map(r.text), Seq("a", "b", "c"), "insertAfter preserved order")
    chk.eq(r.nodes(m.handle).props("spacing"), "4")
    o.dispose()
    chk.result
  },

  test("a reactive label follows its signal") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val count = Var(0)
    val m     = Reconciler.mount(r, Column()(Label(count.map(n => s"Count: $n"))))
    val label = r.childrenOf(m.handle).head

    chk.eq(r.text(label), "Count: 0")
    count.set(7)
    chk.eq(r.text(label), "Count: 7", "signal update reached the widget")
    o.dispose()
    chk.result
  },

  test("tapping a button runs the handler and updates the bound label") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val count = Var(0)
    val m = Reconciler.mount(
      r,
      Column()(
        Label(count.map(n => s"Count: $n")),
        Row()(Button("+")(count.update(_ + 1)), Button("Reset")(count.set(0)))
      )
    )
    val label = r.childrenOf(m.handle).head
    val row   = r.childrenOf(m.handle)(1)
    val plus  = r.childrenOf(row).head
    val reset = r.childrenOf(row)(1)

    r.tap(plus); r.tap(plus); r.tap(plus)
    chk.eq(r.text(label), "Count: 3")
    r.tap(reset)
    chk.eq(r.text(label), "Count: 0")
    o.dispose()
    chk.result
  },

  test("disposing the owner stops updates; destroy tears the tree down") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val count = Var(0)
    val m     = Reconciler.mount(r, Column()(Label(count.map(_.toString))))
    val label = r.childrenOf(m.handle).head

    count.set(1)
    chk.eq(r.text(label), "1")
    o.dispose()
    count.set(2)
    chk.eq(r.text(label), "1", "a disposed owner's effects must not fire")

    m.dispose()
    chk.eq(r.destroyed.toSet, Set(m.handle, label))
    chk.result
  },

  test("static props are applied at create time, not via an effect") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    val m = Reconciler.mount(r, Column(padding = 12)(Button("go", enabled = false)(())))
    val b = r.childrenOf(m.handle).head
    chk.eq(r.nodes(m.handle).props("padding"), "12")
    chk.eq(r.nodes(b).props("enabled"), "false")
    chk.eq(r.text(b), "go")
    o.dispose()
    chk.result
  }
  ) @@ TestAspect.sequential
}
