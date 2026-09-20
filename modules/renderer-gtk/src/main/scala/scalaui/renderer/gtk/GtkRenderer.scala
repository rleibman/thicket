package scalaui.renderer.gtk

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.collection.mutable
import scalaui.renderer.*
import sn.gnome.gtk4.internal.*
import sn.gnome.gobject.internal.*
import sn.gnome.glib.internal.{gchar, gpointer}
import sn.gnome.glib.internal.{g_idle_add, GSourceFunc}

/** GTK4 implementation of [[Renderer]]. */
final class GtkRenderer extends Renderer:
  type Handle = Ptr[GtkWidget]

  private val kinds    = mutable.Map.empty[Ptr[GtkWidget], WidgetKind]
  private val tapIds   = mutable.Map.empty[Ptr[GtkWidget], Long]

  def platform: String = "gtk4"

  /** Every GTK container in the v0 vocabulary lays out its own children. Nothing here is
    * FrameBased yet; a Yoga-driven `GtkFixed` container will be when absolute layout lands.
    */
  def layoutMode(kind: WidgetKind): LayoutMode = kind match
    case WidgetKind.Column | WidgetKind.Row => LayoutMode.ToolkitManaged
    case WidgetKind.Label | WidgetKind.Button => LayoutMode.ToolkitManaged

  def create(kind: WidgetKind, props: Seq[Prop]): Handle =
    val w = Zone:
      kind match
        case WidgetKind.Column => gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)
        case WidgetKind.Row    => gtk_box_new(GtkOrientation.GTK_ORIENTATION_HORIZONTAL, 0)
        case WidgetKind.Label  => gtk_label_new(toCString(""))
        case WidgetKind.Button => gtk_button_new_with_label(toCString(""))
    kinds(w) = kind
    update(w, props)
    w

  def update(handle: Handle, patch: Seq[Prop]): Unit =
    patch.foreach:
      case Prop.Text(v) =>
        Zone:
          kinds.get(handle) match
            case Some(WidgetKind.Label) =>
              gtk_label_set_text(handle.asInstanceOf[Ptr[GtkLabel]], toCString(v))
            case Some(WidgetKind.Button) =>
              gtk_button_set_label(handle.asInstanceOf[Ptr[GtkButton]], toCString(v))
            case _ => ()

      case Prop.OnTap(f) =>
        // Re-tapping an existing handler swaps the closure rather than connecting a second
        // signal, so repeated property updates cannot stack handlers.
        tapIds.get(handle) match
          case Some(id) => Handles.replace(id, f)
          case None =>
            val id = Handles.register(f)
            tapIds(handle) = id
            Zone:
              val _ = g_signal_connect_data(
                handle.asInstanceOf[gpointer],
                toCString("clicked").asInstanceOf[Ptr[gchar]],
                GCallback.fromPtr(Handles.clickedPtr),
                Handles.idToPointer(id),
                null.asInstanceOf[GClosureNotify],
                GConnectFlags.define(0)
              )

      case Prop.Spacing(dp) =>
        if kinds.get(handle).exists(k => k == WidgetKind.Column || k == WidgetKind.Row) then
          gtk_box_set_spacing(handle.asInstanceOf[Ptr[GtkBox]], dp)

      case Prop.Padding(dp) =>
        gtk_widget_set_margin_top(handle, dp)
        gtk_widget_set_margin_bottom(handle, dp)
        gtk_widget_set_margin_start(handle, dp)
        gtk_widget_set_margin_end(handle, dp)

      case Prop.Enabled(v) =>
        gtk_widget_set_sensitive(handle, (if v then 1 else 0).asInstanceOf[sn.gnome.glib.internal.gboolean])

  def insertAfter(parent: Handle, child: Handle, after: Option[Handle]): Unit =
    val box = parent.asInstanceOf[Ptr[GtkBox]]
    after match
      case None    => gtk_box_prepend(box, child)
      case Some(a) => gtk_box_insert_child_after(box, child, a)

  def removeChild(parent: Handle, child: Handle): Unit =
    gtk_box_remove(parent.asInstanceOf[Ptr[GtkBox]], child)

  /** GTK can reorder in place, so override the contract's remove+insert default: a
    * detach/attach cycle would drop focus and restart any running animation.
    */
  override def moveAfter(parent: Handle, child: Handle, after: Option[Handle]): Unit =
    val box = parent.asInstanceOf[Ptr[GtkBox]]
    after match
      case None    => gtk_box_reorder_child_after(box, child, null)
      case Some(a) => gtk_box_reorder_child_after(box, child, a)

  def destroy(handle: Handle): Unit =
    tapIds.remove(handle).foreach(Handles.release)
    kinds.remove(handle)
    g_object_unref(handle.asInstanceOf[gpointer])

  def measure(handle: Handle, constraints: Constraints): Measurement =
    val minW = stackalloc[CInt]()
    val natW = stackalloc[CInt]()
    val minH = stackalloc[CInt]()
    val natH = stackalloc[CInt]()
    val forW = if constraints.maxW.isNaN then -1 else constraints.maxW.toInt
    val forH = if constraints.maxH.isNaN then -1 else constraints.maxH.toInt
    gtk_widget_measure(handle, GtkOrientation.GTK_ORIENTATION_HORIZONTAL, forH, minW, natW, null, null)
    gtk_widget_measure(handle, GtkOrientation.GTK_ORIENTATION_VERTICAL, forW, minH, natH, null, null)
    Measurement((!minW).toFloat, (!minH).toFloat, (!natW).toFloat, (!natH).toFloat)

  /** No-op: every v0 container is ToolkitManaged, so GTK positions its own children. */
  def setFrame(handle: Handle, frame: Frame): Unit = ()

  def runOnUiThread(f: () => Unit): Unit =
    val id = Handles.register(f)
    val _  = g_idle_add(GSourceFunc(Handles.idle), Handles.idToPointer(id))
