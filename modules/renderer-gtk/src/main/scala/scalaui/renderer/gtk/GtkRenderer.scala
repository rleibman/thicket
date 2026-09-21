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
final class GtkRenderer extends Renderer {
  type Handle = Ptr[GtkWidget]

  private val kinds    = mutable.Map.empty[Ptr[GtkWidget], WidgetKind]
  private val tapIds   = mutable.Map.empty[Ptr[GtkWidget], Long]
  private val editIds  = mutable.Map.empty[Ptr[GtkWidget], Long]
  private val toggleIds = mutable.Map.empty[Ptr[GtkWidget], Long]

  /** True while the renderer is writing a value into a widget, so the widget's own change
    * signal can tell an app-driven update from a user edit and stay silent for the former.
    * Without this, binding a signal to a text field is an infinite loop.
    */
  private val suppress = mutable.Set.empty[Ptr[GtkWidget]]

  def platform: String = "gtk4"

  /** Every GTK container in the v0 vocabulary lays out its own children. Nothing here is
    * FrameBased yet; a Yoga-driven `GtkFixed` container will be when absolute layout lands.
    */
  def layoutMode(kind: WidgetKind): LayoutMode = LayoutMode.ToolkitManaged

  def create(kind: WidgetKind, props: Seq[Prop]): Handle = {
    val w = Zone {
      kind match {
        case WidgetKind.Column => gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)
        case WidgetKind.Row    => gtk_box_new(GtkOrientation.GTK_ORIENTATION_HORIZONTAL, 0)
        case WidgetKind.Label     => gtk_label_new(toCString(""))
        case WidgetKind.Button    => gtk_button_new_with_label(toCString(""))
        case WidgetKind.TextField => gtk_entry_new()
        case WidgetKind.Checkbox  => gtk_check_button_new()
        case WidgetKind.Scroll    => gtk_scrolled_window_new()
      }
    }
    kinds(w) = kind
    update(w, props)
    w
  }

  def update(handle: Handle, patch: Seq[Prop]): Unit =
    patch.foreach {
      case Prop.Text(v) =>
        Zone {
          kinds.get(handle) match {
            case Some(WidgetKind.Label) =>
              gtk_label_set_text(handle.asInstanceOf[Ptr[GtkLabel]], toCString(v))
            case Some(WidgetKind.Button) =>
              gtk_button_set_label(handle.asInstanceOf[Ptr[GtkButton]], toCString(v))
            case Some(WidgetKind.Checkbox) =>
              gtk_check_button_set_label(handle.asInstanceOf[Ptr[GtkCheckButton]], toCString(v))
            case Some(WidgetKind.TextField) =>
              // Only write when the value actually differs. Setting the text unconditionally
              // would move the caret to the end on every keystroke, because the app writes
              // back what the user just typed.
              val editable = handle.asInstanceOf[Ptr[GtkEditable]]
              val current  = gtk_editable_get_text(editable)
              val existing = if current == null then "" else fromCString(current)
              if existing != v then {
                suppress += handle
                gtk_editable_set_text(editable, toCString(v))
                suppress -= handle
              }
            case _ => ()
          }
        }

      case Prop.Placeholder(v) =>
        if kinds.get(handle).contains(WidgetKind.TextField) then
          Zone(gtk_entry_set_placeholder_text(handle.asInstanceOf[Ptr[GtkEntry]], toCString(v)))

      case Prop.OnTextChange(f) =>
        editIds.get(handle) match {
          case Some(id) => Handles.replaceValued(id, s => f(s))
          case None =>
            val id = Handles.registerValued(s => f(s))
            editIds(handle) = id
            Handles.bindTextSource(id, () => readText(handle), () => suppress.contains(handle))
            Zone {
              val _ = g_signal_connect_data(
                handle.asInstanceOf[gpointer],
                toCString("changed").asInstanceOf[Ptr[gchar]],
                GCallback.fromPtr(Handles.changedPtr),
                Handles.idToPointer(id),
                null.asInstanceOf[GClosureNotify],
                GConnectFlags.define(0)
              )
            }
        }

      case Prop.Checked(v) =>
        if kinds.get(handle).contains(WidgetKind.Checkbox) then {
          val button = handle.asInstanceOf[Ptr[GtkCheckButton]]
          val active = gtk_check_button_get_active(button).asInstanceOf[CInt] != 0
          if active != v then {
            suppress += handle
            gtk_check_button_set_active(button, gbool(v))
            suppress -= handle
          }
        }

      case Prop.OnCheckedChange(f) =>
        toggleIds.get(handle) match {
          case Some(id) => Handles.replaceValued(id, s => f(s == "true"))
          case None =>
            val id = Handles.registerValued(s => f(s == "true"))
            toggleIds(handle) = id
            Handles.bindTextSource(
              id,
              () => gtk_check_button_get_active(handle.asInstanceOf[Ptr[GtkCheckButton]]).asInstanceOf[CInt] != 0,
              () => suppress.contains(handle)
            )
            Zone {
              val _ = g_signal_connect_data(
                handle.asInstanceOf[gpointer],
                toCString("toggled").asInstanceOf[Ptr[gchar]],
                GCallback.fromPtr(Handles.changedPtr),
                Handles.idToPointer(id),
                null.asInstanceOf[GClosureNotify],
                GConnectFlags.define(0)
              )
            }
        }

      case Prop.OnTap(f) =>
        // Re-tapping an existing handler swaps the closure rather than connecting a second
        // signal, so repeated property updates cannot stack handlers.
        tapIds.get(handle) match {
          case Some(id) => Handles.replace(id, f)
          case None =>
            val id = Handles.register(f)
            tapIds(handle) = id
            Zone {
              val _ = g_signal_connect_data(
                handle.asInstanceOf[gpointer],
                toCString("clicked").asInstanceOf[Ptr[gchar]],
                GCallback.fromPtr(Handles.clickedPtr),
                Handles.idToPointer(id),
                null.asInstanceOf[GClosureNotify],
                GConnectFlags.define(0)
              )
            }
        }

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
    }

  def insertAfter(parent: Handle, child: Handle, after: Option[Handle]): Unit = {
    if kinds.get(parent).contains(WidgetKind.Scroll) then {
      // A scrolled window holds one child, so "insert" is "set".
      gtk_scrolled_window_set_child(parent.asInstanceOf[Ptr[GtkScrolledWindow]], child)
      return
    }
    val box = parent.asInstanceOf[Ptr[GtkBox]]
    after match {
      case None    => gtk_box_prepend(box, child)
      case Some(a) => gtk_box_insert_child_after(box, child, a)
    }
  }

  def removeChild(parent: Handle, child: Handle): Unit =
    if kinds.get(parent).contains(WidgetKind.Scroll) then
      gtk_scrolled_window_set_child(parent.asInstanceOf[Ptr[GtkScrolledWindow]], null)
    else gtk_box_remove(parent.asInstanceOf[Ptr[GtkBox]], child)

  /** GTK can reorder in place, so override the contract's remove+insert default: a
    * detach/attach cycle would drop focus and restart any running animation.
    */
  override def moveAfter(parent: Handle, child: Handle, after: Option[Handle]): Unit = {
    val box = parent.asInstanceOf[Ptr[GtkBox]]
    after match {
      case None    => gtk_box_reorder_child_after(box, child, null)
      case Some(a) => gtk_box_reorder_child_after(box, child, a)
    }
  }

  private def readText(handle: Handle): String = {
    val t = gtk_editable_get_text(handle.asInstanceOf[Ptr[GtkEditable]])
    if t == null then "" else fromCString(t)
  }

  private def gbool(b: Boolean): sn.gnome.glib.internal.gboolean =
    (if b then 1 else 0).asInstanceOf[sn.gnome.glib.internal.gboolean]

  def destroy(handle: Handle): Unit = {
    tapIds.remove(handle).foreach(Handles.release)
    editIds.remove(handle).foreach(Handles.release)
    toggleIds.remove(handle).foreach(Handles.release)
    suppress -= handle
    kinds.remove(handle)
    // GTK4: a widget is owned by its parent, and unparenting drops that reference, which
    // frees it. `g_object_unref` here is wrong — GTK says so out loud: "has a parent GtkBox
    // during dispose... Did you call g_object_unref() instead of gtk_widget_unparent()?".
    if gtk_widget_get_parent(handle) != null then gtk_widget_unparent(handle)
  }

  def measure(handle: Handle, constraints: Constraints): Measurement = {
    val minW = stackalloc[CInt]()
    val natW = stackalloc[CInt]()
    val minH = stackalloc[CInt]()
    val natH = stackalloc[CInt]()
    val forW = if constraints.maxW.isNaN then -1 else constraints.maxW.toInt
    val forH = if constraints.maxH.isNaN then -1 else constraints.maxH.toInt
    gtk_widget_measure(handle, GtkOrientation.GTK_ORIENTATION_HORIZONTAL, forH, minW, natW, null, null)
    gtk_widget_measure(handle, GtkOrientation.GTK_ORIENTATION_VERTICAL, forW, minH, natH, null, null)
    Measurement((!minW).toFloat, (!minH).toFloat, (!natW).toFloat, (!natH).toFloat)
  }

  /** No-op: every v0 container is ToolkitManaged, so GTK positions its own children. */
  def setFrame(handle: Handle, frame: Frame): Unit = ()

  def runOnUiThread(f: () => Unit): Unit = {
    val id = Handles.register(f)
    val _  = g_idle_add(GSourceFunc(Handles.idle), Handles.idToPointer(id))
  }
}
