package scalaui.renderer.gtk

import scala.scalanative.unsafe.*
import sn.gnome.gtk4.internal.*

/** Reads the widget tree back out of GTK.
  *
  * Used by the demos to verify that the reconciler's `insertAfter` / `moveAfter` calls
  * actually produce the intended order in a real toolkit — the unit tests prove the
  * algorithm against an in-memory renderer, which cannot catch a wrong GTK call.
  */
object GtkInspect {

  /** Direct children of `widget`, in GTK's own order. */
  def children(widget: Ptr[GtkWidget]): List[Ptr[GtkWidget]] = {
    val out   = scala.collection.mutable.ListBuffer.empty[Ptr[GtkWidget]]
    var child = gtk_widget_get_first_child(widget)
    while child != null do {
      out += child
      child = gtk_widget_get_next_sibling(child)
    }
    out.toList
  }

  /** Whether the widget is a `GtkLabel`.
    *
    * `gtk_label_get_text` on anything else is a GTK assertion failure, not a null return,
    * so callers have to check first. The CSS name is the cheapest available discriminator
    * in these bindings — the `GTK_IS_LABEL` macro is not generated.
    */
  def isLabel(widget: Ptr[GtkWidget]): Boolean = {
    val name = gtk_widget_get_css_name(widget)
    name != null && fromCString(name) == "label"
  }

  /** Every piece of text in the subtree, in tree order: label text, and the label of a
    * button (which GTK nests as a child `GtkLabel`).
    */
  def allTexts(widget: Ptr[GtkWidget]): List[String] = {
    val kids = children(widget)
    if kids.nonEmpty then kids.flatMap(allTexts)
    else if isLabel(widget) then {
      val t = gtk_label_get_text(widget.asInstanceOf[Ptr[GtkLabel]])
      if t == null then Nil else List(fromCString(t))
    }
    else Nil
  }

  /** Label text of each direct child that is a label, in order. */
  def labelTexts(widget: Ptr[GtkWidget]): List[String] =
    children(widget).filter(isLabel).flatMap { c =>
      val t = gtk_label_get_text(c.asInstanceOf[Ptr[GtkLabel]])
      if t == null then None else Some(fromCString(t))
    }
}
