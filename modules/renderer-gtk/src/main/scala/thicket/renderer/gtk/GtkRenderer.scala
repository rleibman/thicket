package thicket.renderer.gtk

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.collection.mutable
import thicket.renderer.*
import sn.gnome.gtk4.internal.*
import sn.gnome.gobject.internal.*
import sn.gnome.glib.internal.{gchar, gpointer}
import sn.gnome.glib.internal.{g_idle_add, GSourceFunc}
import sn.gnome.gio.internal.{GAsyncReadyCallback, GCancellable, g_cancellable_cancel, g_cancellable_new}

/** GTK4 implementation of [[Renderer]]. */
final class GtkRenderer extends Renderer {
  type Handle = Ptr[GtkWidget]

  private val kinds    = mutable.Map.empty[Ptr[GtkWidget], WidgetKind]
  private val tapIds   = mutable.Map.empty[Ptr[GtkWidget], Long]
  private val editIds  = mutable.Map.empty[Ptr[GtkWidget], Long]
  private val toggleIds = mutable.Map.empty[Ptr[GtkWidget], Long]
  private val valueIds  = mutable.Map.empty[Ptr[GtkWidget], Long]

  // An Alert's state, keyed by its placeholder handle. The GtkAlertDialog itself does not
  // exist until `present`, because GtkAlertDialog is a GObject and cannot be a Handle.
  private val alertTitle   = mutable.Map.empty[Ptr[GtkWidget], String]
  private val alertDetail  = mutable.Map.empty[Ptr[GtkWidget], String]
  private val alertActions = mutable.Map.empty[Ptr[GtkWidget], Seq[AlertAction]]
  private val alertDismiss = mutable.Map.empty[Ptr[GtkWidget], () => Unit]

  /** The cancellable behind a showing alert. Cancelling it is the only way GTK offers to
    * take one down without the user choosing, so `dismiss` needs it — and it also tells the
    * response callback apart from a user dismissal, since both arrive as index -1.
    */
  private val alertCancel  = mutable.Map.empty[Ptr[GtkWidget], Ptr[GCancellable]]

  /** True while the renderer is writing a value into a widget, so the widget's own change
    * signal can tell an app-driven update from a user edit and stay silent for the former.
    * Without this, binding a signal to a text field is an infinite loop.
    */
  private val suppress = mutable.Set.empty[Ptr[GtkWidget]]
  /** Children attached through a *setter* rather than through a container's child list.
    *
    * `gtk_scrolled_window_set_child` wraps its child in an internal `GtkViewport`, and the
    * scrolled window keeps its own pointer to it. Detaching that child with the generic
    * `gtk_widget_unparent` — which is what its *actual* parent, the viewport, would want —
    * leaves the scrolled window's pointer dangling, and disposing it then unparents freed
    * memory: `gtk_widget_unparent: assertion 'GTK_IS_WIDGET (widget)' failed`.
    *
    * The rule this encodes: **a widget attached through a setter must be detached through
    * that setter.**
    */
  private val setterParent = mutable.Map.empty[Ptr[GtkWidget], Ptr[GtkWidget]]

  /** One CSS provider per themed widget.
    *
    * GTK has no per-widget colour setter, so an override becomes a tiny stylesheet scoped
    * to that widget by a generated class. Widgets the app did not override get no provider
    * at all — which is the point: they keep following the user's GTK theme.
    */
  private val cssProviders = mutable.Map.empty[Ptr[GtkWidget], Ptr[GtkCssProvider]]
  private var nextCssClass = 0

  private def applyCss(handle: Ptr[GtkWidget], declaration: String): Unit = {
    val cls = cssProviders.get(handle) match {
      case Some(_) => cssClassOf(handle)
      case None =>
        nextCssClass += 1
        val c = s"sui-$nextCssClass"
        cssClasses(handle) = c
        Zone(gtk_widget_add_css_class(handle, toCString(c)))
        c
    }
    val provider = cssProviders.getOrElseUpdate(handle, gtk_css_provider_new())
    Zone(gtk_css_provider_load_from_string(provider, toCString(s".$cls { $declaration }")))
    val display = gtk_widget_get_display(handle)
    gtk_style_context_add_provider_for_display(
      display,
      provider.asInstanceOf[Ptr[GtkStyleProvider]],
      600.toUInt.asInstanceOf[sn.gnome.glib.internal.guint] // above the theme, below user CSS
    )
  }

