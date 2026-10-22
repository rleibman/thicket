/*
 * Copyright 2026 Roberto Leibman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package example

import thicket.core.AppRoot
import thicket.core.dsl.*
import thicket.renderer.apple.{AppleApp, AppleInspect, Handles, Shim}
import thicket.signals.Var

/** A 10 000-row screen and nothing else, for measuring virtualisation (Forgejo #5).
  *
  * Separate from [[AppleSelfTest]] on purpose: a measurement of virtualisation should have nothing else on screen. It
  * was first separated because `AppleRenderer` could not yet build several of the todo app's widgets (Forgejo #9); that
  * gap is closed, and the probe stays for the cleaner measurement. It uses only `Column`, `Label` and `LazyColumn`.
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

    table match {
      case Some(t) => scrollThrough(t, 0)
      case None    => unmount()
    }
  }

  /** Every row, top to bottom, a screenful at a time, a few screenfuls per main-loop turn so the platform can free what
    * it lets go of between them (#78, review 2026-10-20 P2-8).
    *
    * What has to stay bounded is what the table *holds*: the row views built minus those handed back through
    * `RowSource.discard`. Before #78 UIKit never handed one back, so a cell the table freed left its row — slot, owner,
    * effects — alive until the list was unmounted.
    */
  private val step = 8
  private val stepsPerTurn = 25

  private def scrollThrough(
    t:    Shim.Handle,
    from: Int
  ): Unit = {
    var row = from
    while row < rowCount && row < from + step * stepsPerTurn do {
      Shim.sui_table_scroll_to(t, row)
      row += step
    }
    if row < rowCount then AppleApp.postToUi(() => scrollThrough(t, row))
    else {
      Shim.sui_table_scroll_to(t, rowCount - 1)
      AppleApp.postToUi(() => afterScroll(t))
    }
  }

  private def afterScroll(t: Shim.Handle): Unit = {
    val renderer = AppleApp.renderer
    val built = renderer.materialisedRows(t)
    val discarded = renderer.discardedRows(t)
    println(
      s"[lazytest] scrolled through all $rowCount rows: $built row views built, $discarded discarded, " +
        s"${built - discarded} held"
    )
    check(
      "scrolling every row keeps what the table holds bounded",
      built - discarded > 0 && built - discarded < rowCount / 10,
      s"${built - discarded} held"
    )
    val last = AppleInspect.allTexts(AppleApp.rootHandle).filter(_.startsWith("Row "))
    check(
      "the last row is bound and readable",
      last.contains(s"Row ${rowCount - 1}"),
      last.takeRight(3).toString
    )
    memoryWarning(t)
  }

  /** UIKit frees the cells in a table's reuse queue when memory is short. The row views they hosted are then shown by
    * nothing, so each must come back to the source as a discard, and the source must dispose it: the renderer's
    * per-handle tables shrink by the rows' widgets. AppKit has no such notice, and says so.
    */
  private def memoryWarning(t: Shim.Handle): Unit = {
    val renderer = AppleApp.renderer
    val discardedBefore = renderer.discardedRows(t)
    val entriesBefore = renderer.tableEntries
    if Shim.sui_post_memory_warning() == 0 then {
      println("[lazytest] (this platform posts no memory warning; the discard on a freed cell is not exercised)")
      unmount()
    } else
      // The cells go when the table's purge releases them, and each discard is posted to the
      // turn after that.
      AppleApp.postToUiAfter(200) { () =>
        val discarded = renderer.discardedRows(t) - discardedBefore
        val entries = renderer.tableEntries
        println(
          s"[lazytest] memory warning: $discarded row views discarded; renderer table entries $entriesBefore -> $entries"
        )
        check("a memory warning frees cells and their rows are discarded", discarded > 0, s"$discarded discarded")
        check(
          "and the source disposed them: the renderer forgot their widgets",
          entries <= entriesBefore - discarded,
          s"$entriesBefore -> $entries for $discarded rows"
        )
        Shim.sui_table_scroll_to(t, 0)
        AppleApp.postToUi { () =>
          val texts = AppleInspect.allTexts(AppleApp.rootHandle).filter(_.startsWith("Row "))
          check("and rows are built again on demand", texts.contains("Row 0"), texts.take(3).toString)
          unmount()
        }
      }
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
