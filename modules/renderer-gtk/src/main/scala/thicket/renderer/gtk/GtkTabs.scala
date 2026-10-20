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

package thicket.renderer.gtk

import scala.collection.mutable
import scala.scalanative.unsafe.*
import sn.gnome.gtk4.internal.*
import sn.gnome.gobject.internal.*
import sn.gnome.glib.internal.{gchar, gpointer}
import sn.gnome.adwaita.internal.*
import thicket.renderer.Icon

/** `TabView` on GTK (#61): an `AdwViewStack` with an `AdwViewSwitcherBar`, libadwaita's own bottom switcher.
  *
  * The `TabView` handle is a vertical box holding the two; each `Tab` handle is a box holding its one content child,
  * and that box is the stack page. Kept apart from `GtkRenderer` because of one awkward fact: `AdwViewStack` has no
  * insert-at-position, only "add" (append), so a tab inserted in the middle is placed by taking the later pages out
  * and adding them back, holding a reference while they are out — removing a page drops the stack's reference, which
  * would otherwise free it.
  */
private[gtk] final class GtkTabs(suppress: mutable.Set[Ptr[GtkWidget]]) {

  /** For each TabView: its stack, and its tabs in order. Our own order, because selection is by index. */
  private val stacks = mutable.Map.empty[Ptr[GtkWidget], Ptr[GtkWidget]]
  private val order  = mutable.Map.empty[Ptr[GtkWidget], Vector[Ptr[GtkWidget]]]

  /** For each Tab: its title and icon (kept so a re-added page gets them back), and the TabView it is in. */
  private val titles = mutable.Map.empty[Ptr[GtkWidget], String]
  private val icons  = mutable.Map.empty[Ptr[GtkWidget], Icon]
  private val owner  = mutable.Map.empty[Ptr[GtkWidget], Ptr[GtkWidget]]

  private val selectIds = mutable.Map.empty[Ptr[GtkWidget], Long]

  /** Each TabView's selection as the app last knows it, and its `OnSelect`. `known` is unset until a selection has
    * actually been shown, which is not at create: `Selected` is applied before any tab is mounted, so it is kept in
    * `wanted` and shown when its tab arrives (#70 review, the same thing `AndroidTabs` does).
    */
  private val known  = mutable.Map.empty[Ptr[GtkWidget], Int]
  private val wanted = mutable.Map.empty[Ptr[GtkWidget], Int]
  private val chosen = mutable.Map.empty[Ptr[GtkWidget], Int => Unit]

  /** After tabs are inserted or removed, the page on screen is the same page, but its index may not be: inserting a tab
    * before it moves it along. The user stays where they are, and the app is told the new index, so its model stays
    * true. Removing the visible tab is the same case: GTK shows another, and that is reported.
    */
  private def reportIfMoved(view: Ptr[GtkWidget]): Unit = {
    val now = selectedIndex(view)
    // Nothing to report while the app's own selection has not been shown yet: the tabs are
    // still arriving, and a 0 reported now would overwrite what the app asked for.
    if known.contains(view) && now >= 0 && !known.get(view).contains(now) then {
      known(view) = now
      chosen.get(view).foreach(_(now))
    }
  }

  def createTabView(): Ptr[GtkWidget] = {
    val box   = gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)
    val stack = adw_view_stack_new()
    gtk_widget_set_vexpand(stack, gbool(true))
    val bar = adw_view_switcher_bar_new()
    adw_view_switcher_bar_set_stack(bar.asInstanceOf[Ptr[AdwViewSwitcherBar]], stack.asInstanceOf[Ptr[AdwViewStack]])
    // Always shown: the bar is the TabView's switcher here, not only the narrow-window
    // fallback for a header-bar switcher.
    adw_view_switcher_bar_set_reveal(bar.asInstanceOf[Ptr[AdwViewSwitcherBar]], gbool(true))
    gtk_box_append(box.asInstanceOf[Ptr[GtkBox]], stack)
    gtk_box_append(box.asInstanceOf[Ptr[GtkBox]], bar)
    stacks(box) = stack
    order(box) = Vector.empty
    box
  }

  def createTab(): Ptr[GtkWidget] = gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)

  def isTabView(h: Ptr[GtkWidget]): Boolean = stacks.contains(h)

  private def stackOf(view: Ptr[GtkWidget]) = stacks(view).asInstanceOf[Ptr[AdwViewStack]]

  private def page(tab: Ptr[GtkWidget]): Option[Ptr[AdwViewStackPage]] =
    owner.get(tab).map(view => adw_view_stack_get_page(stackOf(view), tab))

  def setTitle(tab: Ptr[GtkWidget], title: String): Unit = {
    titles(tab) = title
    page(tab).foreach(p => Zone(adw_view_stack_page_set_title(p, toCString(title))))
  }

  def setIcon(tab: Ptr[GtkWidget], icon: Icon): Unit = {
    icons(tab) = icon
    page(tab).foreach(p => Zone(adw_view_stack_page_set_icon_name(p, toCString(GtkIcons.name(icon)))))
  }

  /** A tab's content: a Tab holds one child, so inserting is replacing. */
  def setContent(tab: Ptr[GtkWidget], child: Ptr[GtkWidget]): Unit = {
    var c = gtk_widget_get_first_child(tab)
    while c != null do {
      val next = gtk_widget_get_next_sibling(c)
      gtk_box_remove(tab.asInstanceOf[Ptr[GtkBox]], c)
      c = next
    }
    gtk_widget_set_vexpand(child, gbool(true))
    gtk_box_append(tab.asInstanceOf[Ptr[GtkBox]], child)
  }

  /** Take a page out of the stack, hidden first.
    *
    * libadwaita 1.9.1's `stack_remove` clears the stack's `visible_child` but not its `last_visible_child` — the page a
    * switch is moving away from, kept until the switch's animation ends on the next frame, even with transitions off. A
    * page removed in that window is freed while the stack still points at it, and the next frame's
    * `gtk_widget_set_child_visible` on it crashes. Hiding the page first runs the stack's own `update_child_visible`,
    * which does clear `last_visible_child` when it is that page.
    */
  private def detach(view: Ptr[GtkWidget], tab: Ptr[GtkWidget]): Unit = {
    gtk_widget_set_visible(tab, gbool(false))
    adw_view_stack_remove(stackOf(view), tab)
  }

  private def add(view: Ptr[GtkWidget], tab: Ptr[GtkWidget]): Unit = Zone {
    gtk_widget_set_visible(tab, gbool(true))
    val _ = adw_view_stack_add_titled_with_icon(
      stackOf(view),
      tab,
      null,
      toCString(titles.getOrElse(tab, "")),
      toCString(icons.get(tab).map(GtkIcons.name).getOrElse(""))
    )
  }

  /** Put `tab` after `after` (first when `None`): append it, then take out every page that belongs after it and add
    * them back, so the stack's order is the contract's order.
    */
  def insertAfter(view: Ptr[GtkWidget], tab: Ptr[GtkWidget], after: Option[Ptr[GtkWidget]]): Unit = {
    val current = order(view).filterNot(_ == tab)
    val at      = after.map(a => current.indexOf(a) + 1).getOrElse(0)
    val later   = current.drop(at)
    // Taking the later pages out may take out the one on screen, and the stack would show
    // another; the user should not move because a tab was added, so it is put back.
    val visible = adw_view_stack_get_visible_child(stackOf(view))
    suppress += view
    later.foreach { t =>
      val _ = g_object_ref(t.asInstanceOf[gpointer])
      detach(view, t)
    }
    if owner.contains(tab) then {
      val _ = g_object_ref(tab.asInstanceOf[gpointer])
      detach(view, tab)
    }
    val held = owner.contains(tab)
    owner(tab) = view
    add(view, tab)
    if held then g_object_unref(tab.asInstanceOf[gpointer])
    later.foreach { t =>
      add(view, t)
      g_object_unref(t.asInstanceOf[gpointer])
    }
    if visible != null && visible != tab then adw_view_stack_set_visible_child(stackOf(view), visible)
    suppress -= view
    order(view) = current.take(at) ++ (tab +: later)
    if known.contains(view) then reportIfMoved(view)
    else wanted.get(view).foreach(i => select(view, i))
  }

  def remove(view: Ptr[GtkWidget], tab: Ptr[GtkWidget]): Unit =
    if owner.remove(tab).isDefined then {
      order(view) = order(view).filterNot(_ == tab)
      suppress += view
      detach(view, tab)
      suppress -= view
      reportIfMoved(view)
    }

  def select(view: Ptr[GtkWidget], index: Int): Unit = {
    wanted(view) = index
    // AdwViewStack always shows a page, so -1 ("none") leaves it where it is.
    order(view).lift(index).foreach { tab =>
      known(view) = index
      if adw_view_stack_get_visible_child(stackOf(view)) != tab then {
        suppress += view
        adw_view_stack_set_visible_child(stackOf(view), tab)
        suppress -= view
      }
    }
  }

  def selectedIndex(view: Ptr[GtkWidget]): Int =
    order(view).indexOf(adw_view_stack_get_visible_child(stackOf(view)))

  /** The user's choice, through `notify::visible-child`, read back as an index into our order. */
  def onSelect(view: Ptr[GtkWidget], f: Int => Unit): Unit = {
    chosen(view) = f
    val report: String => Unit = { s =>
      known(view) = s.toInt
      f(s.toInt)
    }
    selectIds.get(view) match {
      case Some(id) => Handles.replaceValued(id, report)
      case None =>
        val id = Handles.registerValued(report)
        selectIds(view) = id
        Handles.bindTextSource(id, () => selectedIndex(view), () => suppress.contains(view))
        Zone {
          val _ = g_signal_connect_data(
            stacks(view).asInstanceOf[gpointer],
            toCString("notify::visible-child").asInstanceOf[Ptr[gchar]],
            GCallback.fromPtr(Handles.notifiedPtr),
            Handles.idToPointer(id),
            null.asInstanceOf[GClosureNotify],
            GConnectFlags.define(0)
          )
        }
    }
  }

  /** Forget a TabView or a Tab. A Tab still in its stack is removed through the stack, which owns it as a page. */
  def destroy(h: Ptr[GtkWidget]): Boolean = {
    selectIds.remove(h).foreach(Handles.release)
    val _ = stacks.remove(h)
    val _ = order.remove(h)
    val _ = known.remove(h)
    val _ = wanted.remove(h)
    val _ = chosen.remove(h)
    val _ = titles.remove(h)
    val _ = icons.remove(h)
    owner.get(h) match {
      case Some(view) if stacks.contains(view) =>
        remove(view, h)
        true
      case _ =>
        val _ = owner.remove(h)
        false
    }
  }

  private def gbool(b: Boolean): sn.gnome.glib.internal.gboolean =
    (if b then 1 else 0).asInstanceOf[sn.gnome.glib.internal.gboolean]

}
