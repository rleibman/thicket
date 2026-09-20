package scalaui.core

import scalaui.core.dsl.*
import scalaui.renderer.WidgetKind
import scalaui.signals.{Owner, Var}

class ReconcilerSuite extends munit.FunSuite:

  test("mounts the tree in declaration order"):
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    val m = Reconciler.mount(r, Column(spacing = 4)(Label("a"), Label("b"), Label("c")))

    assertEquals(r.kind(m.handle), WidgetKind.Column)
    val kids = r.childrenOf(m.handle)
    assertEquals(kids.length, 3)
    assertEquals(kids.map(r.text), Seq("a", "b", "c"), "insertAfter preserved order")
    assertEquals(r.nodes(m.handle).props("spacing"), "4")
    o.dispose()

  test("a reactive label follows its signal"):
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val count = Var(0)
    val m     = Reconciler.mount(r, Column()(Label(count.map(n => s"Count: $n"))))
    val label = r.childrenOf(m.handle).head

    assertEquals(r.text(label), "Count: 0")
    count.set(7)
    assertEquals(r.text(label), "Count: 7", "signal update reached the widget")
    o.dispose()

  test("tapping a button runs the handler and updates the bound label"):
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
    assertEquals(r.text(label), "Count: 3")
    r.tap(reset)
    assertEquals(r.text(label), "Count: 0")
    o.dispose()

  test("disposing the owner stops updates; destroy tears the tree down"):
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val count = Var(0)
    val m     = Reconciler.mount(r, Column()(Label(count.map(_.toString))))
    val label = r.childrenOf(m.handle).head

    count.set(1)
    assertEquals(r.text(label), "1")
    o.dispose()
    count.set(2)
    assertEquals(r.text(label), "1", "a disposed owner's effects must not fire")

    m.dispose(r)
    assertEquals(r.destroyed.toSet, Set(m.handle, label))

  test("static props are applied at create time, not via an effect"):
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    val m = Reconciler.mount(r, Column(padding = 12)(Button("go", enabled = false)(())))
    val b = r.childrenOf(m.handle).head
    assertEquals(r.nodes(m.handle).props("padding"), "12")
    assertEquals(r.nodes(b).props("enabled"), "false")
    assertEquals(r.text(b), "go")
    o.dispose()
