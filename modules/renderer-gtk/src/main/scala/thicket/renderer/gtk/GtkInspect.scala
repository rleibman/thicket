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

import scala.scalanative.unsafe.*
import sn.gnome.gtk4.internal.*
import sn.gnome.gobject.internal.{g_type_check_instance_is_a, GType, GTypeInstance}

/** Reads the widget tree back out of GTK.
  *
  * Used by the demos to verify that the renderer's calls produce the intended result in a
  * real toolkit — the unit tests prove the reconciler against an in-memory renderer, which
  * cannot catch a wrong GTK call.
  *
  * Type tests go through `g_type_check_instance_is_a`, which is GTK's own mechanism. An
  * earlier version compared `gtk_widget_get_css_name`, which is a presentation detail and
  * does not reliably identify a widget class.
  */
object GtkInspect {

  private def isA(widget: Ptr[GtkWidget], tpe: GType): Boolean =
    widget != null &&
      g_type_check_instance_is_a(widget.asInstanceOf[Ptr[GTypeInstance]], tpe)
        .asInstanceOf[CInt] != 0

  /** How many stylesheets this renderer wrote that GTK could not parse. Should be zero.
    *
    * Exposed here rather than from `Handles`, which is `private[gtk]` and should stay that
    * way: this is a question a self-test asks of the toolkit, which is what `GtkInspect` is
    * for.
    *
    * Why it is a counter and not a log grep: GTK reports bad CSS with a `Gtk-WARNING` and
    * then carries on with whatever parsed. The renderer asked for CSS nesting, which GTK4
    * does not support, from the day theming landed — 26 warnings per run of the demo, the
    * child rule silently discarded, and nothing failed, because the self-tests grep for
    * `Gtk-CRITICAL` (#30).
    */
  def cssParseErrors: Long = Handles.cssParseErrors

  /** Callbacks the handle table is holding right now.
    *
    * A `destroy` that forgets to release an id leaves its closure here for the life of the
    * process, and the closure can capture a whole subtree. Comparing the count across a
    * mount-then-unmount is the cheap way to notice; `valueIds` leaked from the day `Slider`
    * landed and nothing saw it.
    */
  def liveCallbacks: Int = Handles.liveCount

  /** Whether the pointer still refers to a live `GtkWidget`.
    *
    * On a toolkit where a container owns its children, freeing a container frees its
    * descendants, so a renderer can be handed a pointer the toolkit has already reclaimed.
    */
  def isWidget(widget: Ptr[GtkWidget]): Boolean = isA(widget, gtk_widget_get_type())

  def isLabel(widget: Ptr[GtkWidget]): Boolean = isA(widget, gtk_label_get_type())
  def isEntry(widget: Ptr[GtkWidget]): Boolean = isA(widget, gtk_entry_get_type())
  def isSwitch(widget: Ptr[GtkWidget]): Boolean = isA(widget, gtk_switch_get_type())
  def isSpinner(widget: Ptr[GtkWidget]): Boolean = isA(widget, gtk_spinner_get_type())

  def isProgressBar(widget: Ptr[GtkWidget]): Boolean =
    isA(widget, gtk_progress_bar_get_type())

  def isScale(widget: Ptr[GtkWidget]): Boolean = isA(widget, gtk_scale_get_type())

  /** A context menu's popover. Parented to its widget but not in the parent box's child
    * list, so it is reachable by walking and invisible to a child count — which is the
    * distinction a `ContextMenu` prop is meant to have.
    */
  def isPopover(widget: Ptr[GtkWidget]): Boolean = isA(widget, gtk_popover_get_type())

  def scaleValue(widget: Ptr[GtkWidget]): Double =
    gtk_range_get_value(widget.asInstanceOf[Ptr[GtkRange]])

  /** False for a `SecureField`: the characters are there but GTK does not draw them. */
  def entryVisible(widget: Ptr[GtkWidget]): Boolean =
    gtk_entry_get_visibility(widget.asInstanceOf[Ptr[GtkEntry]]).asInstanceOf[CInt] != 0

  /** Every descendant for which `p` holds, in tree order. */
  def findAll(widget: Ptr[GtkWidget])(p: Ptr[GtkWidget] => Boolean): List[Ptr[GtkWidget]] = {
    val here = if p(widget) then List(widget) else Nil
    here ++ children(widget).flatMap(c => findAll(c)(p))
  }

  /** What the switch actually shows, read back out of GTK rather than out of the signal
    * that drove it — which is the only way to know the renderer wrote it.
    */
  def switchActive(widget: Ptr[GtkWidget]): Boolean =
    gtk_switch_get_active(widget.asInstanceOf[Ptr[GtkSwitch]]).asInstanceOf[CInt] != 0

