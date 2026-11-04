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
import thicket.renderer.{Steps, WidgetKind}
import thicket.signals.{Checks, Owner, Var}
import zio.test.*

/** `RadioGroup` and `Stepper`, the two §12.2a exceptions (`docs/12`, the owner's decision 2026-11-03).
  *
  * Core's promises: each reaches the renderer as its own kind with the props it shares with `Picker` and `Slider`, the
  * bounds before the value, and the user's choice back as an index or a number. What each platform draws is the
  * galleries'.
  */
object RadioStepperSpec extends ZIOSpecDefault {

  private val sizes = Seq("Small", "Medium", "Large")

  def spec =
    suite("RadioGroup and Stepper")(
      test("a RadioGroup sends its options before its selection, and reports an index") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        var chosen = -99
        val m = Reconciler.mount(r, RadioGroup(sizes, 1)(i => chosen = i))
        val n = r.nodes(m.handle)
        chk.eq(r.kind(m.handle), WidgetKind.RadioGroup)
        chk.eq(n.options, sizes)
        chk.eq(n.nums("selected"), 1.0)
        chk.eq(n.applied.toList.take(2), List("Options", "Selected"))
        n.onSelect.foreach(_(2))
        chk.eq(chosen, 2)
        o.dispose()
        chk.result
      },
      test("a RadioGroup follows its signals") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val options = Var(sizes)
        val selected = Var(-1)
        val m = Reconciler.mount(r, RadioGroup(options, selected)(selected.set))
        selected.set(0)
        options.set(sizes :+ "Huge")
        chk.eq(r.nodes(m.handle).nums("selected"), 0.0)
        chk.eq(r.nodes(m.handle).options, sizes :+ "Huge")
        o.dispose()
        chk.result
      },
      test("a Stepper sends its range and step before its value, and reports the number") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val value = Var(2.0)
        var got = Double.NaN
        val m = Reconciler.mount(r, Stepper(value, min = 0, max = 10, step = 0.5)(got = _))
        val n = r.nodes(m.handle)
        chk.eq(r.kind(m.handle), WidgetKind.Stepper)
        val order = n.applied.toList
        chk.yes(
          order.indexOf("Range") < order.indexOf("Value") && order.indexOf("Step") < order.indexOf("Value"),
          order.toString
        )
        chk.eq(n.props.get("step"), Some("0.5"))
        value.set(3.5)
        chk.eq(n.nums("value"), 3.5)
        n.onValueChange.foreach(_(4.0))
        chk.eq(got, 4.0)
        o.dispose()
        chk.result
      },
      test("a stepper's value is shown to its step's precision") {
        assertTrue(
          Steps.format(3.0, 1.0) == "3",
          Steps.format(2.75, 0.25) == "2.75",
          Steps.format(2.5, 0.25) == "2.50",
          Steps.format(0.30000000000000004, 0.1) == "0.3",
          Steps.digits(10.0) == 0,
          Steps.format(Double.NaN, 0.25) == "NaN",
          Steps.format(Double.PositiveInfinity, 0.25) == "Infinity",
          Steps.format(Double.NegativeInfinity, 0.25) == "-Infinity"
        )
      },
      test("invalid Stepper bounds and step values are rejected") {
        val badBounds = List(
          () => Stepper(0.0, min = Double.NaN, max = 1.0)(identity),
          () => Stepper(0.0, min = 0.0, max = Double.PositiveInfinity)(identity),
          () => Stepper(Double.NaN, min = 0.0, max = 1.0)(identity),
          () => Stepper(0.0, min = 1.0, max = 0.0)(identity),
          () => Stepper(0.0, step = 0.0)(identity),
          () => Stepper(0.0, step = -1.0)(identity),
          () => Stepper(0.0, step = Double.NaN)(identity),
          () => Stepper(0.0, step = Double.PositiveInfinity)(identity)
        )
        assertTrue(badBounds.forall(th => scala.util.Try(th()).isFailure))
      }
    ) @@ TestAspect.sequential

}
