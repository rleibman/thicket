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

package thicket.renderer.apple

import scala.collection.mutable
import scala.scalanative.unsafe.*
import thicket.renderer.*

/** AppKit implementation of [[Renderer]].
  *
  * macOS before iOS on purpose (issue #3): it shares the shim, the handle table and the GC discipline with a UIKit
  * renderer, but iterates without a simulator boot, code signing or a device. S3 found the two toolkits diverge
  * structurally rather than per-call — flipped coordinates, `NSButton` target/action, no `UIControl.Event` — so the
  * Swift side is split per *file* rather than by `#if` inside function bodies, and this Scala side should not need to
  * change at all for UIKit.
  */
final class AppleRenderer extends Renderer {

  type Handle = Shim.Handle

  // Every per-handle table is made through these, which register it; `destroy` sweeps them all
  // (review 2026-10-20, P3-1). Callback ids are still released explicitly in `destroy` first.
  private val perHandleMaps = mutable.ArrayBuffer.empty[mutable.Map[Handle, ?]]
  private val perHandleSets = mutable.ArrayBuffer.empty[mutable.Set[Handle]]

  private def perHandle[V]: mutable.Map[Handle, V] = {
    val m = mutable.Map.empty[Handle, V]
    perHandleMaps += m
    m
  }

  private def perHandleSet: mutable.Set[Handle] = {
    val s = mutable.Set.empty[Handle]
    perHandleSets += s
    s
  }

  /** Entries across every per-handle table. For the self-tests: it must come back down when widgets go. */
  def tableEntries: Int = perHandleMaps.map(_.size).sum + perHandleSets.map(_.size).sum + tabs.entries

  /** TabView and Tab (#69): the controllers are the shim's; the rule for keeping the user on their tab is here. */
  private val tabs = AppleTabs()

  private val kinds = perHandle[WidgetKind]
  private val tapIds = perHandle[Long]
  private val editIds = perHandle[Long]
  private val boolIds = perHandle[Long]
  private val valueIds = perHandle[Long]
  private val selectIds = perHandle[Long]
  private val dateIds = perHandle[Long]
  private val actionIds = perHandle[Seq[Long]]
  private val dismissIds = perHandle[Long]
  private val menuIds = perHandle[Seq[Long]]

  /** What is on screen *over* the app, in the order it was presented. A presented widget is by definition not in the
    * root's tree, so this is how a test finds one without walking a tree that cannot contain it.
    */
  private val presentedNow = mutable.LinkedHashMap.empty[Handle, WidgetKind]
  private val rowIds = perHandle[Long]

  /** The scrollers created horizontally. The renderer is the only thing that knows: the axis is folded into the kind
    * code at `create` and nothing on the Swift side is asked about it afterwards. Exposed for [[isHorizontalScroll]] so
    * a test can tell the two scrollers in a screen apart without a tree-order guess.
    */
  private val horizontalScrolls = perHandleSet

  /** D9 (#74): a kind the shims have not built yet (`ShimKind.unbuilt`, from `Abi.kinds`) is a Column holding a label
    * that says so, with the kind's own children below the label. Before this, the shim's fallback built a plain view
    * and a TabView's tabs piled up silently, and on AppKit the fallback could not hold children at all: the run died
    * with "uncaught foreign exception" and no reason (#72). Keyed by the box; the value is the label, which the
    * reconciler does not know about, so `destroy` frees it.
    */
  private val placeholders = perHandle[Handle]

  /** Under a self-test an unbuilt kind throws instead, so CI cannot pass over it (D9). `THICKET_ALLOW_UNBUILT=1` lets a
    * local run carry on with the placeholder, to measure everything else; CI never sets it.
    */
  lazy private val strictUnbuilt: Boolean =
    Seq("THICKET_SELFTEST", "THICKET_GALLERY_SELFTEST", "THICKET_LAZYTEST").exists(sys.env.contains) &&
      !sys.env.contains("THICKET_ALLOW_UNBUILT")

  /** The handles standing in for unbuilt kinds, for a self-test to name them. */
  def placeholderHandles: Set[Handle] = placeholders.keySet.toSet

  def platform: String = "appkit"

  /** Every container in the v0 vocabulary is an `NSStackView` or an `NSScrollView`, both of which lay out their own
    * children. Nothing here is FrameBased yet, for the same reason nothing is in GTK.
    */
  def layoutMode(kind: WidgetKind): LayoutMode = LayoutMode.ToolkitManaged

