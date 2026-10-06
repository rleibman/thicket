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

/** `Link` and `.link(url)` — opening a URL is a property of a view, not a widget of its own.
  *
  * What is worth pinning down is the shape: a `Link` is a `Button` carrying the URL and *not* a button's accent fill,
  * and the modifier adds the URL without touching anything else about the element it is put on.
  */
object LinkSpec extends ZIOSpecDefault {

  private val url = "https://github.com/rleibman/thicket"

  def spec =
    suite("Link")(
      test("a Link is a Button carrying its URL, not a widget kind of its own") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val m = Reconciler.mount(r, Link("Read the docs", url))

        chk.eq(r.kind(m.handle), WidgetKind.Button)
        chk.eq(r.text(m.handle), "Read the docs")
        chk.eq(r.nodes(m.handle).props("url"), url)
        o.dispose()
        chk.result
      },
      test("a Link has no accent fill, so the platform's link styling is what shows") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val link = Reconciler.mount(r, Link("Docs", url))
        val button = Reconciler.mount(r, Button("Save")(()))

        // Asked of the props that *arrived*, not of the stored colour: with no theme the
        // accent is None, so a stored-colour check passes for an ordinary button too. The
        // control case first — an ordinary button is sent a Fill, which is what would make a
        // link look like a button that does something in the app.
        chk.yes(r.nodes(button.handle).applied.contains("Fill"), r.nodes(button.handle).applied.toString)
        chk.yes(!r.nodes(link.handle).applied.contains("Fill"), r.nodes(link.handle).applied.toString)
        o.dispose()
        chk.result
      },
      test("the link text can follow a signal") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val label = Var("Docs")
        val m = Reconciler.mount(r, Link(label, url))

        label.set("Documentation")
        chk.eq(r.text(m.handle), "Documentation")
        chk.eq(r.nodes(m.handle).props("url"), url)
        o.dispose()
        chk.result
      },
      test(".link adds the URL to any widget and leaves the rest of it alone") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        var tapped = 0
        // A row that opens a page, and also does something in the app when tapped: the URL
        // must not displace the tap, because a renderer that wires one click handler would.
        val m = Reconciler.mount(r, Row()(Label("Licence")).onTap(tapped += 1).link(url))

        chk.eq(r.kind(m.handle), WidgetKind.Row)
        chk.eq(r.nodes(m.handle).props("url"), url)
        chk.yes(r.nodes(m.handle).onTap.isDefined, "the tap is still there")
        r.nodes(m.handle).onTap.foreach(_())
        chk.eq(tapped, 1)
        o.dispose()
        chk.result
      }
    ) @@ TestAspect.sequential

}
