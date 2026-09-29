package scalaui.core

import zio.test.*
import scalaui.signals.Checks

import scalaui.core.dsl.*
import scalaui.signals.{Owner, Var}

/** Structural reconciliation: mounting and unmounting subtrees, and keyed list diffing.
  *
  * The assertions about *handle identity* are the important ones. Anyone can make a list
  * show the right text by rebuilding it; the point of keying is that surviving items keep
  * their widgets, because that is what preserves focus, scroll position and animations.
  */

object StructuralSpec extends ZIOSpecDefault {


  /** Text of a container's children, in render order. */
  private def texts(r: TestRenderer, root: Int): Seq[String] =

    r.childrenOf(root).map(r.text)

  def spec = suite("Structural")(

  // -- Show -----------------------------------------------------------------

  test("Show mounts and unmounts its body") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r       = TestRenderer()
    val visible = Var(false)
    val m       = Reconciler.mount(r, Column()(Label("always"), Show(visible)(Label("maybe"))))

    chk.eq(texts(r, m.handle), Seq("always"))
    visible.set(true)
    chk.eq(texts(r, m.handle), Seq("always", "maybe"))
    visible.set(false)
    chk.eq(texts(r, m.handle), Seq("always"))
    o.dispose()
    chk.result
  },

  test("hiding a Show destroys its widgets and stops its effects") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r       = TestRenderer()
    val visible = Var(true)
    val count   = Var(0)
    val m = Reconciler.mount(r, Column()(Show(visible)(Label(count.map(n => s"n=$n")))))

    val inner = r.childrenOf(m.handle).head
    chk.eq(r.text(inner), "n=0")
    count.set(1)
    chk.eq(r.text(inner), "n=1")

