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

/** `Picker` — a choice from a fixed list.
  *
  * The thing worth pinning down is that the selection is an **index**, because that is what makes two options with the
  * same label distinguishable and what every toolkit's API uses underneath. Most of these tests exist to stop a future
  * change quietly reverting to "the selected string".
  */
object PickerSpec extends ZIOSpecDefault {

  private val fruit = Seq("Apple", "Banana", "Cherry")

  def spec =
    suite("Picker")(
      test("options and selection reach the renderer") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val m = Reconciler.mount(r, Picker(fruit, 1)(_ => ()))

        chk.eq(r.kind(m.handle), WidgetKind.Picker)
        chk.eq(r.nodes(m.handle).options, fruit)
        chk.eq(r.nodes(m.handle).nums("selected"), 1.0)
        o.dispose()
        chk.result
      },
      test("choosing reports the index, not the label") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        var chosen = -99
        val m = Reconciler.mount(r, Picker(fruit, 0)(i => chosen = i))

        r.nodes(m.handle).onSelect.foreach(_(2))
        chk.eq(chosen, 2)
        o.dispose()
        chk.result
      },
      test("two options with the same label stay distinguishable") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        var chosen = -99
        // The case that rules out "the selected string" as the model: these are two different
        // choices and only an index tells them apart.
        val dupes = Seq("Other", "Other")
        val m = Reconciler.mount(r, Picker(dupes, 0)(i => chosen = i))

        chk.eq(r.nodes(m.handle).options.length, 2)
        r.nodes(m.handle).onSelect.foreach(_(1))
        chk.eq(chosen, 1, "the second Other, not the first")
        o.dispose()
        chk.result
      },
      test("-1 means nothing selected, which is not 0") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val m = Reconciler.mount(r, Picker(fruit, -1)(_ => ()))
        chk.eq(r.nodes(m.handle).nums("selected"), -1.0)
        o.dispose()
        chk.result
      },
      test("a reactive selection follows its signal") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val sel = Var(0)
        val m = Reconciler.mount(r, Picker(fruit, sel)(sel.set))

        chk.eq(r.nodes(m.handle).nums("selected"), 0.0)
        sel.set(2)
        chk.eq(r.nodes(m.handle).nums("selected"), 2.0)
        o.dispose()
        chk.result
      },
      test("reactive options can arrive after the picker is mounted") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        // The loaded-from-somewhere case: a picker mounted empty and filled later.
        val opts = Var(Seq.empty[String])
        val m = Reconciler.mount(r, Picker(opts, 0)(_ => ()))

        chk.eq(r.nodes(m.handle).options, Seq.empty)
        opts.set(fruit)
        chk.eq(r.nodes(m.handle).options, fruit)
        o.dispose()
        chk.result
      },
      test("options are applied before the selection") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        // Not a style preference: selecting index 2 of a list that is not there yet is ignored
        // by GTK and throws on Android, so the order the DSL emits them in is load bearing.
        val m = Reconciler.mount(r, Picker(fruit, 2)(_ => ()))
        val order = r.nodes(m.handle).applied.toList

        chk.yes(order.contains("Options"), order.toString)
        chk.yes(order.contains("Selected"), order.toString)
        chk.yes(order.indexOf("Options") < order.indexOf("Selected"), order.toString)
        o.dispose()
        chk.result
      }
    ) @@ TestAspect.sequential

}
