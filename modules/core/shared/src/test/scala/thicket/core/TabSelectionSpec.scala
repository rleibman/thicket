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

package thicket.core

import zio.test.*
import thicket.renderer.TabSelection
import thicket.signals.Checks

/** The tab-selection rules both renderers follow, without a display (review 2026-10-20, P3-4). */
object TabSelectionSpec extends ZIOSpecDefault {

  def spec =
    suite("TabSelection")(
      test("an insert before the visible tab keeps the user on it and reports the new index") {
        val chk = Checks()
        chk.eq(TabSelection.afterChange(Seq("a", "x", "b", "c"), Some("c"), 2), (3, true))
        chk.result
      },
      test("an insert after it changes nothing to report") {
        val chk = Checks()
        chk.eq(TabSelection.afterChange(Seq("a", "b", "x"), Some("b"), 1), (1, false))
        chk.result
      },
      test("a reorder moves the index with the tab") {
        val chk = Checks()
        chk.eq(TabSelection.afterChange(Seq("c", "a", "b"), Some("a"), 0), (1, true))
        chk.result
      },
      test("removing the visible tab shows the nearest, and reports it") {
        val chk = Checks()
        chk.eq(TabSelection.afterChange(Seq("a", "c"), None, 1), (1, true))
        chk.eq(TabSelection.afterChange(Seq("a"), None, 2), (0, true), "past the end: the last")
        chk.eq(TabSelection.afterChange(Seq.empty[String], None, 0), (-1, false), "none left")
        chk.result
      },
      test("nothing is reported before the app's selection was shown, or when unchanged") {
        val chk = Checks()
        chk.eq(TabSelection.moved(None, 0), None)
        chk.eq(TabSelection.moved(Some(2), 2), None)
        chk.eq(TabSelection.moved(Some(2), 3), Some(3))
        chk.eq(TabSelection.moved(Some(2), -1), None)
        chk.result
      },
      test("the first tabs: the app's choice, the first for -1, or wait for the wanted one") {
        val chk = Checks()
        chk.eq(TabSelection.initial(1, 3), Some(1))
        chk.eq(TabSelection.initial(-1, 1), Some(0))
        chk.eq(TabSelection.initial(2, 1), None)
        chk.eq(TabSelection.initial(-1, 0), None)
        chk.result
      }
    )

}
