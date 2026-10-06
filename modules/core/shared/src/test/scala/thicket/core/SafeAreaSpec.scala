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
import thicket.renderer.Edge
import thicket.signals.{Checks, Owner}
import zio.test.*

/** `SafeArea` — a **prop**, not a widget.
  *
  * §12.2a answered a fifth time: no toolkit models a safe area as something you place. Apple exposes `safeAreaInsets`
  * on a view, Android hands insets to a listener on a view, and GTK4 has no such concept at all. These tests pin the
  * shape that follows from that, mostly so a later change does not quietly turn it into a container.
  */
object SafeAreaSpec extends ZIOSpecDefault {

  def spec =
    suite("SafeArea")(
      test("no arguments means every edge") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val m = Reconciler.mount(r, Column()(Label("x")).safeArea())
        chk.eq(r.nodes(m.handle).props("safeArea"), "Bottom,Leading,Top,Trailing")
        o.dispose()
        chk.result
      },
      test("one edge means one edge") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        // The case a real app wants: respect the status bar, let a list scroll under the home
        // indicator.
        val m = Reconciler.mount(r, Column()(Label("x")).safeArea(Edge.Top))
        chk.eq(r.nodes(m.handle).props("safeArea"), "Top")
        o.dispose()
        chk.result
      },
      test("it attaches to the widget rather than adding one") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        // The observable difference between a prop and a container: the tree shape is
        // unchanged. A `SafeArea` wrapper would show up here as an extra node.
        val plain = Reconciler.mount(r, Column()(Label("a"), Label("b")))
        val safe = Reconciler.mount(r, Column()(Label("a"), Label("b")).safeArea())
        chk.eq(r.childrenOf(safe.handle).length, r.childrenOf(plain.handle).length)
        chk.eq(r.childrenOf(safe.handle).map(r.text), Seq("a", "b"))
        o.dispose()
        chk.result
      },
      test("Edge.all is every case, so a new edge cannot be forgotten") {
        val chk = Checks()
        // If someone adds an Edge case, this catches that `all` was not updated — which would
        // silently shrink what `.safeArea()` with no arguments means.
        chk.eq(Edge.all.size, Edge.values.length)
        chk.result
      }
    ) @@ TestAspect.sequential

}
