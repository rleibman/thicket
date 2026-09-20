package scalaui.renderer.gtk

import scala.scalanative.unsafe.*
import sn.gnome.gtk4.internal.*

/** Reads the widget tree back out of GTK.
  *
  * Used by the demos to verify that the reconciler's `insertAfter` / `moveAfter` calls
  * actually produce the intended order in a real toolkit — the unit tests prove the
  * algorithm against an in-memory renderer, which cannot catch a wrong GTK call.
  */
object GtkInspect:

  /** Direct children of `widget`, in GTK's own order. */
  def children(widget: Ptr[GtkWidget]): List[Ptr[GtkWidget]] =
    val out   = scala.collection.mutable.ListBuffer.empty[Ptr[GtkWidget]]
    var child = gtk_widget_get_first_child(widget)
    while child != null do
      out += child
      child = gtk_widget_get_next_sibling(child)
    out.toList

  /** Label text of each direct child that is a label, in order. */
  def labelTexts(widget: Ptr[GtkWidget]): List[String] =
    children(widget).flatMap: c =>
      val t = gtk_label_get_text(c.asInstanceOf[Ptr[GtkLabel]])
      if t == null then None else Some(fromCString(t))
