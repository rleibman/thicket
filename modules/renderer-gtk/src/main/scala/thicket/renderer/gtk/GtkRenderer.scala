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
  private val selectIds = mutable.Map.empty[Ptr[GtkWidget], Long]
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

  /** The modal window carrying a showing sheet, and the close-request callback id. */
  private val sheetWindow  = mutable.Map.empty[Ptr[GtkWidget], Ptr[GtkWindow]]
  private val sheetCloseId = mutable.Map.empty[Ptr[GtkWidget], Long]

  /** A widget's context-menu popover and the callback ids behind its items, so `destroy`
    * can unparent the popover and release them rather than leaking one per menu.
    */
  private val menuPopover = mutable.Map.empty[Ptr[GtkWidget], Ptr[GtkWidget]]
  private val menuIds     = mutable.Map.empty[Ptr[GtkWidget], mutable.ArrayBuffer[Long]]

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

  /** Attach a stylesheet to one widget, and optionally to its direct children.
    *
    * `childDeclaration` is a *separate flat rule*, not CSS nesting. GTK4's parser has no `&`
    * selector: the previous version emitted `.sui-1 { color: X; & > * { color: X; } }` and
    * GTK answered
    *
    * {{{
    * Gtk-WARNING: Theme parser error: <data>:1:26-27: Expected an identifier
    * }}}
    *
    * — column 26 being the `&`. It then abandoned the rest of the block, so the child rule
    * never applied and a tinted container's children kept the platform's colour. It had been
    * doing that since theming landed, 26 times per run of the demo, and nothing noticed
    * because the self-tests grep for `Gtk-CRITICAL` while this is a `Gtk-WARNING`.
    */
  private def applyCss(
    handle:           Ptr[GtkWidget],
    declaration:      String,
    childDeclaration: String = ""
  ): Unit = {
    val cls = cssProviders.get(handle) match {
      case Some(_) => cssClassOf(handle)
      case None =>
        nextCssClass += 1
        val c = s"sui-$nextCssClass"
        cssClasses(handle) = c
        Zone(gtk_widget_add_css_class(handle, toCString(c)))
        c
    }
    val provider = cssProviders.getOrElseUpdate(
      handle, {
        val fresh = gtk_css_provider_new()
        // Tell us when GTK rejects what we wrote. Connected once per provider, at creation,
        // because a stylesheet this renderer generates is always a bug if it does not parse
        // — and GTK's own response is a stderr warning nobody reads.
        Zone {
          val _ = g_signal_connect_data(
            fresh.asInstanceOf[gpointer],
            toCString("parsing-error").asInstanceOf[Ptr[gchar]],
            GCallback.fromPtr(Handles.cssParsingErrorPtr),
            Handles.idToPointer(0L),
            null.asInstanceOf[GClosureNotify],
            GConnectFlags.define(0)
          )
        }
        fresh
      }
    )
    val sheet =
      if childDeclaration.isEmpty then s".$cls { $declaration }"
      else s".$cls { $declaration } .$cls > * { $childDeclaration }"
    Zone(gtk_css_provider_load_from_string(provider, toCString(sheet)))
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
        case WidgetKind.Checkbox => gtk_check_button_new()

        // A GtkOverlay with no main child. Its main child is the one widget that sizes the
        // overlay, and a ZStack has no such child — it is as large as the largest of them —
        // so every child is an overlay with `measure` set, which is GTK's own way of saying
        // "count this one when sizing".
        case WidgetKind.ZStack => gtk_overlay_new()
        // A row of grouped GtkToggleButtons in a box with the `linked` class: the segmented
        // control GNOME apps drew before AdwToggleGroup, and still the one any GTK 4 has.
        // Not AdwToggleGroup: the pinned bindings predate it, and linking its symbols would
        // stop every thicket GTK app starting on libadwaita < 1.7 (Ubuntu 24.04 ships 1.5).
        // GNOME's date field: a menu button showing the date, whose popover holds a
        // GtkCalendar. GTK has no compact date picker of its own, and an inline calendar is
        // not what a form puts in a row.
        case WidgetKind.DatePicker =>
          val button   = gtk_menu_button_new()
          val calendar = gtk_calendar_new()
          val popover  = gtk_popover_new()
          gtk_popover_set_child(popover.asInstanceOf[Ptr[GtkPopover]], calendar)
          gtk_menu_button_set_popover(button.asInstanceOf[Ptr[GtkMenuButton]], popover)
          calendars(button) = calendar
          button
        case WidgetKind.SegmentedControl =>
          val box = gtk_box_new(GtkOrientation.GTK_ORIENTATION_HORIZONTAL, 0)
          gtk_widget_add_css_class(box, toCString("linked"))
          box
        case WidgetKind.Picker =>
          // A GtkStringList model rather than gtk_drop_down_new_from_strings: the options are
          // a prop and can change, and a model can be refilled in place where a
          // NULL-terminated array has to be rebuilt and re-handed every time.
          val model = gtk_string_list_new(null)
          gtk_drop_down_new(model.asInstanceOf[Ptr[sn.gnome.gio.internal.GListModel]], null)
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
        // The sheet's *content* container. The modal window that carries it does not
        // exist until `present`, the same split Alert uses.
        case WidgetKind.Sheet => gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)

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
            case Some(WidgetKind.Alert) | Some(WidgetKind.Sheet) => alertTitle(handle) = v
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

      case Prop.SafeArea(_) =>
        // Nothing to do, and that is the correct answer rather than a gap. GTK4 has no
        // safe-area concept because a desktop window has no notch, no status bar over it and
        // no home indicator: its safe area *is* the whole window, so the right inset is zero.
        // Contrast a control GTK genuinely lacks, where the honest options are to imitate it
        // or to decline — see §12.2a.
        ()

      case Prop.DateValue(date) => showDate(handle, date)

      case Prop.OnDateChange(f) =>
        dateChanged(handle) = f
        if !dateIds.contains(handle) then
          calendars.get(handle).foreach { calendar =>
            val id = Handles.register(() => dateChosen(handle, calendar))
            dateIds(handle) = id
            Zone {
              // (GtkCalendar*, gpointer), the same shape as "clicked".
              val _ = g_signal_connect_data(
                calendar.asInstanceOf[gpointer],
                toCString("day-selected").asInstanceOf[Ptr[gchar]],
                GCallback.fromPtr(Handles.clickedPtr),
                Handles.idToPointer(id),
                null.asInstanceOf[GClosureNotify],
                GConnectFlags.define(0)
              )
            }
          }

      case Prop.Options(values) if isSegmented(handle) => setSegments(handle, values)
      case Prop.Selected(index) if isSegmented(handle)  => selectSegment(handle, index)
      case Prop.OnSelect(f) if isSegmented(handle)      => segmentChosen(handle) = f

      case Prop.Options(values) =>
        // Refill the model in place. GtkStringList has no "clear", so splice removes the old
        // range and inserts the new one in a single step; a remove-all-then-append loop
        // would emit a change per item and make the drop-down flicker.
        val model = gtk_drop_down_get_model(handle.asInstanceOf[Ptr[GtkDropDown]])
        val list = model.asInstanceOf[Ptr[GtkStringList]]
        val existing = toInt(sn.gnome.gio.internal.g_list_model_get_n_items(model))
        Zone {
          // NULL-terminated, which is what splice wants for "the strings to insert".
          val arr = alloc[CString]((values.length + 1).toUInt)
          values.zipWithIndex.foreach { (s, i) => arr(i) = toCString(s) }
          arr(values.length) = null
          gtk_string_list_splice(list, toGuint(0), toGuint(existing), arr)
        }

      case Prop.Selected(index) =>
        // GTK_INVALID_LIST_POSITION for "nothing selected", which is unsigned -1 rather than
        // a negative index: handing it a raw -1 would select item 4294967295 and crash.
        val pos = if index < 0 then toGuint(-1) else toGuint(index)
        val dd = handle.asInstanceOf[Ptr[GtkDropDown]]
        // Guarded like the switch and the check button: writing the selection fires
        // notify::selected, which would report the app's own write back to it as a user
        // choice and, with a Var behind it, loop.
        if toInt(gtk_drop_down_get_selected(dd)) != index then {
          suppress += handle
          gtk_drop_down_set_selected(dd, pos)
          suppress -= handle
        }

      case Prop.StackAlignment(h, v) =>
        stackAlignment(handle) = (gtkAlign(h), gtkAlign(v))
        var c = gtk_widget_get_first_child(handle)
        while c != null do {
          alignInStack(handle, c)
          c = gtk_widget_get_next_sibling(c)
        }

      case Prop.OnSelect(f) =>
        selectIds.get(handle) match {
          case Some(id) => Handles.replaceValued(id, s => f(s.toInt))
          case None =>
            val id = Handles.registerValued(s => f(s.toInt))
            selectIds(handle) = id
            Handles.bindTextSource(
              id,
              () => toInt(gtk_drop_down_get_selected(handle.asInstanceOf[Ptr[GtkDropDown]])),
              () => suppress.contains(handle)
            )
            Zone {
              // Like GtkSwitch, a drop-down reports through GObject property notification
              // rather than a signal of its own, so this needs the 3-argument trampoline.
              val _ = g_signal_connect_data(
                handle.asInstanceOf[gpointer],
                toCString("notify::selected").asInstanceOf[Ptr[gchar]],
                GCallback.fromPtr(Handles.notifiedPtr),
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
        appTaps(handle) = f
        connectTap(handle)

      // GtkLinkButton is a GtkButton with the `link` style class and a URI to launch, so a
      // Button that carries a URL *is* one, drawn by the theme exactly as GTK's own is. On
      // anything else the URL is behaviour only, like OnTap.
      case Prop.OpenUrl(url) =>
        links(handle) = url
        if kinds.get(handle).contains(WidgetKind.Button) then
          Zone(gtk_widget_add_css_class(handle, toCString("link")))
        connectTap(handle)

      case Prop.ContextMenu(items) =>
        // A GtkPopover of buttons, not a GtkPopoverMenu. The latter is the more "menu-ish"
        // widget, but it is driven by a GMenuModel whose items address GActions by *name*
        // through an action group — an indirection that does not fit an API where each item
        // carries its own closure. A popover with the `menu` style class gets Adwaita's
        // menu appearance without inventing an action-name namespace. Worth revisiting if
        // keyboard navigation or accessibility differs in practice.
        val popover = gtk_popover_new()
        val box     = gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)
        Zone {
          gtk_widget_add_css_class(popover, toCString("menu"))
          gtk_popover_set_has_arrow(popover.asInstanceOf[Ptr[GtkPopover]], gbool(false))
        }

        items.foreach { item =>
          val button = Zone(gtk_button_new_with_label(toCString(item.label)))
          Zone(gtk_widget_add_css_class(button, toCString("flat")))
          gtk_widget_set_sensitive(button, gbool(item.enabled))
          val itemId = Handles.register { () =>
            gtk_popover_popdown(popover.asInstanceOf[Ptr[GtkPopover]])
            item.onSelect()
          }
          menuIds.getOrElseUpdate(handle, mutable.ArrayBuffer.empty) += itemId
          Zone {
            val _ = g_signal_connect_data(
              button.asInstanceOf[gpointer],
              toCString("clicked").asInstanceOf[Ptr[gchar]],
              GCallback.fromPtr(Handles.clickedPtr),
              Handles.idToPointer(itemId),
              null.asInstanceOf[GClosureNotify],
              GConnectFlags.define(0)
            )
          }
          gtk_box_append(box.asInstanceOf[Ptr[GtkBox]], button)
        }

        gtk_popover_set_child(popover.asInstanceOf[Ptr[GtkPopover]], box)
        gtk_widget_set_parent(popover, handle)
        menuPopover(handle) = popover

        // Secondary click, which is the desktop gesture — Android uses long press. Button 3
        // rather than any button, or an ordinary left click would open the menu too.
        val gesture = gtk_gesture_click_new()
        gtk_gesture_single_set_button(gesture.asInstanceOf[Ptr[GtkGestureSingle]], toGuint(3))
        val openId = Handles.register(() => gtk_popover_popup(popover.asInstanceOf[Ptr[GtkPopover]]))
        menuIds.getOrElseUpdate(handle, mutable.ArrayBuffer.empty) += openId
        Zone {
          val _ = g_signal_connect_data(
            gesture.asInstanceOf[gpointer],
            toCString("pressed").asInstanceOf[Ptr[gchar]],
            GCallback.fromPtr(Handles.releasedPtr),
            Handles.idToPointer(openId),
            null.asInstanceOf[GClosureNotify],
            GConnectFlags.define(0)
          )
        }
        gtk_widget_add_controller(handle, gesture.asInstanceOf[Ptr[GtkEventController]])

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
        // The second rule is for the children, so a button's internal GtkLabel inherits it;
        // it is passed separately because GTK4 cannot parse a nested `&` selector.
        color.foreach(c => applyCss(handle, s"color: ${hex(c)};", s"color: ${hex(c)};"))

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

  /** Each ZStack's alignment, so a child inserted later is placed like the ones already there. */
  private val stackAlignment = mutable.Map.empty[Ptr[GtkWidget], (GtkAlign, GtkAlign)]

  private def gtkAlign(a: Alignment): GtkAlign =
    a match {
      // START and END, not LEFT and RIGHT: GTK flips them under a right-to-left locale,
      // which is what Start and End mean everywhere else in the contract.
      case Alignment.Start  => GtkAlign.GTK_ALIGN_START
      case Alignment.Center => GtkAlign.GTK_ALIGN_CENTER
      case Alignment.End    => GtkAlign.GTK_ALIGN_END
    }

  /** A GtkOverlay places an overlay child by that child's own halign/valign, so the stack's alignment is written onto
    * each child. Nothing else in this renderer sets either, so there is nothing of the child's to overwrite.
    */
  private def alignInStack(stack: Handle, child: Handle): Unit =
    stackAlignment.get(stack).foreach { (h, v) =>
      gtk_widget_set_halign(child, h)
      gtk_widget_set_valign(child, v)
    }

  // -- DatePicker ------------------------------------------------------------

  private val calendars   = mutable.Map.empty[Ptr[GtkWidget], Ptr[GtkWidget]]
  private val dateIds     = mutable.Map.empty[Ptr[GtkWidget], Long]
  private val dateChanged = mutable.Map.empty[Ptr[GtkWidget], CalendarDate => Unit]

  private def showDate(button: Handle, date: CalendarDate): Unit =
    calendars.get(button).foreach { c =>
      val calendar = c.asInstanceOf[Ptr[GtkCalendar]]
      // Day 1 first: moving from the 31st to a 30-day month would otherwise pass through a
      // day that does not exist. GtkCalendar's month counts from 0.
      suppress += button
      gtk_calendar_set_day(calendar, 1)
      gtk_calendar_set_year(calendar, date.year)
      gtk_calendar_set_month(calendar, date.month - 1)
      gtk_calendar_set_day(calendar, date.day)
      suppress -= button
      Zone(gtk_menu_button_set_label(button.asInstanceOf[Ptr[GtkMenuButton]], toCString(formatDate(date))))
    }

  /** The locale's own date format (`%x`), from glib, so the button reads as dates do on this desktop. At midnight UTC
    * on a UTC date-time: the date is a calendar day, and no zone may move it.
    */
  private def formatDate(date: CalendarDate): String = Zone {
    import sn.gnome.glib.internal.{gdouble, gint}
    val dt = sn.gnome.glib.internal.g_date_time_new_utc(gint(date.year), gint(date.month), gint(date.day), gint(0), gint(0), gdouble(0.0))
    val f  = sn.gnome.glib.internal.g_date_time_format(dt, toCString("%x").asInstanceOf[Ptr[gchar]])
    sn.gnome.glib.internal.g_date_time_unref(dt)
    val out = fromCString(f.asInstanceOf[CString])
    sn.gnome.glib.internal.g_free(f.asInstanceOf[gpointer])
    out
  }

  /** "day-selected" also fires when the app moves the calendar, so the suppress guard is what keeps an app's write from
    * being reported back as a choice.
    */
  private def dateChosen(button: Handle, c: Ptr[GtkWidget]): Unit =
    if !suppress.contains(button) then {
      val calendar = c.asInstanceOf[Ptr[GtkCalendar]]
      val chosen = CalendarDate(
        gtk_calendar_get_year(calendar),
        gtk_calendar_get_month(calendar) + 1,
        gtk_calendar_get_day(calendar)
      )
      Zone(gtk_menu_button_set_label(button.asInstanceOf[Ptr[GtkMenuButton]], toCString(formatDate(chosen))))
      dateChanged.get(button).foreach(_(chosen))
    }

  // -- SegmentedControl ------------------------------------------------------
  //
  // The selection lives here, not in the buttons: the buttons are rebuilt whenever the
  // options change, and the index has to survive that. Each button's "toggled" handler is
  // its own Handles id, released when the buttons are replaced and when the control goes.

  private val segments      = mutable.Map.empty[Ptr[GtkWidget], Vector[(Ptr[GtkWidget], Long)]]
  private val segmentIndex  = mutable.Map.empty[Ptr[GtkWidget], Int]
  private val segmentChosen = mutable.Map.empty[Ptr[GtkWidget], Int => Unit]

  private def isSegmented(handle: Handle): Boolean = kinds.get(handle).contains(WidgetKind.SegmentedControl)

  private def setSegments(control: Handle, values: Seq[String]): Unit = {
    releaseSegments(control)
    val built = values.zipWithIndex.map { (label, i) =>
      val b = Zone(gtk_toggle_button_new_with_label(toCString(label)))
      val toggle = b.asInstanceOf[Ptr[GtkToggleButton]]
      // Grouped to the first, which is what makes them mutually exclusive: GTK deactivates
      // the old one when a new one is pressed.
      val id = Handles.register { () =>
        // "toggled" fires for the button going off as well as the one coming on; only the
        // one coming on is a choice, and only when the user made it.
        if gtk_toggle_button_get_active(toggle).asInstanceOf[CInt] != 0 && !suppress.contains(control) then {
          segmentIndex(control) = i
          segmentChosen.get(control).foreach(_(i))
        }
      }
      Zone {
        val _ = g_signal_connect_data(
          b.asInstanceOf[gpointer],
          toCString("toggled").asInstanceOf[Ptr[gchar]],
          GCallback.fromPtr(Handles.clickedPtr),
          Handles.idToPointer(id),
          null.asInstanceOf[GClosureNotify],
          GConnectFlags.define(0)
        )
      }
      (b, id)
    }.toVector
    built.headOption.foreach { (first, _) =>
      built.drop(1).foreach { (b, _) =>
        gtk_toggle_button_set_group(b.asInstanceOf[Ptr[GtkToggleButton]], first.asInstanceOf[Ptr[GtkToggleButton]])
      }
    }
    built.foreach((b, _) => gtk_box_append(control.asInstanceOf[Ptr[GtkBox]], b))
    segments(control) = built
    // The index survives a change of options; re-applying it is what keeps "Week" chosen
    // when the list is refilled, or clears it when the list got shorter.
    selectSegment(control, segmentIndex.getOrElse(control, -1))
  }

  private def selectSegment(control: Handle, index: Int): Unit = {
    val buttons = segments.getOrElse(control, Vector.empty)
    segmentIndex(control) = index
    // Writing `active` fires "toggled", which would report the app's own write back to it
    // as a user choice — and with a Var behind it, loop. Guarded as the switch is.
    suppress += control
    if index >= 0 && index < buttons.length then
      gtk_toggle_button_set_active(buttons(index)._1.asInstanceOf[Ptr[GtkToggleButton]], gbool(true))
    else
      // -1, or past the end: nothing chosen. A GTK toggle group allows none active.
      buttons.foreach((b, _) => gtk_toggle_button_set_active(b.asInstanceOf[Ptr[GtkToggleButton]], gbool(false)))
    suppress -= control
  }

  /** Disconnect nothing — the buttons are freed with the box — but release each button's handler, which the table
    * would otherwise keep, with its closure, forever.
    */
  private def releaseSegments(control: Handle): Unit =
    segments.remove(control).foreach(_.foreach { (b, id) =>
      Handles.release(id)
      gtk_box_remove(control.asInstanceOf[Ptr[GtkBox]], b)
    })

  /** What a tap on each widget does: the app's own handler and, separately, the URL it opens. Kept apart and dispatched
    * from one connection, so `.link(url)` on a row that is also tappable runs both instead of whichever was applied last.
    */
  private val appTaps = mutable.Map.empty[Ptr[GtkWidget], () => Unit]
  private val links   = mutable.Map.empty[Ptr[GtkWidget], String]

  private def fireTap(handle: Handle): Unit = {
    appTaps.get(handle).foreach(_())
    links.get(handle).foreach(url => UrlOpener.open(url, handle))
  }

  /** Connect the platform's tap signal once per widget. Re-applying a prop changes what the maps hold, never how many
    * handlers are connected, so repeated property updates cannot stack them.
    */
  private def connectTap(handle: Handle): Unit =
    if !tapIds.contains(handle) then {
      val id = Handles.register(() => fireTap(handle))
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

  def insertAfter(parent: Handle, child: Handle, after: Option[Handle]): Unit = {
    if kinds.get(parent).contains(WidgetKind.ZStack) then {
      val overlay = parent.asInstanceOf[Ptr[GtkOverlay]]
      gtk_overlay_add_overlay(overlay, child)
      gtk_overlay_set_measure_overlay(overlay, child, gbool(true))
      // add_overlay always appends, i.e. on top. Paint order is child order, so a child that
      // belongs underneath — an underlay shown after the content — is moved to its place.
      gtk_widget_insert_after(child, parent, after.orNull)
      alignInStack(parent, child)
      return
    }
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
    if kinds.get(parent).contains(WidgetKind.ZStack) then
      gtk_overlay_remove_overlay(parent.asInstanceOf[Ptr[GtkOverlay]], child)
    else if kinds.get(parent).contains(WidgetKind.Scroll) then {
      gtk_scrolled_window_set_child(parent.asInstanceOf[Ptr[GtkScrolledWindow]], null)
      val _ = setterParent.remove(child)
    } else gtk_box_remove(parent.asInstanceOf[Ptr[GtkBox]], child)

  /** GTK can reorder in place, so override the contract's remove+insert default: a
    * detach/attach cycle would drop focus and restart any running animation.
    */
  override def moveAfter(parent: Handle, child: Handle, after: Option[Handle]): Unit = {
    // In a ZStack this is a change of paint order, and the widget's sibling position is all
    // GTK uses for that — so it is a reorder, the same as in a box.
    if kinds.get(parent).contains(WidgetKind.ZStack) then {
      gtk_widget_insert_after(child, parent, after.orNull)
      return
    }
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
    val _ = appTaps.remove(handle)
    val _ = links.remove(handle)
    editIds.remove(handle).foreach(Handles.release)
    toggleIds.remove(handle).foreach(Handles.release)
    // `valueIds` was missing here from the day `Slider` landed, so every destroyed slider
    // left its handler and its two source closures in the table. A review caught the same
    // omission for `selectIds`; both are the same mistake, and `Handles.liveCount` plus a
    // self-test check is what stops a third.
    valueIds.remove(handle).foreach(Handles.release)
    selectIds.remove(handle).foreach(Handles.release)
    segments.remove(handle).foreach(_.foreach((_, id) => Handles.release(id)))
    dateIds.remove(handle).foreach(Handles.release)
    val _ = calendars.remove(handle)
    val _ = dateChanged.remove(handle)
    val _ = segmentIndex.remove(handle)
    val _ = segmentChosen.remove(handle)
    suppress -= handle
    kinds.remove(handle)
    val _ = stackAlignment.remove(handle)
    // GTK4: a widget is owned by its parent, and unparenting drops that reference, which
    // frees it. `g_object_unref` here is wrong — GTK says so out loud: "has a parent GtkBox
    // during dispose... Did you call g_object_unref() instead of gtk_widget_unparent()?".
    val _ = cssProviders.remove(handle)
    val _ = cssClasses.remove(handle)
    // A popover is parented to its widget, so it must be unparented before the widget
    // goes — GTK4 frees a widget's children with it, and the popover is not in the child
    // list the box knows about.
    menuPopover.remove(handle).foreach(gtk_widget_unparent)
    menuIds.remove(handle).foreach(_.foreach(Handles.release))

    sheetWindow.remove(handle) match {
      // A sheet's content is attached through `gtk_window_set_child`, so it is detached
      // the same way — the rule the Scroll case already encodes. That call also drops the
      // window's reference, which is the only one, so it *is* the release: doing it in
      // `dismiss` instead would free the widget before the reconciler destroyed it, and
      // `destroy` would then run `gtk_widget_get_parent` on freed memory. It did, and GTK
      // said so: "assertion 'GTK_IS_WIDGET (widget)' failed".
      case Some(win) =>
        gtk_window_set_child(win, null)
        gtk_window_destroy(win)
      case None =>
        setterParent.remove(handle) match {
          case Some(scroll) =>
            gtk_scrolled_window_set_child(scroll.asInstanceOf[Ptr[GtkScrolledWindow]], null)
          case None =>
            if gtk_widget_get_parent(handle) != null then gtk_widget_unparent(handle)
        }
    }
  }

  override def present(handle: Handle): Unit =
    if kinds.get(handle).contains(WidgetKind.Sheet) then presentSheet(handle)
    else presentAlert(handle)

  private def presentSheet(handle: Handle): Unit = Zone {
    val win = gtk_window_new().asInstanceOf[Ptr[GtkWindow]]
    gtk_window_set_modal(win, gbool(true))
    gtk_window_set_transient_for(win, GtkApp.window)
    alertTitle.get(handle).filter(_.nonEmpty).foreach(t => gtk_window_set_title(win, toCString(t)))
    gtk_window_set_child(win, handle)

    // The window manager's close button and Escape both arrive as close-request. Let the
    // close proceed and report it: the app owns the signal that mounted this, so it is the
    // app that has to take it down — exactly as with Alert.
    alertDismiss.get(handle).foreach { f =>
      val id = Handles.register { () =>
        val _ = sheetWindow.remove(handle)
        f()
      }
      sheetCloseId(handle) = id
      val _ = g_signal_connect_data(
        win.asInstanceOf[gpointer],
        toCString("close-request").asInstanceOf[Ptr[gchar]],
        GCallback.fromPtr(Handles.closeRequestPtr),
        Handles.idToPointer(id),
        null.asInstanceOf[GClosureNotify],
        GConnectFlags.define(0)
      )
    }

    sheetWindow(handle) = win
    gtk_window_present(win)
  }

  private def presentAlert(handle: Handle): Unit = Zone {
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

    // Only hide it. Tearing the window down here would free the content with it, and the
    // reconciler destroys the content immediately afterwards — `destroy` owns that, above.
    sheetWindow.get(handle).foreach(win => gtk_widget_set_visible(win.asInstanceOf[Ptr[GtkWidget]], gbool(false)))
    sheetCloseId.remove(handle).foreach(Handles.release)
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
