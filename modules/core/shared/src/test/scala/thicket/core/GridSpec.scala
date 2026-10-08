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

import thicket.core.dsl.*
import thicket.renderer.WidgetKind
import thicket.signals.{Checks, Owner, Var}
import zio.test.*

/** `Grid` — children flowing into columns.
  *
  * A cell's position is derived from its index, so the contract's whole promise is that the **order** of the children
  * is right: the renderer turns index `i` into row `i / columns`, column `i % columns`. These tests pin the order
  * through the cases that change it, because a wrong order in a grid is a label beside the wrong value.
  */
object GridSpec extends ZIOSpecDefault {

  def spec =
    suite("Grid")(
      test("its own kind, with its column count, spacing and children in order") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val m = Reconciler.mount(r, Grid(columns = 2, spacing = 8)(Label("a"), Label("1"), Label("b"), Label("2")))

        chk.eq(r.kind(m.handle), WidgetKind.Grid)
        chk.eq(r.nodes(m.handle).nums("columns"), 2.0)
        chk.eq(r.nodes(m.handle).props("spacing"), "8")
        chk.eq(r.childrenOf(m.handle).map(r.text), Seq("a", "1", "b", "2"))
        o.dispose()
        chk.result
      },
      test("a pair of cells shown later lands where it is written, not at the end") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        // A whole row appearing in the middle of a form: every cell after it must move along,
        // which only works if the insert is positional.
        val extra = Var(false)
        val m = Reconciler.mount(
          r,
          Grid(columns = 2)(
            Label("Name"),
            Label("Ann"),
            Show(extra)(Fragment(Label("Phone"), Label("555"))),
            Label("Email"),
            Label("ann@x")
          )
        )

        chk.eq(r.childrenOf(m.handle).map(r.text), Seq("Name", "Ann", "Email", "ann@x"))
        extra.set(true)
        chk.eq(r.childrenOf(m.handle).map(r.text), Seq("Name", "Ann", "Phone", "555", "Email", "ann@x"))
        extra.set(false)
        chk.eq(r.childrenOf(m.handle).map(r.text), Seq("Name", "Ann", "Email", "ann@x"))
        o.dispose()
        chk.result
      },
      test("a keyed reorder moves the cells, so the grid re-flows") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val cells = Var(Seq("a", "b", "c", "d"))
        val m = Reconciler.mount(r, Grid(columns = 2)(ForEach(cells, identity)(s => Label(s))))

        cells.set(Seq("d", "c", "b", "a"))
        chk.eq(r.childrenOf(m.handle).map(r.text), Seq("d", "c", "b", "a"))
        o.dispose()
        chk.result
      },
      test("zero columns is refused where it is written, not by a renderer later") {
        val chk = Checks()
        chk.yes(scala.util.Try(Grid(columns = 0)(Label("x"))).isFailure)
        chk.result
      }
    ) @@ TestAspect.sequential

}