  /** The axis has to be folded into the kind code because `create` reaches Swift as a single int and the axis is read
    * at create, not at update. 15 is a horizontal `Scroll`; 6 stays the vertical one. `NSScrollView` and `UIScrollView`
    * each do both directions, so unlike Android this is one class configured two ways rather than two classes.
    *
    * 15 rather than 9 because phase 2 reserved 9-14 for the widgets it added; an axis is not worth renumbering six
    * pending widgets over.
    *
    * The codes are `ShimKind`, generated from `tools/shim-gen`'s `Abi.kinds` alongside the header table that says which
    * view each toolkit builds for them.
    */
  private def kindCode(
    kind:  WidgetKind,
    props: Seq[Prop]
  ): CInt =
    kind match {
      case WidgetKind.Column    => ShimKind.Column
      case WidgetKind.Row       => ShimKind.Row
      case WidgetKind.Label     => ShimKind.Label
      case WidgetKind.Button    => ShimKind.Button
      case WidgetKind.TextField => ShimKind.TextField
      case WidgetKind.Checkbox  => ShimKind.Checkbox
      case WidgetKind.Scroll    => if isHorizontal(props) then ShimKind.ScrollHorizontal else ShimKind.Scroll
      case WidgetKind.Divider   => ShimKind.Divider
      case WidgetKind.Image     => ShimKind.Image

      case WidgetKind.Toggle            => ShimKind.Toggle
      case WidgetKind.Spacer            => ShimKind.Spacer
      case WidgetKind.ProgressBar       => ShimKind.ProgressBar
      case WidgetKind.ActivityIndicator => ShimKind.ActivityIndicator
      case WidgetKind.Slider            => ShimKind.Slider
      case WidgetKind.SecureField       => ShimKind.SecureField
      case WidgetKind.Picker            => ShimKind.Picker
      case WidgetKind.ZStack            => ShimKind.ZStack
      case WidgetKind.SegmentedControl  => ShimKind.SegmentedControl
      case WidgetKind.DatePicker        => ShimKind.DatePicker
      case WidgetKind.Grid              => ShimKind.Grid
      case WidgetKind.IconButton        => ShimKind.IconButton
      // An NSTabViewController / a UITabBarController, and one item each (#69).
      case WidgetKind.TabView => ShimKind.TabView
      case WidgetKind.Tab     => ShimKind.Tab

      // Presented rather than inserted (WidgetKind.presented): the alert's handle is a
      // placeholder holding its configuration, the sheet's a real container its children
      // mount into. `present` is what puts either on screen.
      case WidgetKind.Alert => ShimKind.Alert
      case WidgetKind.Sheet => ShimKind.Sheet
    }

  /** Spelled out rather than `ordinal`: these numbers are ABI, and reordering the enum must not silently move them. */
  private def alignCode(a: Alignment): CInt =
    a match {
      case Alignment.Start  => 0
      case Alignment.Center => 1
      case Alignment.End    => 2
    }

  private def isHorizontal(props: Seq[Prop]): Boolean =
    props.exists {
      case Prop.Axis(Orientation.Horizontal) => true
      case _                                 => false
    }

  def create(
    kind:  WidgetKind,
    props: Seq[Prop]
  ): Handle = {
    val code = kindCode(kind, props)
    if ShimKind.unbuilt(code) then return placeholder(kind)
    val h = Shim.sui_create(code)
    kinds(h) = kind
    if kind == WidgetKind.Scroll && isHorizontal(props) then horizontalScrolls += h
    // Before `update`: the selection arrives with the props, before any tab does.
    if kind == WidgetKind.TabView then tabs.register(h)
    update(h, props)
    h
  }

  private def placeholder(kind: WidgetKind): Handle = {
    val message = s"$kind is not built on Apple yet"
    if strictUnbuilt then
      throw IllegalStateException(
        s"$message (D9, #74): a self-test does not pass over an unbuilt kind. " +
          "THICKET_ALLOW_UNBUILT=1 shows the placeholder instead, for a local run."
      )
    val box = Shim.sui_create(ShimKind.Column)
    val label = Shim.sui_create(ShimKind.Label)
    Zone(Shim.sui_set_text(label, toCString(message)))
    Shim.sui_insert_after(box, label, null.asInstanceOf[Handle])
    kinds(box) = kind
    placeholders(box) = label
    box
  }

