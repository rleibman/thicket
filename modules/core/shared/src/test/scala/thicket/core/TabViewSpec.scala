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
import thicket.renderer.{Icon, WidgetKind}
import thicket.signals.{Checks, Owner, Var}
import zio.test.*

/** `TabView` and `Tab` (#61).
  *
  * The promises: tabs arrive in order with their title and icon; every tab's content is mounted, not only the selected
  * one's, because keeping them alive is the point; and the selection is an index like `Picker`'s.
  */
object TabViewSpec extends ZIOSpecDefault {

  def spec =
    suite("TabView")(
      test("tabs in order, each with its title, icon and content") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val m = Reconciler.mount(
          r,
          TabView(0)(_ => ())(
            Tab("Items", Icon.Home)(Label("the list")),
            Tab("Settings", Icon.Settings)(Label("prefs"))
          )
        )

        chk.eq(r.kind(m.handle), WidgetKind.TabView)
        val tabs = r.childrenOf(m.handle)
        chk.eq(tabs.map(r.kind), Seq(WidgetKind.Tab, WidgetKind.Tab))
        chk.eq(tabs.map(r.text), Seq("Items", "Settings"))
        chk.eq(tabs.map(t => r.nodes(t).props("icon")), Seq("Home", "Settings"))
        o.dispose()
        chk.result
      },
      test("every tab's content is mounted, not only the selected one's") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val m = Reconciler.mount(
          r,
          TabView(0)(_ => ())(Tab("A", Icon.Home)(Label("first")), Tab("B", Icon.Star)(Label("second")))
        )
        // Keeping tabs alive is what preserves a tab's scroll position and in-flight work when
        // the user switches away and back, which is what every native tab container does.
        val contents = r.childrenOf(m.handle).flatMap(r.childrenOf).map(r.text)
        chk.eq(contents, Seq("first", "second"))
        o.dispose()
        chk.result
      },
      test("the selection is an index, both ways") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val tab = Var(0)
        val m = Reconciler.mount(
          r,
          TabView(tab)(tab.set)(Tab("A", Icon.Home)(Label("a")), Tab("B", Icon.Star)(Label("b")))
        )

        chk.eq(r.nodes(m.handle).nums("selected"), 0.0)
        tab.set(1)
        chk.eq(r.nodes(m.handle).nums("selected"), 1.0)
        r.nodes(m.handle).onSelect.foreach(_(0))
        chk.eq(tab.now, 0)
        o.dispose()
        chk.result
      },
      test("a tab's title can follow a signal") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val unread = Var(0)
        val m = Reconciler.mount(
          r,
          TabView(0)(_ => ())(Tab(unread.map(n => if n == 0 then "Inbox" else s"Inbox ($n)"), Icon.Mail)(Label("x")))
        )
        unread.set(3)
        chk.eq(r.text(r.childrenOf(m.handle).head), "Inbox (3)")
        o.dispose()
        chk.result
      },
      test("a tab shown later lands where it is written") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val admin = Var(false)
        val m = Reconciler.mount(
          r,
          TabView(0)(_ => ())(
            Tab("Home", Icon.Home)(Label("h")),
            Show(admin)(Tab("Admin", Icon.Settings)(Label("a"))),
            Tab("Me", Icon.Person)(Label("m"))
          )
        )
        admin.set(true)
        chk.eq(r.childrenOf(m.handle).map(r.text), Seq("Home", "Admin", "Me"))
        o.dispose()
        chk.result
      }
    ) @@ TestAspect.sequential

}