  private val cssClasses = mutable.Map.empty[Ptr[GtkWidget], String]
  private def cssClassOf(h: Ptr[GtkWidget]): String = cssClasses(h)

  def platform: String = "gtk4"

  /** Every GTK container in the v0 vocabulary lays out its own children. Nothing here is
    * FrameBased yet; a Yoga-driven `GtkFixed` container will be when absolute layout lands.
    */
  def layoutMode(kind: WidgetKind): LayoutMode = LayoutMode.ToolkitManaged

  private def isSwitch(handle: Handle): Boolean =
    kinds.get(handle).contains(WidgetKind.Toggle)

  /** Both text kinds are a `GtkEntry` underneath, so everything but construction is shared. */
  private def isEntryKind(handle: Handle): Boolean =
    kinds.get(handle).exists(k => k == WidgetKind.TextField || k == WidgetKind.SecureField)

  def create(kind: WidgetKind, props: Seq[Prop]): Handle = {
    val w = Zone {
      kind match {
        case WidgetKind.Column => gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)
        case WidgetKind.Row    => gtk_box_new(GtkOrientation.GTK_ORIENTATION_HORIZONTAL, 0)
        case WidgetKind.Label     => gtk_label_new(toCString(""))
        case WidgetKind.Button    => gtk_button_new_with_label(toCString(""))
        case WidgetKind.TextField => gtk_entry_new()

        // The same GtkEntry, with the characters hidden. GTK needs no separate class here
        // — AppKit does, which is why SecureField is a widget kind rather than a prop.
        case WidgetKind.SecureField =>
          val e = gtk_entry_new()
          gtk_entry_set_visibility(e.asInstanceOf[Ptr[GtkEntry]], gbool(false))
          e

        // Range is sent as a prop immediately afterwards; these are placeholder bounds so
        // the widget exists in a valid state. The step is a hundredth of the default
        // range, which is what a keyboard arrow moves.
        case WidgetKind.Slider =>
          gtk_scale_new_with_range(GtkOrientation.GTK_ORIENTATION_HORIZONTAL, 0.0, 1.0, 0.01)
        case WidgetKind.Checkbox  => gtk_check_button_new()
        case WidgetKind.Scroll =>
          val sw = gtk_scrolled_window_new()
          // Policy is the whole of the axis on GTK: a scrolled window scrolls both ways
          // by default, and NEVER on the cross axis is what makes it give its natural
          // size there instead of shrinking to nothing.
          val horizontal = props.exists {
            case Prop.Axis(Orientation.Horizontal) => true
            case _                                 => false
          }
          if horizontal then
            gtk_scrolled_window_set_policy(
              sw.asInstanceOf[Ptr[GtkScrolledWindow]],
              GtkPolicyType.GTK_POLICY_AUTOMATIC,
              GtkPolicyType.GTK_POLICY_NEVER
            )
          else
            gtk_scrolled_window_set_policy(
              sw.asInstanceOf[Ptr[GtkScrolledWindow]],
              GtkPolicyType.GTK_POLICY_NEVER,
              GtkPolicyType.GTK_POLICY_AUTOMATIC
            )
          sw
        case WidgetKind.Divider =>
          gtk_separator_new(GtkOrientation.GTK_ORIENTATION_HORIZONTAL)
        case WidgetKind.Image  => gtk_picture_new()
        case WidgetKind.Toggle => gtk_switch_new()

        // An empty box, not a label: a zero-length label still asks for a line's height,
        // which makes a Spacer in a Row quietly taller than the row needs to be.
        case WidgetKind.Spacer => gtk_box_new(GtkOrientation.GTK_ORIENTATION_HORIZONTAL, 0)

        // A placeholder widget, never shown: an Alert is presented (WidgetKind.presented)
        // and GtkAlertDialog is a GObject rather than a GtkWidget, so it cannot be a
        // handle. The dialog itself is built in `present`.
        case WidgetKind.Alert => gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)

        case WidgetKind.ProgressBar => gtk_progress_bar_new()

        case WidgetKind.ActivityIndicator =>
          val s = gtk_spinner_new()
          // GtkSpinner does not spin until it is told to, and it spins for as long as it
          // exists — which is the contract: `Show(loading)` is what stops it.
          gtk_spinner_start(s.asInstanceOf[Ptr[GtkSpinner]])
          s
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
            // An Alert's title arrives as Prop.Text on its placeholder handle.
            case Some(WidgetKind.Alert) => alertTitle(handle) = v
            case Some(WidgetKind.TextField) | Some(WidgetKind.SecureField) =>
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
        if isEntryKind(handle) then
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
        if isSwitch(handle) then {
          val sw     = handle.asInstanceOf[Ptr[GtkSwitch]]
          val active = gtk_switch_get_active(sw).asInstanceOf[CInt] != 0
          if active != v then {
            suppress += handle
            gtk_switch_set_active(sw, gbool(v))
            suppress -= handle
          }
        } else if kinds.get(handle).contains(WidgetKind.Checkbox) then {
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
            val sw = isSwitch(handle)
            Handles.bindTextSource(
              id,
              () =>
                if sw then
                  gtk_switch_get_active(handle.asInstanceOf[Ptr[GtkSwitch]]).asInstanceOf[CInt] != 0
                else
                  gtk_check_button_get_active(handle.asInstanceOf[Ptr[GtkCheckButton]])
                    .asInstanceOf[CInt] != 0,
              () => suppress.contains(handle)
            )
            Zone {
              // A check button emits "toggled"; a switch has no such signal and reports
              // through GObject's property notification instead, which carries an extra
              // GParamSpec argument and so needs the 3-argument trampoline.
              val _ = g_signal_connect_data(
                handle.asInstanceOf[gpointer],
                toCString(if sw then "notify::active" else "toggled").asInstanceOf[Ptr[gchar]],
                GCallback.fromPtr(if sw then Handles.notifiedPtr else Handles.changedPtr),
                Handles.idToPointer(id),
                null.asInstanceOf[GClosureNotify],
                GConnectFlags.define(0)
              )
            }
        }

      case Prop.Range(min, max) =>
        gtk_range_set_range(handle.asInstanceOf[Ptr[GtkRange]], min, max)

      case Prop.Value(v) =>
        val range   = handle.asInstanceOf[Ptr[GtkRange]]
        val current = gtk_range_get_value(range)
        // Same rule as the text field: writing unconditionally fights the user's drag,
        // because the app writes back the value the drag just produced.
        if current != v then {
          suppress += handle
          gtk_range_set_value(range, v)
          suppress -= handle
        }

      case Prop.OnValueChange(f) =>
        valueIds.get(handle) match {
          case Some(id) => Handles.replaceValued(id, s => f(s.toDouble))
          case None =>
            val id = Handles.registerValued(s => f(s.toDouble))
            valueIds(handle) = id
            Handles.bindTextSource(
              id,
              () => gtk_range_get_value(handle.asInstanceOf[Ptr[GtkRange]]),
              () => suppress.contains(handle)
            )
            Zone {
              val _ = g_signal_connect_data(
                handle.asInstanceOf[gpointer],
                toCString("value-changed").asInstanceOf[Ptr[gchar]],
                GCallback.fromPtr(Handles.changedPtr),
                Handles.idToPointer(id),
                null.asInstanceOf[GClosureNotify],
                GConnectFlags.define(0)
              )
            }
        }

      case Prop.Progress(value) =>
        val bar = handle.asInstanceOf[Ptr[GtkProgressBar]]
        value match {
          case Some(f) =>
            gtk_progress_bar_set_fraction(bar, math.max(0.0, math.min(1.0, f)))
          case None =>
            // GTK has no "indeterminate" flag: an indeterminate bar is one that is pulsed.
            // Pulsing once at creation shows the block; the app is expected to be doing
            // something, and a self-animating bar would need a timer per widget.
            gtk_progress_bar_pulse(bar)
        }

      case Prop.OnTap(f) =>
        // Re-tapping an existing handler swaps the closure rather than connecting a second
        // signal, so repeated property updates cannot stack handlers.
        tapIds.get(handle) match {
          case Some(id) => Handles.replace(id, f)
          case None =>
            val id = Handles.register(f)
            tapIds(handle) = id
            if kinds.get(handle).contains(WidgetKind.Button) then
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
            else {
              // Something tappable should look tappable, so the row picks up the platform's
              // own activatable styling and pointer cursor rather than us drawing anything.
              Zone {
                gtk_widget_add_css_class(handle, toCString("activatable"))
                gtk_widget_set_cursor_from_name(handle, toCString("pointer"))
              }
              // A GtkBox emits no "clicked": taps on plain containers come from an event
              // controller, which is how GTK4 does input on arbitrary widgets.
              val gesture = gtk_gesture_click_new()
              Zone {
                val _ = g_signal_connect_data(
                  gesture.asInstanceOf[gpointer],
                  toCString("released").asInstanceOf[Ptr[gchar]],
                  GCallback.fromPtr(Handles.releasedPtr),
                  Handles.idToPointer(id),
                  null.asInstanceOf[GClosureNotify],
                  GConnectFlags.define(0)
                )
              }
              gtk_widget_add_controller(handle, gesture.asInstanceOf[Ptr[GtkEventController]])
            }
        }

      case Prop.Style(role) =>
        // GTK's own type scale, via the style classes Adwaita defines, rather than a pixel
        // size chosen by us.
        Zone {
          List("title-1", "body", "caption").foreach(c =>
            gtk_widget_remove_css_class(handle, toCString(c))
          )
          val cls = role match {
            case TextRole.Title   => "title-1"
            case TextRole.Body    => "body"
            case TextRole.Caption => "caption"
          }
          gtk_widget_add_css_class(handle, toCString(cls))
        }

      case Prop.Tint(color) =>
        // `None` means "leave it to the platform" — deliberately not "use black".
        // `> *` so a button's internal GtkLabel inherits it too.
        color.foreach(c => applyCss(handle, s"color: ${hex(c)}; & > * { color: ${hex(c)}; }"))

      case Prop.Fill(color) =>
        color.foreach(c => applyCss(handle, s"background-image: none; background-color: ${hex(c)};"))

      case Prop.Picture(source) =>
        val picture = handle.asInstanceOf[Ptr[GtkPicture]]
        source match {
          case None => gtk_picture_set_paintable(picture, null)
          case Some(ImageSource.FromFile(path)) =>
            Zone(gtk_picture_set_filename(picture, toCString(path)))
          case Some(ImageSource.FromBytes(data)) =>
            Zone {
              // Decoding happens here, on the UI thread. For anything large an app should
              // decode off-thread and hand over a file; see the note in the DSL.
              val buf = alloc[Byte](data.length)
              var i   = 0
              while (i < data.length) { buf(i) = data(i); i += 1 }
              val bytes = sn.gnome.glib.internal.g_bytes_new(
                buf.asInstanceOf[sn.gnome.glib.internal.gconstpointer],
                data.length.toULong.asInstanceOf[sn.gnome.glib.internal.gsize]
              )
              val texture = sn.gnome.gdk4.internal.gdk_texture_new_from_bytes(bytes, null)
              if texture != null then
                gtk_picture_set_paintable(
                  picture,
                  texture.asInstanceOf[Ptr[sn.gnome.gdk4.internal.GdkPaintable]]
                )
            }
        }

      case Prop.Fit(fit) =>
        gtk_picture_set_content_fit(
          handle.asInstanceOf[Ptr[GtkPicture]],
          fit match {
            case ContentFit.Contain => GtkContentFit.GTK_CONTENT_FIT_CONTAIN
            case ContentFit.Cover   => GtkContentFit.GTK_CONTENT_FIT_COVER
            case ContentFit.Fill    => GtkContentFit.GTK_CONTENT_FIT_FILL
          }
        )

      case Prop.TextEmphasis(level) =>
        // "dim-label" is GTK's own secondary-foreground class, so it follows the user's
        // theme and contrast settings instead of a colour we picked.
        Zone {
          level match {
            case Emphasis.Secondary => gtk_widget_add_css_class(handle, toCString("dim-label"))
            case Emphasis.Normal    => gtk_widget_remove_css_class(handle, toCString("dim-label"))
          }
        }

      case Prop.Grow(v) =>
        gtk_widget_set_hexpand(handle, gbool(v))

      case Prop.Align(a) =>
        if kinds.get(handle).contains(WidgetKind.Label) then {
          val x = a match {
            case Alignment.Start  => 0.0f
            case Alignment.Center => 0.5f
            case Alignment.End    => 1.0f
          }
          gtk_label_set_xalign(handle.asInstanceOf[Ptr[GtkLabel]], x)
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

      // Create-only: the contract says a renderer may ignore a later axis change, and
      // re-policying a live scroller mid-scroll is worse than ignoring it.
      case Prop.Axis(_) => ()

      case Prop.Message(v)   => alertDetail(handle) = v
      case Prop.Actions(as)  => alertActions(handle) = as
      case Prop.OnDismiss(f) => alertDismiss(handle) = f
    }

  def insertAfter(parent: Handle, child: Handle, after: Option[Handle]): Unit = {
    if kinds.get(parent).contains(WidgetKind.Scroll) then {
      // A scrolled window holds one child, so "insert" is "set".
      gtk_scrolled_window_set_child(parent.asInstanceOf[Ptr[GtkScrolledWindow]], child)
      setterParent(child) = parent
      return
    }
    val box = parent.asInstanceOf[Ptr[GtkBox]]
    after match {
      case None    => gtk_box_prepend(box, child)
      case Some(a) => gtk_box_insert_child_after(box, child, a)
    }
  }

  def removeChild(parent: Handle, child: Handle): Unit =
    if kinds.get(parent).contains(WidgetKind.Scroll) then {
      gtk_scrolled_window_set_child(parent.asInstanceOf[Ptr[GtkScrolledWindow]], null)
      val _ = setterParent.remove(child)
    } else gtk_box_remove(parent.asInstanceOf[Ptr[GtkBox]], child)

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

  /** glib's `guint` and `gboolean` are *opaque* aliases of unsigned/CInt, so
    * `asInstanceOf[CInt]` on one compiles and then throws `ClassCastException` at runtime.
    * Going through the unsigned type is the only safe route.
    */
  private def toInt(u: sn.gnome.glib.internal.guint): Int =
    u.asInstanceOf[scala.scalanative.unsigned.UInt].toInt

  private def toGuint(i: Int): sn.gnome.glib.internal.guint =
    i.toUInt.asInstanceOf[sn.gnome.glib.internal.guint]

  private def hex(c: thicket.renderer.Rgb): String = f"#${c.r}%02x${c.g}%02x${c.b}%02x"

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
    val _ = cssProviders.remove(handle)
    val _ = cssClasses.remove(handle)
    setterParent.remove(handle) match {
      case Some(scroll) =>
        gtk_scrolled_window_set_child(scroll.asInstanceOf[Ptr[GtkScrolledWindow]], null)
      case None =>
        if gtk_widget_get_parent(handle) != null then gtk_widget_unparent(handle)
    }
  }

