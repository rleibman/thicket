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

  private val kinds = mutable.Map.empty[Handle, WidgetKind]
  private val tapIds = mutable.Map.empty[Handle, Long]
  private val editIds = mutable.Map.empty[Handle, Long]
  private val boolIds = mutable.Map.empty[Handle, Long]
  private val rowIds = mutable.Map.empty[Handle, Long]

  /** The scrollers created horizontally. The renderer is the only thing that knows: the axis
    * is folded into the kind code at `create` and nothing on the Swift side is asked about
    * it afterwards. Exposed for [[isHorizontalScroll]] so a test can tell the two scrollers
    * in a screen apart without a tree-order guess.
    */
  private val horizontalScrolls = mutable.Set.empty[Handle]

  def platform: String = "appkit"

  /** Every container in the v0 vocabulary is an `NSStackView` or an `NSScrollView`, both of which lay out their own
    * children. Nothing here is FrameBased yet, for the same reason nothing is in GTK.
    */
  def layoutMode(kind: WidgetKind): LayoutMode = LayoutMode.ToolkitManaged

  /** The axis has to be folded into the kind code because `create` reaches Swift as a single
    * int and the axis is read at create, not at update. 15 is a horizontal `Scroll`; 6 stays
    * the vertical one. `NSScrollView` and `UIScrollView` each do both directions, so unlike
    * Android this is one class configured two ways rather than two classes.
    *
    * 15 rather than 9 because phase 2 reserved 9-14 for the widgets it added; an axis is not
    * worth renumbering six pending widgets over.
    */
  private def kindCode(
    kind:  WidgetKind,
    props: Seq[Prop]
  ): CInt =
    kind match {
      case WidgetKind.Column    => 0
      case WidgetKind.Row       => 1
      case WidgetKind.Label     => 2
      case WidgetKind.Button    => 3
      case WidgetKind.TextField => 4
      case WidgetKind.Checkbox  => 5
      case WidgetKind.Scroll    => if isHorizontal(props) then 15 else 6
      case WidgetKind.Divider   => 7
      case WidgetKind.Image     => 8

      // Reserved, and not yet built by either shim. `sui_create`'s `default:` branch
      // returns a *separator*, so passing one of these through would silently render a
      // slider as a hairline rule — a wrong widget that looks like a layout bug and sends
      // whoever hits it looking in the wrong place entirely. Failing loudly with the name
      // of the missing case is the honest behaviour until the Swift lands. Forgejo #9.
      case WidgetKind.Toggle            => unimplemented("Toggle", 9)
      case WidgetKind.Spacer            => unimplemented("Spacer", 10)
      case WidgetKind.ProgressBar       => unimplemented("ProgressBar", 11)
      case WidgetKind.ActivityIndicator => unimplemented("ActivityIndicator", 12)
      case WidgetKind.Slider            => unimplemented("Slider", 13)
      case WidgetKind.SecureField       => unimplemented("SecureField", 14)
    }

  private def unimplemented(name: String, reservedCode: Int): Nothing =
    throw new UnsupportedOperationException(
      s"$name is not implemented in the Apple shim yet (kind code $reservedCode reserved). " +
        "See Forgejo #9."
    )

  private def isHorizontal(props: Seq[Prop]): Boolean =
    props.exists {
      case Prop.Axis(Orientation.Horizontal) => true
      case _                                 => false
    }

  def create(
    kind:  WidgetKind,
    props: Seq[Prop]
  ): Handle = {
    val h = Shim.sui_create(kindCode(kind, props))
    kinds(h) = kind
    if kind == WidgetKind.Scroll && isHorizontal(props) then horizontalScrolls += h
    update(h, props)
    h
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
          case None                             => Shim.sui_clear_image(handle)
          case Some(ImageSource.FromFile(path)) => Zone(Shim.sui_set_image_file(handle, toCString(path)))
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
          case None =>
            val id = Handles.register(f)
            tapIds(handle) = id
            Shim.sui_on_tap(handle, Handles.tapTrampoline, id)
        }

      case Prop.OnTextChange(f) =>
        editIds.get(handle) match {
          case Some(id) => Handles.replaceText(id, f)
          case None =>
            val id = Handles.registerText(f)
            editIds(handle) = id
            Shim.sui_on_text_change(handle, Handles.textTrampoline, id)
        }

      case Prop.OnCheckedChange(f) =>
        boolIds.get(handle) match {
          case Some(id) => Handles.replaceBool(id, f)
          case None =>
            val id = Handles.registerBool(f)
            boolIds(handle) = id
            Shim.sui_on_checked_change(handle, Handles.boolTrampoline, id)
        }

      // Create-only: the contract says a renderer may ignore a later axis change, and
      // re-policying a live scroller mid-scroll is worse than ignoring it. Honoured at
      // `create` via `kindCode`, exactly as GTK and Android do it.
      case Prop.Axis(_) => ()

      // Unreachable while ProgressBar cannot be created at all (see kindCode), and present
      // so this renderer keeps compiling as the catalogue grows. Forgejo #9.
      case Prop.Progress(_) => ()

      // Likewise unreachable while Slider cannot be created. NSSlider and UISlider both
      // take the app's own units, so these are near-direct once the shim lands.
      case Prop.Range(_, _)     => ()
      case Prop.Value(_)        => ()
      case Prop.OnValueChange(_) => ()
    }

  def insertAfter(
    parent: Handle,
    child:  Handle,
    after:  Option[Handle]
  ): Unit = Shim.sui_insert_after(parent, child, after.getOrElse(null.asInstanceOf[Handle]))

  def removeChild(
    parent: Handle,
    child:  Handle
  ): Unit = Shim.sui_remove_child(parent, child)

  /** `NSStackView` has no reorder primitive, so the contract's remove+insert default is what this does — inherited
    * rather than overridden, deliberately.
    */
  def destroy(handle: Handle): Unit = {
    tapIds.remove(handle).foreach(Handles.release)
    editIds.remove(handle).foreach(Handles.release)
    boolIds.remove(handle).foreach(Handles.release)
    // The row closure captures the whole RowSource graph, so an unreleased id keeps every
    // unmounted list alive. A table asking for a row after this gets null, which it treats as
    // no view.
    rowIds.remove(handle).foreach(Handles.release)
    kinds.remove(handle)
    horizontalScrolls.remove(handle)
    // Detaching is part of destroying; the shim removes from the superview before
    // releasing, and the reconciler destroys depth-first.
    Shim.sui_destroy(handle)
  }

  /** Whether a handle is a `Scroll` that was created with [[Orientation.Horizontal]]. */
  def isHorizontalScroll(handle: Handle): Boolean = horizontalScrolls.contains(handle)

  /** `NSTableView` and `UITableView` both recycle, so the contract's `RowSource` is a direct
    * fit and needed no change — the third toolkit in a row for which that is true, after
    * `GtkListView` and Android's `ListView`.
    */
  override def supportsVirtualRows: Boolean = true

  override def createVirtualList(source: RowSource[Handle]): Handle = {
    // The id, not the closure, is what crosses to Swift: a C function pointer cannot close
    // over state (S4), so one static trampoline serves every table.
    val id = Handles.registerRow((index, recycled) =>
      if index >= 0 && index < source.count then source.bind(index, recycled)
      else null.asInstanceOf[Handle]
    )
    val table = Shim.sui_create_table(Handles.rowTrampoline, id)
    rowIds(table) = id
    kinds(table) = WidgetKind.Scroll
    Shim.sui_table_reload(table, source.count)
    source.onInvalidate(() => Shim.sui_table_reload(table, source.count))
    table
  }

  /** How many row views the table has actually built. The number that says virtualisation is
    * working: without it a 10 000-row list silently materialises 10 000 rows.
    */
  def materialisedRows(handle: Handle): Int = Shim.sui_table_materialised(handle)

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
