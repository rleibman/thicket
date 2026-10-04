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
import thicket.renderer.MenuItem
import thicket.signals.{Checks, Owner, Var}
import zio.test.*

/** A context menu is a *property of a widget*, not a widget.
  *
  * That is the claim these tests pin down, because it is the thing that would be easy to get wrong by analogy with
  * `Alert` — which is presented, and which a menu superficially resembles. All four toolkits model a context menu as
  * something a view *has*: `NSView.menu`, `UIContextMenuInteraction`, a `GtkPopover` parented to the widget, a
  * `PopupMenu` anchored at the view.
  */
object MenuSpec extends ZIOSpecDefault {

  def spec =
    suite("ContextMenu")(
      test("it attaches to a widget rather than becoming one") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val m = Reconciler.mount(
          r,
          Column()(Label("a row").contextMenu(MenuItem("Rename")(()), MenuItem("Delete")(())))
        )

        // One child, the label. A menu adds no node and presents nothing.
        chk.eq(r.childrenOf(m.handle).length, 1)
        chk.eq(r.presentedNow.toSeq, Seq.empty, "a menu is not presented; it is owned")
        chk.eq(r.nodes(r.childrenOf(m.handle).head).props("menu"), "Rename,Delete")
        o.dispose()
        chk.result
      },
      test("items keep their order and their enabled state") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val m = Reconciler.mount(
          r,
          Label("x").contextMenu(
            MenuItem("First")(()),
            MenuItem("Disabled", enabled = false)(()),
            MenuItem("Last")(())
          )
        )
        val items = r.nodes(m.handle).menu
        chk.eq(items.map(_.label), Seq("First", "Disabled", "Last"))
        chk.no(items(1).enabled, "an item can be present but not selectable")
        chk.yes(items.head.enabled)
        o.dispose()
        chk.result
      },
      test("selecting an item runs that item, and only that one") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        var renamed = 0
        var deleted = 0
        val m = Reconciler.mount(
          r,
          Label("x").contextMenu(MenuItem("Rename")(renamed += 1), MenuItem("Delete")(deleted += 1))
        )

        r.nodes(m.handle).menu.head.onSelect()
        chk.eq(renamed, 1)
        chk.eq(deleted, 0)
        o.dispose()
        chk.result
      },
      test("a menu on a row inside a keyed list belongs to that row") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val items = Var(Seq(1, 2, 3))
        var acted = 0

        val m = Reconciler.mount(
          r,
          Column()(ForEach(items, key = (i: Int) => i) { item =>
            Label(item.map(_.toString)).contextMenu(MenuItem("Act")(acted += item.now))
          })
        )

        val rows = r.childrenOf(m.handle)
        chk.eq(rows.length, 3)
        // The third row's menu must act on the third item, not on whatever was mounted first.
        r.nodes(rows(2)).menu.head.onSelect()
        chk.eq(acted, 3)

        // And after a reorder it still follows its own row.
        items.set(Seq(3, 1, 2))
        acted = 0
        r.nodes(r.childrenOf(m.handle).head).menu.head.onSelect()
        chk.eq(acted, 3, "the row that moved to the front carries its own menu")
        o.dispose()
        chk.result
      }
    ) @@ TestAspect.sequential

}