  def update(
    handle: Handle,
    patch:  Seq[Prop]
  ): Unit =
    patch.foreach {
      case Prop.Text(v) =>
        // The shim compares before writing, which is what keeps the caret from jumping to
        // the end when the app writes back what the user just typed. The contract requires
        // it and only the renderer can do it.
        Zone(Shim.sui_set_text(handle, toCString(v)))

      case Prop.Placeholder(v) =>
        Zone(Shim.sui_set_placeholder(handle, toCString(v)))

      case Prop.Checked(v) =>
        Shim.sui_set_checked(handle, if v then 1 else 0)

      case Prop.Enabled(v) =>
        Shim.sui_set_enabled(handle, if v then 1 else 0)

      case Prop.Spacing(dp) =>
        Shim.sui_set_spacing(handle, dp)

      case Prop.Padding(dp) =>
        Shim.sui_set_padding(handle, dp)

      case Prop.Style(role) =>
        Shim.sui_set_text_role(
          handle,
          role match {
            case TextRole.Title   => 0
            case TextRole.Body    => 1
            case TextRole.Caption => 2
          }
        )

      case Prop.TextEmphasis(level) =>
        Shim.sui_set_text_emphasis(
          handle,
          level match {
            case Emphasis.Normal    => 0
            case Emphasis.Secondary => 1
          }
        )

      case Prop.Grow(v) =>
        Shim.sui_set_grow(handle, if v then 1 else 0)

      case Prop.Align(a) =>
        Shim.sui_set_align(
          handle,
          a match {
            case Alignment.Start  => 0
            case Alignment.Center => 1
            case Alignment.End    => 2
          }
        )

      case Prop.Tint(color) =>
        // `None` means "leave it to the platform", deliberately not "use black".
        color match {
          case Some(c) => Shim.sui_set_tint(handle, 1, c.r, c.g, c.b)
          case None    => ()
        }

      case Prop.Fill(color) =>
        color match {
          case Some(c) => Shim.sui_set_fill(handle, 1, c.r, c.g, c.b)
          case None    => ()
        }

      case Prop.Picture(source) =>
        source match {
          case None                              => Shim.sui_clear_image(handle)
          case Some(ImageSource.FromFile(path))  => Zone(Shim.sui_set_image_file(handle, toCString(path)))
          case Some(ImageSource.FromBytes(data)) =>
            Zone {
              val buf = alloc[Byte](data.length)
              var i = 0
              while i < data.length do {
                buf(i) = data(i)
                i += 1
              }
              Shim.sui_set_image_bytes(handle, buf, data.length)
            }
        }

      case Prop.Fit(fit) =>
        Shim.sui_set_content_fit(
          handle,
          fit match {
            case ContentFit.Contain => 0
            case ContentFit.Cover   => 1
            case ContentFit.Fill    => 2
          }
        )

      // Re-applying a handler swaps the closure behind the existing id rather than
      // registering a second one, so repeated updates cannot stack handlers.
      case Prop.OnTap(f) =>
        tapIds.get(handle) match {
          case Some(id) => Handles.replace(id, f)
          case None     =>
            val id = Handles.register(f)
            tapIds(handle) = id
            Shim.sui_on_tap(handle, Handles.tapTrampoline, id)
        }

      case Prop.OnTextChange(f) =>
        editIds.get(handle) match {
          case Some(id) => Handles.replaceText(id, f)
          case None     =>
            val id = Handles.registerText(f)
            editIds(handle) = id
            Shim.sui_on_text_change(handle, Handles.textTrampoline, id)
        }

      case Prop.OnCheckedChange(f) =>
        boolIds.get(handle) match {
          case Some(id) => Handles.replaceBool(id, f)
          case None     =>
            val id = Handles.registerBool(f)
            boolIds(handle) = id
            Shim.sui_on_checked_change(handle, Handles.boolTrampoline, id)
        }

      // Create-only: the contract says a renderer may ignore a later axis change, and
      // re-policying a live scroller mid-scroll is worse than ignoring it. Honoured at
      // `create` via `kindCode`, exactly as GTK and Android do it.
      case Prop.Axis(_) => ()

      // `None` is indeterminate, which is deliberately not the same as `Some(0.0)`.
      case Prop.Progress(value) =>
        value match {
          case Some(f) => Shim.sui_set_progress(handle, 1, f)
          case None    => Shim.sui_set_progress(handle, 0, 0.0)
        }

      // In the app's own units. `Range` always arrives before `Value` (CatalogueSpec), so the
      // value is never clamped against the control's default bounds.
      case Prop.Range(min, max) => Shim.sui_set_range(handle, min, max)
      case Prop.Value(v)        => Shim.sui_set_value(handle, v)

      // An NSGridView on AppKit; a constraint construction on UIKit, which has no grid view.
      case Prop.Columns(n) => Shim.sui_set_grid_columns(handle, n)

      // An SF Symbol, named by AppleIcons; the label is VoiceOver's and the tooltip.
      case Prop.Glyph(icon)        => Zone(Shim.sui_set_symbol(handle, toCString(AppleIcons.name(icon))))
      case Prop.AccessibleLabel(t) => Zone(Shim.sui_set_accessible_label(handle, toCString(t)))

      case Prop.OpenUrl(url) => Zone(Shim.sui_set_open_url(handle, toCString(url)))

      case Prop.StackAlignment(h, v) => Shim.sui_set_stack_alignment(handle, alignCode(h), alignCode(v))

      case Prop.SafeArea(edges) =>
        // A bitmask, matching the ABI. Leading/Trailing stay logical across the boundary:
        // the shim resolves them against the view's effective layout direction, because only
        // the platform knows whether the locale is RTL.
        val mask =
          (if edges.contains(Edge.Top) then 1 else 0) |
            (if edges.contains(Edge.Bottom) then 2 else 0) |
            (if edges.contains(Edge.Leading) then 4 else 0) |
            (if edges.contains(Edge.Trailing) then 8 else 0)
        Shim.sui_set_safe_area(handle, mask)

      case Prop.Options(values) =>
        // Clear-then-add, the shape the ABI uses for an alert's actions. No handles to
        // release here: options are strings, not callbacks.
        //
        // The selection is an index and survives new options, as on GTK and Android (#77,
        // review 2026-10-20 P1-10). Clearing empties an NSSegmentedControl, an NSPopUpButton
        // and a UISegmentedControl, and adding back auto-selects the pop-up's first item, so
        // the index the control held is read first and written back after. The shims do not
        // fire the action for either step, so nothing is reported to the app. A Selected in
        // the same patch, before or after this, still wins: it was applied to the control
        // and is what is read here, or it is applied next.
        val keep = Shim.sui_get_selected(handle)
        Shim.sui_picker_clear_options(handle)
        values.foreach(v => Zone(Shim.sui_picker_add_option(handle, toCString(v))))
        if Shim.sui_get_selected(handle) != keep then Shim.sui_set_selected(handle, keep)

      // A TabView's selection keeps the app's choice until its tab arrives, and -1 or past the end
      // leaves the tab shown; see AppleTabs.
      case Prop.Selected(index) if tabs.isTabView(handle) => tabs.select(handle, index)

      case Prop.Selected(index) =>
        // Read first and skip a no-op write, as the toggle does: setting the selection makes
        // both AppKit and UIKit fire their action, which would report the app's own write
        // back as a user choice.
        if Shim.sui_get_selected(handle) != index then Shim.sui_set_selected(handle, index)

      // An epoch day across the boundary; see Abi.dates. Read first and skip a no-op write,
      // as the toggle and the picker do.
      case Prop.DateValue(d) =>
        if Shim.sui_get_date(handle) != d.toEpochDay then Shim.sui_set_date(handle, d.toEpochDay)

      case Prop.OnDateChange(f) =>
        val g: Int => Unit = day => f(CalendarDate.fromEpochDay(day))
        dateIds.get(handle) match {
          case Some(id) => Handles.replaceInt(id, g)
          case None     =>
            val id = Handles.registerInt(g)
            dateIds(handle) = id
            Shim.sui_on_date_change(handle, Handles.intTrampoline, id)
        }

      case Prop.OnSelect(f) =>
        // A TabView also reports an index that moved under the user; the shim reports only clicks.
        if tabs.isTabView(handle) then tabs.onSelect(handle, f)
        selectIds.get(handle) match {
          case Some(id) => Handles.replaceInt(id, f)
          case None     =>
            val id = Handles.registerInt(f)
            selectIds(handle) = id
            Shim.sui_on_select(handle, Handles.intTrampoline, id)
        }

      case Prop.OnValueChange(f) =>
        valueIds.get(handle) match {
          case Some(id) => Handles.replaceValue(id, f)
          case None     =>
            val id = Handles.registerValue(f)
            valueIds(handle) = id
            Shim.sui_on_value_change(handle, Handles.valueTrampoline, id)
        }

      // The title is Prop.Text, routed by the shim for *both* presented kinds — GTK and
      // Android both shipped with it reaching Alert only, and a Sheet's title went nowhere.
      case Prop.Message(v) => Zone(Shim.sui_set_message(handle, toCString(v)))

      // Data, not child widgets. Each action's closure gets its own tap id; replacing the
      // list releases the old ones, so a re-rendered alert does not accumulate them.
      case Prop.Actions(as) =>
        actionIds.remove(handle).foreach(_.foreach(Handles.release))
        Shim.sui_alert_clear_actions(handle)
        val ids = as.map { a =>
          val id = Handles.register(() => a.onSelect())
          val role = if a.cancel then 2 else if a.destructive then 1 else 0
          Zone(Shim.sui_alert_add_action(handle, toCString(a.label), role, Handles.tapTrampoline, id))
          id
        }
        actionIds(handle) = ids

      // The platform closing it without a choice. Never fired for the app's own dismiss.
      case Prop.OnDismiss(f) =>
        dismissIds.get(handle) match {
          case Some(id) => Handles.replace(id, f)
          case None     =>
            val id = Handles.register(f)
            dismissIds(handle) = id
            Shim.sui_on_dismiss(handle, Handles.tapTrampoline, id)
        }

      // A property of the view, not a widget: `NSView.menu` on AppKit, a
      // `UIContextMenuInteraction` on UIKit, and nothing added to the tree on either. The
      // platform picks the gesture. Each item's closure gets its own tap id; replacing the
      // menu releases the previous set.
      case Prop.ContextMenu(items) =>
        menuIds.remove(handle).foreach(_.foreach(Handles.release))
        Shim.sui_menu_clear(handle)
        val ids = items.map { item =>
          val id = Handles.register(() => item.onSelect())
          Zone(
            Shim.sui_menu_add_item(
              handle,
              toCString(item.label),
              if item.enabled then 1 else 0,
              Handles.tapTrampoline,
              id
            )
          )
          id
        }
        if ids.nonEmpty then menuIds(handle) = ids
    }