  def progressFraction(widget: Ptr[GtkWidget]): Double =
    gtk_progress_bar_get_fraction(widget.asInstanceOf[Ptr[GtkProgressBar]])

  /** Direct children of `widget`, in GTK's own order. */
  def children(widget: Ptr[GtkWidget]): List[Ptr[GtkWidget]] = {
    val out   = scala.collection.mutable.ListBuffer.empty[Ptr[GtkWidget]]
    var child = gtk_widget_get_first_child(widget)
    while (child != null) {
      out += child
      child = gtk_widget_get_next_sibling(child)
    }
    out.toList
  }

  /** The width GTK would give `widget` if nothing constrained it.
    *
    * A horizontal scroller is doing its job exactly when its own natural width is far
    * below its content's: the content overflows, and the viewport asks for a fraction of
    * it rather than forcing the window wider or clipping the surplus.
    */
  def naturalWidth(widget: Ptr[GtkWidget]): Int = natural(widget, GtkOrientation.GTK_ORIENTATION_HORIZONTAL)

  def naturalHeight(widget: Ptr[GtkWidget]): Int = natural(widget, GtkOrientation.GTK_ORIENTATION_VERTICAL)

  private def natural(widget: Ptr[GtkWidget], axis: GtkOrientation): Int = {
    val zone = scala.scalanative.unsafe.Zone.open()
    try {
      given Zone = zone
      val min    = alloc[CInt](1)
      val nat    = alloc[CInt](1)
      gtk_widget_measure(widget, axis, -1, min, nat, null, null)
      !nat
    } finally zone.close()
  }

  /** Route every link opened from here on into the returned buffer instead of to the desktop.
    *
    * A self-test that clicks a link must not launch a browser on the machine it runs on, so this records what *would*
    * have opened. It proves the click reaches the URL; that `GtkUriLauncher` then opens it is the platform's half, and
    * is not exercised by any self-test.
    */
  def interceptUrls(): scala.collection.mutable.Buffer[String] = {
    val opened = scala.collection.mutable.Buffer.empty[String]
    UrlOpener.replace((url, _) => opened += url)
    opened
  }

  /** Emit a button's "clicked" signal, as a real click does — through the handler GTK has connected, not by calling
    * ours. On a `GtkToggleButton` that is what toggles it.
    *
    * Not `gtk_widget_activate`, which on a button plays the pressed animation and emits "clicked" about 250 ms *later*:
    * a check that reads straight afterwards sees nothing.
    */
  def click(widget: Ptr[GtkWidget]): Unit =
    Zone(
      sn.gnome.gobject.internal.g_signal_emit_by_name(
        widget.asInstanceOf[sn.gnome.glib.internal.gpointer],
        toCString("clicked").asInstanceOf[Ptr[sn.gnome.glib.internal.gchar]]
      )
    )

  def hasCssClass(widget: Ptr[GtkWidget], name: String): Boolean =
    Zone(gtk_widget_has_css_class(widget, toCString(name)).asInstanceOf[CInt] != 0)

  def isToggleButton(widget: Ptr[GtkWidget]): Boolean = isA(widget, gtk_toggle_button_get_type())

  def toggleActive(widget: Ptr[GtkWidget]): Boolean =
    gtk_toggle_button_get_active(widget.asInstanceOf[Ptr[GtkToggleButton]]).asInstanceOf[CInt] != 0

  def isMenuButton(widget: Ptr[GtkWidget]): Boolean = isA(widget, gtk_menu_button_get_type())

  def menuButtonLabel(widget: Ptr[GtkWidget]): String = {
    val l = gtk_menu_button_get_label(widget.asInstanceOf[Ptr[GtkMenuButton]])
    if l == null then "" else fromCString(l)
  }

  /** A `DatePicker`'s calendar: the child of its menu button's popover, which is not in the widget tree a walk from the
    * root sees until it is opened.
    */
  def calendarOf(menuButton: Ptr[GtkWidget]): Option[Ptr[GtkWidget]] = {
    val popover = gtk_menu_button_get_popover(menuButton.asInstanceOf[Ptr[GtkMenuButton]])
    if popover == null then None
    else Option(gtk_popover_get_child(popover)).filter(c => isA(c, gtk_calendar_get_type()))
  }

  /** The date the calendar shows, as (year, month 1-12, day). */
  def calendarDate(calendar: Ptr[GtkWidget]): (Int, Int, Int) = {
    val c = calendar.asInstanceOf[Ptr[GtkCalendar]]
    (gtk_calendar_get_year(c), gtk_calendar_get_month(c) + 1, gtk_calendar_get_day(c))
  }

