package scalaui.core

import scalaui.core.dsl.*
import scalaui.signals.{Owner, Var}

/** Structural reconciliation: mounting and unmounting subtrees, and keyed list diffing.
  *
  * The assertions about *handle identity* are the important ones. Anyone can make a list
  * show the right text by rebuilding it; the point of keying is that surviving items keep
  * their widgets, because that is what preserves focus, scroll position and animations.
  */
class StructuralSuite extends munit.FunSuite {

  /** Text of a container's children, in render order. */
  private def texts(r: TestRenderer, root: Int): Seq[String] =
    r.childrenOf(root).map(r.text)

  // -- Show -----------------------------------------------------------------

  test("Show mounts and unmounts its body") {
    val o = Owner(); given Owner = o
    val r       = TestRenderer()
    val visible = Var(false)
    val m       = Reconciler.mount(r, Column()(Label("always"), Show(visible)(Label("maybe"))))

    assertEquals(texts(r, m.handle), Seq("always"))
    visible.set(true)
    assertEquals(texts(r, m.handle), Seq("always", "maybe"))
    visible.set(false)
    assertEquals(texts(r, m.handle), Seq("always"))
    o.dispose()
  }

  test("hiding a Show destroys its widgets and stops its effects") {
    val o = Owner(); given Owner = o
    val r       = TestRenderer()
    val visible = Var(true)
    val count   = Var(0)
    val m = Reconciler.mount(r, Column()(Show(visible)(Label(count.map(n => s"n=$n")))))

    val inner = r.childrenOf(m.handle).head
    assertEquals(r.text(inner), "n=0")
    count.set(1)
    assertEquals(r.text(inner), "n=1")

    visible.set(false)
    assert(r.destroyed.contains(inner), "the hidden widget was destroyed")
    val destroyedSoFar = r.destroyed.length
    count.set(2) // the effect that fed the hidden label must be gone
    assertEquals(r.destroyed.length, destroyedSoFar, "no further renderer work after unmount")
    o.dispose()
  }

  test("Show re-mounts fresh content, and re-renders its body each time") {
    val o = Owner(); given Owner = o
    val r       = TestRenderer()
    val visible = Var(true)
    var builds  = 0
    val m = Reconciler.mount(r, Column()(Show(visible) { builds += 1; Label("hi") }))

    assertEquals(builds, 1)
    visible.set(false)
    visible.set(true)
    assertEquals(builds, 2, "the body is re-evaluated on remount")
    assertEquals(texts(r, m.handle), Seq("hi"))
    o.dispose()
  }

  test("two adjacent Shows keep their order regardless of which is visible") {
    // An empty region must be transparent when the next sibling computes its anchor.
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    val a = Var(false)
    val b = Var(false)
    val m = Reconciler.mount(
      r,
      Column()(Label("first"), Show(a)(Label("A")), Show(b)(Label("B")), Label("last"))
    )

    assertEquals(texts(r, m.handle), Seq("first", "last"))
    b.set(true)
    assertEquals(texts(r, m.handle), Seq("first", "B", "last"))
    a.set(true)
    assertEquals(texts(r, m.handle), Seq("first", "A", "B", "last"), "A landed before B")
    b.set(false)
    assertEquals(texts(r, m.handle), Seq("first", "A", "last"))
    o.dispose()
  }

  // -- ForEach --------------------------------------------------------------

  test("ForEach renders one child per item, in order") {
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val items = Var(Seq("a", "b", "c"))
    val m     = Reconciler.mount(r, Column()(ForEach(items, key = identity)(Label(_))))
    assertEquals(texts(r, m.handle), Seq("a", "b", "c"))
    o.dispose()
  }

  test("appending and removing items touches only those items") {
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val items = Var(Seq("a", "b"))
    val m     = Reconciler.mount(r, Column()(ForEach(items, key = identity)(Label(_))))

    val before = r.childrenOf(m.handle)
    items.set(Seq("a", "b", "c"))
    val after = r.childrenOf(m.handle)
    assertEquals(after.take(2), before, "existing widgets were reused, not rebuilt")
    assertEquals(texts(r, m.handle), Seq("a", "b", "c"))

    items.set(Seq("a", "c"))
    assertEquals(texts(r, m.handle), Seq("a", "c"))
    assertEquals(r.childrenOf(m.handle).head, before.head, "'a' is still the same widget")
    o.dispose()
  }

  test("reordering moves existing widgets instead of rebuilding them") {
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val items = Var(Seq("a", "b", "c"))
    val m     = Reconciler.mount(r, Column()(ForEach(items, key = identity)(Label(_))))

    val handleOf = r.childrenOf(m.handle).map(h => r.text(h) -> h).toMap
    val createdBefore = r.createCount

    items.set(Seq("c", "a", "b"))

    assertEquals(texts(r, m.handle), Seq("c", "a", "b"))
    assertEquals(r.createCount, createdBefore, "no widget was created")
    assert(r.destroyed.isEmpty, "no widget was destroyed")
    assertEquals(
      r.childrenOf(m.handle).map(r.text).map(handleOf),
      r.childrenOf(m.handle),
      "each label is still the widget it was"
    )
    o.dispose()
  }

  test("a stable list does no renderer work at all") {
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val items = Var(Seq("a", "b", "c"))
    val _     = Reconciler.mount(r, Column()(ForEach(items, key = identity)(Label(_))))

    val ops = r.opCount
    items.set(Seq("a", "b", "c")) // equal value: the signal should not even propagate
    assertEquals(r.opCount, ops)
    o.dispose()
  }