    visible.set(false)
    chk.yes(r.destroyed.contains(inner), "the hidden widget was destroyed")
    val destroyedSoFar = r.destroyed.length
    count.set(2) // the effect that fed the hidden label must be gone
    chk.eq(r.destroyed.length, destroyedSoFar, "no further renderer work after unmount")
    o.dispose()
    chk.result
  },

  test("Show re-mounts fresh content, and re-renders its body each time") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r       = TestRenderer()
    val visible = Var(true)
    var builds  = 0
    val m = Reconciler.mount(r, Column()(Show(visible) { builds += 1; Label("hi") }))

    chk.eq(builds, 1)
    visible.set(false)
    visible.set(true)
    chk.eq(builds, 2, "the body is re-evaluated on remount")
    chk.eq(texts(r, m.handle), Seq("hi"))
    o.dispose()
    chk.result
  },

  test("two adjacent Shows keep their order regardless of which is visible") {
    val chk = Checks()
    // An empty region must be transparent when the next sibling computes its anchor.
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    val a = Var(false)
    val b = Var(false)
    val m = Reconciler.mount(
      r,
      Column()(Label("first"), Show(a)(Label("A")), Show(b)(Label("B")), Label("last"))
    )

    chk.eq(texts(r, m.handle), Seq("first", "last"))
    b.set(true)
    chk.eq(texts(r, m.handle), Seq("first", "B", "last"))
    a.set(true)
    chk.eq(texts(r, m.handle), Seq("first", "A", "B", "last"), "A landed before B")
    b.set(false)
    chk.eq(texts(r, m.handle), Seq("first", "A", "last"))
    o.dispose()
    chk.result
  },

  // -- ForEach --------------------------------------------------------------

  test("ForEach renders one child per item, in order") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val items = Var(Seq("a", "b", "c"))
    val m     = Reconciler.mount(r, Column()(ForEach(items, key = identity)(Label(_))))
    chk.eq(texts(r, m.handle), Seq("a", "b", "c"))
    o.dispose()
    chk.result
  },

  test("appending and removing items touches only those items") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val items = Var(Seq("a", "b"))
    val m     = Reconciler.mount(r, Column()(ForEach(items, key = identity)(Label(_))))

    val before = r.childrenOf(m.handle)
    items.set(Seq("a", "b", "c"))
    val after = r.childrenOf(m.handle)
    chk.eq(after.take(2), before, "existing widgets were reused, not rebuilt")
    chk.eq(texts(r, m.handle), Seq("a", "b", "c"))

    items.set(Seq("a", "c"))
    chk.eq(texts(r, m.handle), Seq("a", "c"))
    chk.eq(r.childrenOf(m.handle).head, before.head, "'a' is still the same widget")
    o.dispose()
    chk.result
  },

  test("reordering moves existing widgets instead of rebuilding them") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val items = Var(Seq("a", "b", "c"))
    val m     = Reconciler.mount(r, Column()(ForEach(items, key = identity)(Label(_))))

    val handleOf = r.childrenOf(m.handle).map(h => r.text(h) -> h).toMap
    val createdBefore = r.createCount

    items.set(Seq("c", "a", "b"))

    chk.eq(texts(r, m.handle), Seq("c", "a", "b"))
    chk.eq(r.createCount, createdBefore, "no widget was created")
    chk.yes(r.destroyed.isEmpty, "no widget was destroyed")
    chk.eq(
      r.childrenOf(m.handle).map(r.text).map(handleOf),
      r.childrenOf(m.handle),
      "each label is still the widget it was"
    )
    o.dispose()
    chk.result
  },

  test("a stable list does no renderer work at all") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val items = Var(Seq("a", "b", "c"))
    val _     = Reconciler.mount(r, Column()(ForEach(items, key = identity)(Label(_))))

    val ops = r.opCount
    items.set(Seq("a", "b", "c")) // equal value: the signal should not even propagate
    chk.eq(r.opCount, ops)
    o.dispose()
    chk.result
  },

  test("items are keyed, not positional") {
    val chk = Checks()
    // Same texts, different identities: keying by index would reuse; keying by value
    // must rebuild. This is the test that catches a positional 'optimisation'.
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val items = Var(Seq(1, 2, 3))
    val m     = Reconciler.mount(r, Column()(ForEach(items, key = (i: Int) => i)(i => Label(i.map(n => s"#$n")))))

    val first = r.childrenOf(m.handle).head
    items.set(Seq(9, 2, 3))
    chk.eq(texts(r, m.handle), Seq("#9", "#2", "#3"))
    chk.yes(!r.childrenOf(m.handle).contains(first), "key 1 was removed, not relabelled")
    o.dispose()
    chk.result
  },

  // -- Nesting and interaction ----------------------------------------------

  test("a ForEach nested inside a Show") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r       = TestRenderer()
    val visible = Var(true)
    val items   = Var(Seq("x", "y"))
    val m = Reconciler.mount(
      r,
      Column()(Show(visible)(Column()(ForEach(items, key = identity)(Label(_)))))
    )

    val inner = r.childrenOf(m.handle).head
    chk.eq(texts(r, inner), Seq("x", "y"))
    items.set(Seq("x", "y", "z"))
    chk.eq(texts(r, inner), Seq("x", "y", "z"))

    visible.set(false)
    chk.eq(r.childrenOf(m.handle), Seq.empty)
    items.set(Seq("q")) // must not resurrect anything
    chk.eq(r.childrenOf(m.handle), Seq.empty)

    visible.set(true)
    val inner2 = r.childrenOf(m.handle).head
    chk.eq(texts(r, inner2), Seq("q"), "remount reflects the current items")
    o.dispose()
    chk.result
  },

  test("a region sits correctly between static siblings") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val items = Var(Seq.empty[String])
    val m = Reconciler.mount(
      r,
      Column()(Label("head"), ForEach(items, key = identity)(Label(_)), Label("tail"))
    )

    chk.eq(texts(r, m.handle), Seq("head", "tail"))
    items.set(Seq("1", "2"))
    chk.eq(texts(r, m.handle), Seq("head", "1", "2", "tail"))
    items.set(Seq("2"))
    chk.eq(texts(r, m.handle), Seq("head", "2", "tail"))
    o.dispose()
    chk.result
  },

  test("a Fragment contributes several children to one slot") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    val m = Reconciler.mount(
      r,
      Column()(Label("a"), Fragment(Label("b"), Label("c")), Label("d"))
    )
    chk.eq(texts(r, m.handle), Seq("a", "b", "c", "d"))
    o.dispose()
    chk.result
  },

  test("removing a list item disposes the effects its row created") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val items = Var(Seq("a", "b"))
    val tick  = Var(0)
    val m = Reconciler.mount(
      r,
      Column()(ForEach(items, key = identity)(s => Label(tick.map(n => s"${s.now}$n"))))
    )

    chk.eq(texts(r, m.handle), Seq("a0", "b0"))
    tick.set(1)
    chk.eq(texts(r, m.handle), Seq("a1", "b1"))

    items.set(Seq("a"))
    val opsAfterRemoval = r.opCount
    tick.set(2)
    chk.eq(texts(r, m.handle), Seq("a2"))
    // Exactly one update, for the surviving row: the removed row's effect is gone.
    chk.eq(r.opCount - opsAfterRemoval, 1)
    o.dispose()
    chk.result
  },

  test("an item that keeps its key but changes its data updates in place") {
    val chk = Checks()
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

    chk.eq(texts(r, m.handle), Seq("o buy milk", "o call mum"))
    val handlesBefore = r.childrenOf(m.handle)
    val createdBefore = r.createCount

    // Same ids, one item edited and one completed.
    todos.set(Seq(Todo(1, "buy oat milk", false), Todo(2, "call mum", true)))

    chk.eq(texts(r, m.handle), Seq("o buy oat milk", "x call mum"))
    chk.eq(r.childrenOf(m.handle), handlesBefore, "rows were patched, not rebuilt")
    chk.eq(r.createCount, createdBefore, "no widget was created")
    chk.yes(r.destroyed.isEmpty, "no widget was destroyed")
    o.dispose()
    chk.result
  },

  test("a changed item patches only its own row") {
    val chk = Checks()
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
    chk.eq(r.opCount - ops, 1, "exactly one widget update for one changed item")
    o.dispose()
    chk.result
  }
  ) @@ TestAspect.sequential
}
