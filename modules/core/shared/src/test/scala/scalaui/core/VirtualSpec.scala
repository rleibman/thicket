package scalaui.core

import zio.test.*
import scalaui.signals.Checks

import scalaui.core.dsl.*
import scalaui.signals.{Owner, Var}

/** `LazyColumn` against a recycling container.
  *
  * The claims worth pinning are the ones that distinguish it from `ForEach`: only visible
  * rows exist, scrolling re-binds rather than rebuilds, and the fallback on a renderer
  * without a virtualising container is still correct.
  */

object VirtualSpec extends ZIOSpecDefault {


  final case class Item(id: Int, title: String)


  private def items(n: Int): Seq[Item] = (1 to n).map(i => Item(i, s"Item $i"))


  private def mountList(r: TestRenderer, data: Var[Seq[Item]])(using Owner) =

    Reconciler.mount(

      r,

      LazyColumn(data, key = (i: Item) => i.id)(item => Label(item.map(_.title)))

    )

  def spec = suite("Virtual")(

  test("only the visible rows are materialised") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    r.virtualising = true
    r.windowSize = 5
    val data = Var(items(10000))

    val _ = mountList(r, data)
    r.scrollTo(0)

    chk.eq(r.visibleRows.length, 5, "5 rows on screen, not 10 000")
    chk.eq(r.visibleRows.map(r.text), Seq("Item 1", "Item 2", "Item 3", "Item 4", "Item 5"))
    // One widget per row, plus the container.
    chk.yes(r.createCount <= 6, s"materialised ${r.createCount} widgets for a 10 000-row list")
    o.dispose()
    chk.result
  },

  test("scrolling recycles rows instead of building new ones") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    r.virtualising = true
    r.windowSize = 5
    val data = Var(items(1000))

    val _ = mountList(r, data)
    r.scrollTo(0)
    val createdAfterFirstScreen = r.createCount

    r.scrollTo(5)
    chk.eq(r.visibleRows.map(r.text), Seq("Item 6", "Item 7", "Item 8", "Item 9", "Item 10"))
    chk.eq(
      r.createCount,
      createdAfterFirstScreen,
      "a scroll must re-bind recycled rows, not create widgets"
    )

    r.scrollTo(500)
    chk.eq(r.visibleRows.map(r.text).head, "Item 501")
    chk.eq(r.createCount, createdAfterFirstScreen, "still no new widgets")
    o.dispose()
    chk.result
  },

  test("a live row follows its item's data") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    r.virtualising = true
    r.windowSize = 3
    val data = Var(items(100))

    val _ = mountList(r, data)
    r.scrollTo(0)
    chk.eq(r.visibleRows.map(r.text), Seq("Item 1", "Item 2", "Item 3"))

    data.update(xs => xs.updated(1, xs(1).copy(title = "edited")))
    chk.eq(r.visibleRows.map(r.text), Seq("Item 1", "edited", "Item 3"))
    o.dispose()
    chk.result
  },

  test("the row count follows the data") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    r.virtualising = true
    r.windowSize = 10
    val data = Var(items(3))

    val _ = mountList(r, data)
    r.scrollTo(0)
    chk.eq(r.visibleRows.length, 3)

    data.set(items(7))
    chk.eq(r.visibleRows.length, 7)

    data.set(items(2))
    chk.eq(r.visibleRows.length, 2)
    o.dispose()
    chk.result
  },

  test("disposing the list disposes every row it is holding") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    r.virtualising = true
    r.windowSize = 4
    val data = Var(items(50))

    val m = mountList(r, data)
    r.scrollTo(0)
    val before = r.destroyed.length

    m.dispose()
    chk.yes(r.destroyed.length > before, "rows and the container were destroyed")

    // Disposing the widgets and disposing the effects are separate acts: the list's effect
    // belongs to the owner and may outlive the slot. What must hold in between is that a
    // late data change cannot resurrect rows into a container that no longer exists.
    val createdBefore = r.createCount
    val boundBefore   = r.bindCount
    data.update(xs => xs.updated(0, xs.head.copy(title = "after dispose")))
    chk.eq(r.createCount, createdBefore, "no widgets built for a disposed list")
    chk.eq(r.bindCount, boundBefore, "the framework was not asked to bind a row")

    // Disposing the owner is what stops the effect itself.
    o.dispose()
    val after = r.opCount
    data.update(xs => xs.updated(0, xs.head.copy(title = "later still")))
    chk.eq(r.opCount, after, "a disposed owner's effects must not fire")
    chk.result
  },

  test("falls back to mounting every row when the renderer cannot virtualise") {
    val chk = Checks()
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    r.virtualising = false // the default, and what GTK does today
    val data = Var(items(20))

    val m = mountList(r, data)
    chk.eq(r.childrenOf(m.handle).length, 20, "correct, just heavier")
    chk.eq(r.childrenOf(m.handle).map(r.text).head, "Item 1")

    data.update(xs => xs.updated(0, xs.head.copy(title = "edited")))
    chk.eq(r.childrenOf(m.handle).map(r.text).head, "edited")
    o.dispose()
    chk.result
  }
  ) @@ TestAspect.sequential
}