  override def present(handle: Handle): Unit = Zone {
    val actions = alertActions.getOrElse(handle, Nil)
    // An empty format, not "%s" and not null. `gtk_alert_dialog_new` is printf-style
    // variadic: "%s" declares a conversion with no argument pushed, so g_strdup_vprintf
    // reads a register that was never set — undefined behaviour that happens to work.
    // `null` is not the fix; GTK hands the format straight to g_strdup_vprintf and
    // segfaults. An empty string declares no conversions, so nothing is read, and the
    // message it produces is overwritten by set_message on the next line anyway.
    val dialog  = gtk_alert_dialog_new(toCString(""))

    gtk_alert_dialog_set_message(dialog, toCString(alertTitle.getOrElse(handle, "")))
    alertDetail.get(handle).filter(_.nonEmpty).foreach { d =>
      gtk_alert_dialog_set_detail(dialog, toCString(d))
    }

    // GTK takes the labels as one NULL-terminated array and answers with an index, so the
    // order given is the order shown — unlike Android, which has three fixed slots.
    if actions.nonEmpty then {
      val labels = alloc[CString](actions.length + 1)
      actions.zipWithIndex.foreach((a, i) => labels(i) = toCString(a.label))
      labels(actions.length) = null
      gtk_alert_dialog_set_buttons(dialog, labels)
      // Escape and the window-close button both activate the cancel button when one is
      // declared, which is why a cancel action arrives as a *choice* here and as a
      // dismissal on Android. Declaring it is what makes Escape do the expected thing.
      actions.indexWhere(_.cancel) match {
        case -1 => ()
        case i  => gtk_alert_dialog_set_cancel_button(dialog, i)
      }
    }

    val cancellable = g_cancellable_new()
    alertCancel(handle) = cancellable

    val dismissed = alertDismiss.get(handle)
    val id = Handles.registerChosen { index =>
      // -1 is "no choice": either our own `dismiss` cancelled it, or GTK closed it without
      // a cancel button to activate. The first is not the app's business; the second is
      // exactly what OnDismiss is for.
      if index >= 0 && index < actions.length then actions(index).onSelect()
      else if alertCancel.contains(handle) then dismissed.foreach(_())
    }

    gtk_alert_dialog_choose(
      dialog,
      GtkApp.window,
      cancellable,
      GAsyncReadyCallback.fromPtr(Handles.alertChosenPtr),
      Handles.idToPointer(id)
    )
  }