  def insertAfter(
    parent: Handle,
    child:  Handle,
    after:  Option[Handle]
  ): Unit =
    if tabs.isTabView(parent) then tabs.insertAfter(parent, child, after)
    else {
      // In a placeholder, "first" means after its label, which stays on top.
      val anchor = after.orElse(placeholders.get(parent))
      Shim.sui_insert_after(parent, child, anchor.getOrElse(null.asInstanceOf[Handle]))
    }

  def removeChild(
    parent: Handle,
    child:  Handle
  ): Unit =
    if tabs.isTabView(parent) then tabs.remove(parent, child)
    else Shim.sui_remove_child(parent, child)

  /** A tab moved is inserted where it goes, not removed first: removing the tab on screen would move the user. */
  override def moveAfter(
    parent: Handle,
    child:  Handle,
    after:  Option[Handle]
  ): Unit =
    if tabs.isTabView(parent) then tabs.insertAfter(parent, child, after)
    else super.moveAfter(parent, child, after)

  /** `NSStackView` has no reorder primitive, so the contract's remove+insert default is what this does — inherited
    * rather than overridden, deliberately.
    */
  def destroy(handle: Handle): Unit = {
    // A tab leaves its TabView through the same rule as a removal, so the user stays put.
    tabs.destroy(handle)
    tapIds.remove(handle).foreach(Handles.release)
    editIds.remove(handle).foreach(Handles.release)
    boolIds.remove(handle).foreach(Handles.release)
    valueIds.remove(handle).foreach(Handles.release)
    selectIds.remove(handle).foreach(Handles.release)
    dateIds.remove(handle).foreach(Handles.release)
    actionIds.remove(handle).foreach(_.foreach(Handles.release))
    dismissIds.remove(handle).foreach(Handles.release)
    menuIds.remove(handle).foreach(_.foreach(Handles.release))
    // The row closure captures the whole RowSource graph, so an unreleased id keeps every
    // unmounted list alive. A table asking for a row after this gets null, which it treats as
    // no view.
    rowIds.remove(handle).foreach(Handles.release)
    placeholders.remove(handle).foreach(Shim.sui_destroy)
    // Everything else keyed by this handle, in every table.
    perHandleMaps.foreach(_.remove(handle))
    perHandleSets.foreach(_ -= handle)
    // Detaching is part of destroying; the shim removes from the superview before
    // releasing, and the reconciler destroys depth-first.
    Shim.sui_destroy(handle)
  }

