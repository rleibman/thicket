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
import thicket.renderer.{PlatformView, WidgetKind}
import thicket.signals.{Checks, Owner, Var}
import zio.test.*

/** `Platform`, the embedding point (`docs/17` §17.3).
  *
  * What core promises: a `Platform` widget carries its view to the renderer as `Prop.View`, and a signal replaces the
  * view on the same widget. Making the native view, and releasing it, is each renderer's, and the galleries check it.
  */
object PlatformSpec extends ZIOSpecDefault {

  private def view(name: String): PlatformView =
    new PlatformView {
      val description = name
    }

  def spec =
    suite("Platform")(
      test("a Platform widget hands its view to the renderer") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val m = Reconciler.mount(r, Platform(view("a map")))
        chk.eq(r.kind(m.handle), WidgetKind.Platform)
        chk.eq(r.nodes(m.handle).props.get("view"), Some("a map"))
        chk.eq(r.childrenOf(m.handle), Seq.empty)
        o.dispose()
        chk.result
      },
      test("a signal replaces the view on the same widget") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val which = Var("first")
        val m = Reconciler.mount(r, Platform(which.map(view)))
        chk.eq(r.nodes(m.handle).props.get("view"), Some("first"))
        which.set("second")
        chk.eq(r.kind(m.handle), WidgetKind.Platform)
        chk.eq(r.nodes(m.handle).props.get("view"), Some("second"))
        chk.eq(r.destroyed, Nil)
        o.dispose()
        chk.result
      }
    )

}