  test("items are keyed, not positional") {
    // Same texts, different identities: keying by index would reuse; keying by value
    // must rebuild. This is the test that catches a positional 'optimisation'.
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val items = Var(Seq(1, 2, 3))
    val m     = Reconciler.mount(r, Column()(ForEach(items, key = (i: Int) => i)(i => Label(i.map(n => s"#$n")))))

    val first = r.childrenOf(m.handle).head
    items.set(Seq(9, 2, 3))
    assertEquals(texts(r, m.handle), Seq("#9", "#2", "#3"))
    assert(!r.childrenOf(m.handle).contains(first), "key 1 was removed, not relabelled")
    o.dispose()
  }

  // -- Nesting and interaction ----------------------------------------------

  test("a ForEach nested inside a Show") {
    val o = Owner(); given Owner = o
    val r       = TestRenderer()
    val visible = Var(true)
    val items   = Var(Seq("x", "y"))
    val m = Reconciler.mount(
      r,
      Column()(Show(visible)(Column()(ForEach(items, key = identity)(Label(_)))))
    )

    val inner = r.childrenOf(m.handle).head
    assertEquals(texts(r, inner), Seq("x", "y"))
    items.set(Seq("x", "y", "z"))
    assertEquals(texts(r, inner), Seq("x", "y", "z"))

    visible.set(false)
    assertEquals(r.childrenOf(m.handle), Seq.empty)
    items.set(Seq("q")) // must not resurrect anything
    assertEquals(r.childrenOf(m.handle), Seq.empty)

    visible.set(true)
    val inner2 = r.childrenOf(m.handle).head
    assertEquals(texts(r, inner2), Seq("q"), "remount reflects the current items")
    o.dispose()
  }

  test("a region sits correctly between static siblings") {
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val items = Var(Seq.empty[String])
    val m = Reconciler.mount(
      r,
      Column()(Label("head"), ForEach(items, key = identity)(Label(_)), Label("tail"))
    )

    assertEquals(texts(r, m.handle), Seq("head", "tail"))
    items.set(Seq("1", "2"))
    assertEquals(texts(r, m.handle), Seq("head", "1", "2", "tail"))
    items.set(Seq("2"))
    assertEquals(texts(r, m.handle), Seq("head", "2", "tail"))
    o.dispose()
  }

  test("a Fragment contributes several children to one slot") {
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    val m = Reconciler.mount(
      r,
      Column()(Label("a"), Fragment(Label("b"), Label("c")), Label("d"))
    )
    assertEquals(texts(r, m.handle), Seq("a", "b", "c", "d"))
    o.dispose()
  }

  test("removing a list item disposes the effects its row created") {
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val items = Var(Seq("a", "b"))
    val tick  = Var(0)
    val m = Reconciler.mount(
      r,
      Column()(ForEach(items, key = identity)(s => Label(tick.map(n => s"${s.now}$n"))))
    )

    assertEquals(texts(r, m.handle), Seq("a0", "b0"))
    tick.set(1)
    assertEquals(texts(r, m.handle), Seq("a1", "b1"))

    items.set(Seq("a"))
    val opsAfterRemoval = r.opCount
    tick.set(2)
    assertEquals(texts(r, m.handle), Seq("a2"))
    // Exactly one update, for the surviving row: the removed row's effect is gone.
    assertEquals(r.opCount - opsAfterRemoval, 1)
    o.dispose()
  }

  test("an item that keeps its key but changes its data updates in place") {
    // The failure this guards against: keyed reuse showing stale content. A row bound to
    // a plain value would be a snapshot taken at mount time.
    final case class Todo(id: Int, title: String, done: Boolean)
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    val todos = Var(Seq(Todo(1, "buy milk", false), Todo(2, "call mum", false)))
    val m = Reconciler.mount(
      r,
      Column()(ForEach(todos, key = (t: Todo) => t.id) { t =>
        Label(t.map(x => s"${if x.done then "x" else "o"} ${x.title}"))
      })
    )

    assertEquals(texts(r, m.handle), Seq("o buy milk", "o call mum"))
    val handlesBefore = r.childrenOf(m.handle)
    val createdBefore = r.createCount

    // Same ids, one item edited and one completed.
    todos.set(Seq(Todo(1, "buy oat milk", false), Todo(2, "call mum", true)))

    assertEquals(texts(r, m.handle), Seq("o buy oat milk", "x call mum"))
    assertEquals(r.childrenOf(m.handle), handlesBefore, "rows were patched, not rebuilt")
    assertEquals(r.createCount, createdBefore, "no widget was created")
    assert(r.destroyed.isEmpty, "no widget was destroyed")
    o.dispose()
  }

  test("a changed item patches only its own row") {
    final case class Todo(id: Int, title: String)
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    val todos = Var(Seq(Todo(1, "a"), Todo(2, "b"), Todo(3, "c")))
    val _ = Reconciler.mount(
      r,
      Column()(ForEach(todos, key = (t: Todo) => t.id)(t => Label(t.map(_.title))))
    )

    val ops = r.opCount
    todos.set(Seq(Todo(1, "a"), Todo(2, "B"), Todo(3, "c")))
    assertEquals(r.opCount - ops, 1, "exactly one widget update for one changed item")
    o.dispose()
  }
}