  /** Called after the widget's children are mounted into it, so a sheet is shown with its content in place. */
  override def present(handle: Handle): Unit = {
    presentedNow(handle) = kinds.getOrElse(handle, WidgetKind.Alert)
    Shim.sui_present(handle)
  }

  /** Called before `destroy`. Only takes it off screen; `destroy` releases it, so nothing the presentation held can be
    * freed while the reconciler still has the handle.
    */
  override def dismiss(handle: Handle): Unit = {
    Shim.sui_dismiss(handle)
    val _ = presentedNow.remove(handle)
  }

  /** The presented widgets currently on screen, oldest first, with their kinds. */
  def presented: List[(Handle, WidgetKind)] = presentedNow.toList

  /** What a handle was created as, if this renderer created it. A test uses it to pick out the framework's own widgets
    * from the platform's private subviews — a UIKit switch or slider contains image views of its own.
    */
  def kindOf(handle: Handle): Option[WidgetKind] = kinds.get(handle)

  /** Whether a handle is a `Scroll` that was created with [[Orientation.Horizontal]]. */
  def isHorizontalScroll(handle: Handle): Boolean = horizontalScrolls.contains(handle)

  /** `NSTableView` and `UITableView` both recycle, so the contract's `RowSource` is a direct fit and needed no change —
    * the third toolkit in a row for which that is true, after `GtkListView` and Android's `ListView`.
    */
  override def supportsVirtualRows: Boolean = true

