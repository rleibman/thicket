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

/** `SegmentedControl` — the same choice as `Picker`, drawn with every option visible.
  *
  * The claim worth pinning is "the same": the same three props in the same order, the same index-not-label selection,
  * the same `-1`. A renderer can then share the selection logic, and an app can switch between the two by changing one
  * word.
  */
object SegmentedControlSpec extends ZIOSpecDefault {

  private val spans = Seq("Day", "Week", "Month")

  def spec =
    suite("SegmentedControl")(
      test("its own kind, with the Picker's props") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val m = Reconciler.mount(r, SegmentedControl(spans, 1)(_ => ()))

        chk.eq(r.kind(m.handle), WidgetKind.SegmentedControl)
        chk.eq(r.nodes(m.handle).options, spans)
        chk.eq(r.nodes(m.handle).nums("selected"), 1.0)
        o.dispose()
        chk.result
      },
      test("options arrive before the selection, as for Picker") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val m = Reconciler.mount(r, SegmentedControl(spans, 2)(_ => ()))
        val order = r.nodes(m.handle).applied.toList

        chk.yes(order.indexOf("Options") >= 0 && order.indexOf("Options") < order.indexOf("Selected"), order.toString)
        o.dispose()
        chk.result
      },
      test("choosing reports the index, and two equal labels stay distinct") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        var chosen = -99
        val m = Reconciler.mount(r, SegmentedControl(Seq("A", "A"), 0)(i => chosen = i))

        r.nodes(m.handle).onSelect.foreach(_(1))
        chk.eq(chosen, 1, "the second A, not the first")
        o.dispose()
        chk.result
      },
      test("a reactive selection follows its signal, including to none") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val sel = Var(0)
        val m = Reconciler.mount(r, SegmentedControl(spans, sel)(sel.set))

        sel.set(2)
        chk.eq(r.nodes(m.handle).nums("selected"), 2.0)
        sel.set(-1)
        chk.eq(r.nodes(m.handle).nums("selected"), -1.0)
        o.dispose()
        chk.result
      }
    ) @@ TestAspect.sequential

}