  /** Choose a day as a click on it does: move the calendar there and emit "day-selected", which is the signal a click
    * on a day emits — so the renderer's own handler is what reports it.
    */
  def chooseDay(calendar: Ptr[GtkWidget], year: Int, month: Int, day: Int): Unit = {
    val c = calendar.asInstanceOf[Ptr[GtkCalendar]]
    gtk_calendar_set_day(c, 1)
    gtk_calendar_set_year(c, year)
    gtk_calendar_set_month(c, month - 1)
    gtk_calendar_set_day(c, day)
    Zone(
      sn.gnome.gobject.internal.g_signal_emit_by_name(
        calendar.asInstanceOf[sn.gnome.glib.internal.gpointer],
        toCString("day-selected").asInstanceOf[Ptr[sn.gnome.glib.internal.gchar]]
      )
    )
  }

  def isButton(widget: Ptr[GtkWidget]): Boolean = isA(widget, gtk_button_get_type())

  def isGrid(widget: Ptr[GtkWidget]): Boolean = isA(widget, gtk_grid_get_type())

  /** The icon name a button shows, or "" for none. */
  def buttonIconName(widget: Ptr[GtkWidget]): String = {
    val n = gtk_button_get_icon_name(widget.asInstanceOf[Ptr[GtkButton]])
    if n == null then "" else fromCString(n)
  }

  def tooltip(widget: Ptr[GtkWidget]): String = {
    val t = gtk_widget_get_tooltip_text(widget)
    if t == null then "" else fromCString(t)
  }

  /** Whether the icon theme in use can draw `name` — asked of the theme, not of a list, so a name that the user's theme
    * lacks fails here rather than drawing GTK's missing-image icon.
    */
  def themeHasIcon(widget: Ptr[GtkWidget], name: String): Boolean =
    Zone(
      gtk_icon_theme_has_icon(gtk_icon_theme_get_for_display(gtk_widget_get_display(widget)), toCString(name))
        .asInstanceOf[CInt] != 0
    )

  /** Where a `Grid` put a cell, as (column, row), read back from GTK rather than computed. */
  def gridCell(grid: Ptr[GtkWidget], child: Ptr[GtkWidget]): (Int, Int) = {
    val zone = scala.scalanative.unsafe.Zone.open()
    try {
      given Zone = zone
      val col = alloc[CInt](1)
      val row = alloc[CInt](1)
      gtk_grid_query_child(grid.asInstanceOf[Ptr[GtkGrid]], child, col, row, null, null)
      (!col, !row)
    } finally zone.close()
  }

  /** A `ZStack`. */
  def isOverlay(widget: Ptr[GtkWidget]): Boolean = isA(widget, gtk_overlay_get_type())

  /** Where a ZStack placed `widget`, as the contract's names for it, horizontal then vertical. */
  def placement(widget: Ptr[GtkWidget]): (String, String) = {
    def name(a: GtkAlign): String =
      if a == GtkAlign.GTK_ALIGN_START then "Start"
      else if a == GtkAlign.GTK_ALIGN_CENTER then "Center"
      else if a == GtkAlign.GTK_ALIGN_END then "End"
      else "other"
    (name(gtk_widget_get_halign(widget)), name(gtk_widget_get_valign(widget)))
  }

  /** The first descendant that is a `GtkScrolledWindow`, in tree order. */
  def findScroller(widget: Ptr[GtkWidget]): Option[Ptr[GtkWidget]] =
    if isA(widget, gtk_scrolled_window_get_type()) then Some(widget)
    else children(widget).view.flatMap(findScroller).headOption

  /** Every piece of text in the subtree, in tree order: label text, the label of a button
    * (which GTK nests as a child `GtkLabel`), and the contents of a text entry.
    *
    * Entries are checked *before* recursing: a `GtkEntry` has internal children of its own,
    * and walking into them would report its private structure instead of its value.
    */
  def allTexts(widget: Ptr[GtkWidget]): List[String] =
    if isEntry(widget) then {
      val t = gtk_editable_get_text(widget.asInstanceOf[Ptr[GtkEditable]])
      if t == null then Nil else List(fromCString(t))
    } else {
      val kids = children(widget)
      if kids.nonEmpty then kids.flatMap(allTexts)
      else if isLabel(widget) then {
        val t = gtk_label_get_text(widget.asInstanceOf[Ptr[GtkLabel]])
        if t == null then Nil else List(fromCString(t))
      } else Nil
    }

  /** Label text of each direct child that is a label, in order. */
  def labelTexts(widget: Ptr[GtkWidget]): List[String] =
    children(widget).filter(isLabel).flatMap { c =>
      val t = gtk_label_get_text(c.asInstanceOf[Ptr[GtkLabel]])
      if t == null then None else Some(fromCString(t))
    }
}