  override def createVirtualList(source: RowSource[Handle]): Handle = {
    // The id, not the closure, is what crosses to Swift: a C function pointer cannot close
    // over state (S4), so one static trampoline serves every table.
    // Index -1 is the table handing a row view back for good (#78, review 2026-10-20 P2-8):
    // the cell hosting it was freed, or a re-bind replaced it. `discard` disposes the row's
    // slot and owner, and ignores a view the source no longer holds.
    val id = Handles.registerRow(
      (
        index,
        recycled
      ) =>
        if index == -1 then {
          recycled.foreach(source.discard)
          null.asInstanceOf[Handle]
        } else if index >= 0 && index < source.count then source.bind(index, recycled)
        else null.asInstanceOf[Handle]
    )
    val table = Shim.sui_create_table(Handles.rowTrampoline, id)
    rowIds(table) = id
    kinds(table) = WidgetKind.Scroll
    Shim.sui_table_reload(table, source.count)
    source.onInvalidate(() => Shim.sui_table_reload(table, source.count))
    table
  }

  /** How many row views the table has actually built. The number that says virtualisation is working: without it a 10
    * 000-row list silently materialises 10 000 rows.
    */
  def materialisedRows(handle: Handle): Int = Shim.sui_table_materialised(handle)

  /** How many of those row views the table has handed back for good, each through `RowSource.discard`. Built minus
    * discarded is what the table still holds.
    */
  def discardedRows(handle: Handle): Int = Shim.sui_table_discarded(handle)

  def measure(
    handle:      Handle,
    constraints: Constraints
  ): Measurement = {
    val minW = stackalloc[CDouble]()
    val minH = stackalloc[CDouble]()
    val natW = stackalloc[CDouble]()
    val natH = stackalloc[CDouble]()
    Shim.sui_measure(
      handle,
      constraints.maxW.toDouble,
      constraints.maxH.toDouble,
      minW,
      minH,
      natW,
      natH
    )
    Measurement((!minW).toFloat, (!minH).toFloat, (!natW).toFloat, (!natH).toFloat)
  }

  /** No-op: every v0 container is ToolkitManaged, so AppKit positions its own children. */
  def setFrame(
    handle: Handle,
    frame:  Frame
  ): Unit = ()

  def runOnUiThread(f: () => Unit): Unit = Shim.sui_run_on_main(Handles.tapTrampoline, Handles.registerOneShot(f))

}
