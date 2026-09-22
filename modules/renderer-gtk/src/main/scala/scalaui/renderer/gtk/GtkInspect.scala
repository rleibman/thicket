package scalaui.renderer.gtk

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
