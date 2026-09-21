package scalaui.gtk

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import sn.gnome.gtk4.internal.*
import sn.gnome.gobject.internal.*
import sn.gnome.glib.internal.*

/** GTK4 implementation of the draft contract. */
final class GtkRenderer extends Renderer {
  type Handle = Ptr[GtkWidget]

  def platform: String = "gtk4"

  def create(kind: WidgetKind, props: Seq[Prop]): Handle = {
    val w: Ptr[GtkWidget] = Zone {
      kind match {
        case WidgetKind.Column => gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)
        case WidgetKind.Row    => gtk_box_new(GtkOrientation.GTK_ORIENTATION_HORIZONTAL, 0)
        case WidgetKind.Label  => gtk_label_new(toCString(""))
        case WidgetKind.Button => gtk_button_new_with_label(toCString(""))
      }
    }
    Handles.kindOf(w) = kind
    update(w, props)
    w
  }

  def update(h: Handle, patch: Seq[Prop]): Unit =
    patch.foreach {
      case Prop.Text(v) =>
        Zone {
          Handles.kindOf.get(h) match {
            case Some(WidgetKind.Label) =>
              gtk_label_set_text(h.asInstanceOf[Ptr[GtkLabel]], toCString(v))
            case Some(WidgetKind.Button) =>
              gtk_button_set_label(h.asInstanceOf[Ptr[GtkButton]], toCString(v))
            case _ => ()
          }
        }
      case Prop.OnTap(f) =>
        val id = Handles.registerCallback(f)
        Zone {
          val handlerId = g_signal_connect_data(
            h.asInstanceOf[gpointer],
            toCString("clicked").asInstanceOf[Ptr[gchar]],
            GCallback.fromPtr(Handles.clickedPtr),
            Handles.idToPointer(id),
            null.asInstanceOf[GClosureNotify],
            GConnectFlags.define(0)
          )
          val _ = handlerId // non-zero on success; checked manually during the spike
        }
      case Prop.Spacing(dp) =>
        gtk_box_set_spacing(h.asInstanceOf[Ptr[GtkBox]], dp)
      case Prop.Padding(dp) =>
        gtk_widget_set_margin_top(h, dp)
        gtk_widget_set_margin_bottom(h, dp)
        gtk_widget_set_margin_start(h, dp)
        gtk_widget_set_margin_end(h, dp)
    }

  /** CONTRACT FRICTION 1: GTK containers own child order; there is no index-based
    * insert in the GtkBox API beyond append/prepend/insert_child_after, and no
    * "insert at index" primitive. See REPORT.md.
    */
  def insertChild(parent: Handle, child: Handle, index: Int): Unit =
    gtk_box_append(parent.asInstanceOf[Ptr[GtkBox]], child)

  def removeChild(parent: Handle, child: Handle): Unit =
    gtk_box_remove(parent.asInstanceOf[Ptr[GtkBox]], child)

  /** CONTRACT FRICTION 2: GTK measures via preferred sizes, and does its own layout.
    * A Yoga-driven `setFrame` has nowhere to go in a GtkBox. See REPORT.md.
    */
  def measure(h: Handle, c: Constraints): MeasuredSize = {
    val minW = stackalloc[CInt]()
    val natW = stackalloc[CInt]()
    val minH = stackalloc[CInt]()
    val natH = stackalloc[CInt]()
    gtk_widget_measure(h, GtkOrientation.GTK_ORIENTATION_HORIZONTAL, -1, minW, natW, null, null)
    gtk_widget_measure(h, GtkOrientation.GTK_ORIENTATION_VERTICAL, -1, minH, natH, null, null)
    MeasuredSize((!natW).toFloat, (!natH).toFloat)
  }

  def setFrame(h: Handle, frame: Rect): Unit =
    // Not expressible for a GtkBox child: the box positions its own children.
    // A Yoga-driven renderer would have to use GtkFixed throughout. See REPORT.md.
    ()

  def destroy(h: Handle): Unit = {
    Handles.kindOf.remove(h)
    g_object_unref(h.asInstanceOf[gpointer])
  }

  /** Posts onto the GTK main loop, from any thread. */
  def runOnUiThread(f: () => Unit): Unit = {
    val id = Handles.registerCallback(f)
    g_idle_add(GSourceFunc(Handles.idleTrampoline), Handles.idToPointer(id))
  }
}
