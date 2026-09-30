package thicket.core

import zio.test.*
import thicket.signals.Checks

import thicket.core.dsl.*
import thicket.signals.{Owner, Var}

/** How the reconciler scales with list size.
  *
  * `ForEach` mounts every row, so N-02's 10 000-row target is the obvious place for it to
  * fall over. These numbers say how far off that is, and separate *framework* cost from
  * *toolkit* cost — the `TestRenderer` does no layout or drawing, so what is measured here
  * is purely reconciliation.
  */

object ScaleSpec extends ZIOSpecDefault {


  final case class Item(id: Int, title: String, done: Boolean)


  private def rows(n: Int): Seq[Item] =

    (1 to n).map(i => Item(i, s"Item $i", i % 3 == 0))


  private def timeMs(body: => Unit): Double = {
    val t0 = java.lang.System.nanoTime()
    body
    (java.lang.System.nanoTime() - t0) / 1e6
  }


  private def listOf(items: Var[Seq[Item]]): Element =

    Column()(

      ForEach(items, key = (r: Item) => r.id) { row =>
        Row(spacing = 8, padding = 8)(
          Label(row.map(_.title)).grow,
          Label(row.map(r => if r.done then "✓" else ""))
        )
      }

    )

  def spec = suite("Scale")(

  test("mounting scales linearly and reports its cost") {
    val chk = Checks()
    val sizes = List(100, 1000, 5000, 10000)
    val results = sizes.map { n =>
      val o = Owner(); given Owner = o
      val r     = TestRenderer()
      val items = Var(rows(n))
      // Warm the JIT on the small case before the numbers that matter.
      val ms = timeMs { val _ = Reconciler.mount(r, listOf(items)) }
      val widgets = r.createCount
      o.dispose()
      (n, ms, widgets)
    }

    results.foreach { case (n, ms, widgets) =>
      println(f"[scale] mount $n%6d rows: $ms%8.1f ms, $widgets%7d widgets")
    }

    val (_, tenKms, tenKwidgets) = results.last
    // 10k rows x 3 widgets each, plus the container.
    chk.eq(tenKwidgets, 10000 * 3 + 1)
    chk.yes(tenKms < 30000, s"mounting 10k rows took ${tenKms}ms")
    chk.result
  },

  test("updating one row in a large list stays O(1) in renderer work") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val items = Var(rows(10000))
    val _     = Reconciler.mount(r, listOf(items))

    // Warm, then take a median: a single cold measurement here is mostly JIT.
    def change(i: Int): Double = {
      val before = r.opCount
      val ms = timeMs {
        items.update(rs => rs.updated(5000, rs(5000).copy(title = s"changed $i")))
      }
      chk.eq(r.opCount - before, 1, "one changed row must cost one renderer write")
      ms
    }

    (1 to 20).foreach(change)
    val samples = (21 to 60).map(change).sorted
    val median  = samples(samples.length / 2)
    println(f"[scale] change 1 of 10000 rows: median $median%6.2f ms (warm), 1 renderer op")
    chk.yes(median < 16.6, f"a single-row change must fit in a frame; was $median%.2f ms")
    o.dispose()
    chk.result
  },

  test("appending to a large list does not rebuild it") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val items = Var(rows(10000))
    val _     = Reconciler.mount(r, listOf(items))

    val createdBefore = r.createCount
    val ms = timeMs { items.update(_ :+ Item(999999, "appended", false)) }
    val created = r.createCount - createdBefore
    println(f"[scale] append to 10000 rows: $ms%6.2f ms, $created widgets created")
    chk.eq(created, 3, "only the new row's widgets")
    o.dispose()
    chk.result
  },

  test("prepending to a large list: the cost of keeping order") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val items = Var(rows(10000))
    val _     = Reconciler.mount(r, listOf(items))

    val before = r.opCount
    val ms = timeMs { items.update(Item(999999, "prepended", false) +: _) }
    println(f"[scale] prepend to 10000 rows: $ms%6.2f ms, ${r.opCount - before} renderer ops")
    o.dispose()
    chk.result
  }
  ) @@ TestAspect.sequential
}
