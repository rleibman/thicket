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
  def naturalWidth(widget: Ptr[GtkWidget]): Int = {
    val zone = scala.scalanative.unsafe.Zone.open()
    try {
      given Zone = zone
      val min    = alloc[CInt](1)
      val nat    = alloc[CInt](1)
      gtk_widget_measure(widget, GtkOrientation.GTK_ORIENTATION_HORIZONTAL, -1, min, nat, null, null)
      !nat
    } finally zone.close()
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
