package example

import thicket.core.AppRoot
import thicket.core.dsl.*
import thicket.renderer.apple.{AppleApp, AppleInspect, Handles, Shim}
import thicket.signals.Var

/** A 10 000-row screen and nothing else, for measuring virtualisation (Forgejo #5).
  *
  * Separate from [[AppleSelfTest]] on purpose. The shared `TodoApp` uses `Toggle`, `Spacer`, `ProgressBar`, `Spinner`,
  * `Slider` and `SecureField`, all of which `AppleRenderer` throws on until Forgejo #9, so the demo cannot start on
  * Apple at all and the measurement this issue asks for would be unreachable behind an unrelated gap. This uses only
  * `Column`, `Label` and `LazyColumn`, which the Apple shim has had since phase 0.
  *
  * Run with `THICKET_LAZYTEST=1`.
  */
object LazyProbe {

  private val rowCount = 10000

  val items: Var[Seq[Int]] = Var((0 until rowCount).toSeq)

  /** Unmounting the list is the second half of the probe: a table that is destroyed must take its Swift source and its
    * Scala row closure with it.
    */
  val mounted: Var[Boolean] = Var(true)

  private var failures = 0

  private def check(
    name:   String,
    cond:   Boolean,
    detail: => String = ""
  ): Unit = {
    if !cond then failures += 1
    println(s"[lazytest] ${if cond then "ok  " else "FAIL"} $name")
    if !cond && detail.nonEmpty then println(s"               $detail")
  }

  def build(): AppRoot = {
    AppleApp.postToUi(() => measure())
    AppRoot(
      "10 000 rows",
      Column(spacing = 0)(
        Show(mounted)(LazyColumn(items, key = (i: Int) => i)(row => Label(row.map(i => s"Row $i"))))
      )
    )
  }

  /** The number that decides the issue: how many row views the table actually built.
    *
    * Counted on the Swift side, because the Scala side cannot see what the table chose to recycle. Android reports ~66
    * and GTK ~205; any number far below 10 000 means the platform is recycling, and 10 000 means it is not.
    */
  private def measure(): Unit = {
    val renderer = AppleApp.renderer
    val root = AppleApp.rootHandle

    val table = findTable(root)
    check("the LazyColumn produced a table", table.isDefined)

    table.foreach { t =>
      val built = renderer.materialisedRows(t)
      println(s"[lazytest] $rowCount rows in the model, $built row views materialised")
      check(
        "the table did not materialise every row",
        built > 0 && built < rowCount / 10,
        s"$built of $rowCount — virtualisation is not recycling"
      )
      check("the table materialised something", built > 0, s"$built rows built")
    }

    // Reading text back proves the rows are real widgets rather than an empty table that
    // trivially materialises nothing.
    val texts = AppleInspect.allTexts(root).filter(_.startsWith("Row "))
    check("the materialised rows carry their text", texts.nonEmpty, s"${texts.size} row labels")
    println(s"[lazytest] ${texts.size} row labels readable in the tree")

    unmount()
  }

  /** Counted on both sides, because each side owns something the other cannot see: the shim owns the table's source
    * (the table's own references to it are weak), and the renderer owns the row closure, which captures the whole
    * `RowSource` graph.
    */
  private def unmount(): Unit = {
    val liveBefore = Shim.sui_table_live()
    val rowsBefore = Handles.rowCount
    mounted.set(false)
    AppleApp.postToUi { () =>
      val liveAfter = Shim.sui_table_live()
      val rowsAfter = Handles.rowCount
      println(s"[lazytest] unmounted: table sources $liveBefore -> $liveAfter, row callbacks $rowsBefore -> $rowsAfter")
      check("unmounting frees the table's source", liveBefore == 1 && liveAfter == 0, s"$liveBefore -> $liveAfter")
      check("unmounting releases the row callback", rowsAfter == rowsBefore - 1, s"$rowsBefore -> $rowsAfter")
      println(
        if failures == 0 then "[lazytest] ALL CHECKS PASSED"
        else s"[lazytest] $failures CHECK(S) FAILED"
      )
    }
  }

  /** Depth-first; the table is the only handle the renderer made through `createVirtualList`. */
  private def findTable(h: thicket.renderer.apple.Shim.Handle): Option[thicket.renderer.apple.Shim.Handle] =
    if AppleApp.renderer.materialisedRows(h) > 0 then Some(h)
    else AppleInspect.children(h).flatMap(findTable).headOption

}