  override def dismiss(handle: Handle): Unit = {
    // Removing it first is what tells the response callback this was us rather than the
    // user, so OnDismiss does not fire for a dismissal the app itself asked for.
    alertCancel.remove(handle).foreach(g_cancellable_cancel)
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

  /** GTK4's `GtkListView` materialises only the rows on screen, the same as Android's
    * `ListView`. This is `RowSource`'s second implementation, and the contract needed no
    * changes to accommodate it — which is the first evidence it is an abstraction rather
    * than a description of Android.
    */
  override def supportsVirtualRows: Boolean = true

  override def createVirtualList(source: RowSource[Ptr[GtkWidget]]): Ptr[GtkWidget] = {
    // GtkListView wants a GListModel. A GtkStringList of placeholders is the cheapest one
    // available from these bindings: it gives the view a row count, and the strings are
    // never displayed — the factory below supplies the actual widgets.
    val model = gtk_string_list_new(null)

    val factory = gtk_signal_list_item_factory_new()
    val binderId = Handles.registerListBinder { item =>
      val listItem = item.asInstanceOf[Ptr[GtkListItem]]
      val position = toInt(gtk_list_item_get_position(listItem))
      if position >= 0 && position < source.count then {
        // Whatever this list item last held is the recycled handle.
        val recycled = Option(gtk_list_item_get_child(listItem)).filter(_ != null)
        val child    = source.bind(position, recycled)
        gtk_list_item_set_child(listItem, child)
      }
    }
    Zone {
      val _ = g_signal_connect_data(
        factory.asInstanceOf[gpointer],
        toCString("bind").asInstanceOf[Ptr[gchar]],
        GCallback.fromPtr(Handles.listBindPtr),
        Handles.idToPointer(binderId),
        null.asInstanceOf[GClosureNotify],
        GConnectFlags.define(0)
      )
    }

    val selection = gtk_no_selection_new(model.asInstanceOf[Ptr[sn.gnome.gio.internal.GListModel]])
    val view = gtk_list_view_new(selection.asInstanceOf[Ptr[GtkSelectionModel]], factory)
    gtk_list_view_set_show_separators(view.asInstanceOf[Ptr[GtkListView]], gbool(true))

    // A list only virtualises inside something that scrolls.
    val scroller = gtk_scrolled_window_new()
    gtk_scrolled_window_set_policy(
      scroller.asInstanceOf[Ptr[GtkScrolledWindow]],
      GtkPolicyType.GTK_POLICY_NEVER,
      GtkPolicyType.GTK_POLICY_AUTOMATIC
    )
    gtk_scrolled_window_set_child(scroller.asInstanceOf[Ptr[GtkScrolledWindow]], view)
    gtk_widget_set_vexpand(scroller, gbool(true))
    kinds(scroller) = WidgetKind.Scroll
    setterParent(view) = scroller

    virtualModels(scroller) = model
    syncModel(model, source.count)
    source.onInvalidate(() => syncModel(model, source.count))
    scroller
  }

  private val virtualModels = mutable.Map.empty[Ptr[GtkWidget], Ptr[GtkStringList]]

  /** Make the model's length match the data. The strings are placeholders; only the count
    * matters, because the factory supplies every widget.
    */
  private def syncModel(model: Ptr[GtkStringList], count: Int): Unit = {
    val current = toInt(
      sn.gnome.gio.internal.g_list_model_get_n_items(
        model.asInstanceOf[Ptr[sn.gnome.gio.internal.GListModel]]
      )
    )
    if count > current then
      Zone {
        val additions = alloc[CString](count - current + 1)
        var i         = 0
        while (i < count - current) { additions(i) = toCString(""); i += 1 }
        additions(count - current) = null
        gtk_string_list_splice(model, toGuint(current), toGuint(0), additions)
      }
    else if count < current then
      gtk_string_list_splice(model, toGuint(count), toGuint(current - count), null)
  }

  def runOnUiThread(f: () => Unit): Unit = {
    val id = Handles.register(f)
    val _  = g_idle_add(GSourceFunc(Handles.idle), Handles.idToPointer(id))
  }
}
