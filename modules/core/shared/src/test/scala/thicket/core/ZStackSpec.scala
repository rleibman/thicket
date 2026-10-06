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
import thicket.renderer.{Alignment, WidgetKind}
import thicket.signals.{Checks, Owner, Var}
import zio.test.*

/** `ZStack` — children drawn over one another.
  *
  * In a `Column` the child order is reading order. Here it is also **paint order**: the last child is on top. So the
  * thing worth pinning down is that order survives everything the reconciler does to it, because a mistake shows up as
  * a spinner hidden *behind* the content it was meant to cover — which no test that only counts children would notice.
  */
object ZStackSpec extends ZIOSpecDefault {

  def spec =
    suite("ZStack")(
      test("children arrive in paint order, last on top") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val m = Reconciler.mount(r, ZStack()(Label("under"), Label("over")))

        chk.eq(r.kind(m.handle), WidgetKind.ZStack)
        chk.eq(r.childrenOf(m.handle).map(r.text), Seq("under", "over"))
        o.dispose()
        chk.result
      },
      test("centred on both axes unless told otherwise") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val m = Reconciler.mount(r, ZStack()(Label("x")))

        chk.eq(r.nodes(m.handle).props("horizontal"), "Center")
        chk.eq(r.nodes(m.handle).props("vertical"), "Center")
        o.dispose()
        chk.result
      },
      test("the alignment is the stack's, both axes independently") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        // The badge case: trailing and top. Asking for two *different* values is what would
        // catch the axes being swapped, which Center/Center never could.
        val m = Reconciler.mount(r, ZStack(Alignment.End, Alignment.Start)(Label("picture"), Label("3")))

        chk.eq(r.nodes(m.handle).props("horizontal"), "End")
        chk.eq(r.nodes(m.handle).props("vertical"), "Start")
        // On the stack, not pushed down onto the children: the renderer places them.
        chk.yes(r.childrenOf(m.handle).forall(c => !r.nodes(c).props.contains("horizontal")))
        o.dispose()
        chk.result
      },
      test("an overlay shown later lands on top, not underneath") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        // The case ZStack exists for: content, with a spinner over it while loading. It is
        // mounted *after* the content, and an insert that put it first would paint it behind.
        val busy = Var(false)
        val m = Reconciler.mount(r, ZStack()(Label("content"), Show(busy)(Label("loading"))))

        chk.eq(r.childrenOf(m.handle).map(r.text), Seq("content"))
        busy.set(true)
        chk.eq(r.childrenOf(m.handle).map(r.text), Seq("content", "loading"))
        busy.set(false)
        chk.eq(r.childrenOf(m.handle).map(r.text), Seq("content"))
        o.dispose()
        chk.result
      },
      test("an underlay shown later lands underneath, not on top") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        // The mirror of the overlay, and the one an append-only renderer gets wrong: a
        // highlight that appears *behind* content already on screen must be inserted first.
        val highlighted = Var(false)
        val m = Reconciler.mount(r, ZStack()(Show(highlighted)(Label("highlight")), Label("content")))

        highlighted.set(true)
        chk.eq(r.childrenOf(m.handle).map(r.text), Seq("highlight", "content"))
        o.dispose()
        chk.result
      },
      test("a keyed reorder changes which child is on top") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val cards = Var(Seq("a", "b", "c"))
        val m = Reconciler.mount(r, ZStack()(ForEach(cards, identity)(s => Label(s))))

        // Bring "a" to the front. In a ZStack that is a z-order change, so the renderer's
        // moveAfter has to reorder paint, not just a list.
        cards.set(Seq("b", "c", "a"))
        chk.eq(r.childrenOf(m.handle).map(r.text), Seq("b", "c", "a"))
        o.dispose()
        chk.result
      }
    ) @@ TestAspect.sequential

}
