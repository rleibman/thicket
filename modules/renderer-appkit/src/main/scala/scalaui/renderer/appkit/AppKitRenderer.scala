package scalaui.renderer.appkit

import scala.collection.mutable
import scala.scalanative.unsafe.*
import scalaui.renderer.*

/** AppKit implementation of [[Renderer]].
  *
  * macOS before iOS on purpose (issue #3): it shares the shim, the handle table and the GC discipline with a UIKit
  * renderer, but iterates without a simulator boot, code signing or a device. S3 found the two toolkits diverge
  * structurally rather than per-call — flipped coordinates, `NSButton` target/action, no `UIControl.Event` — so the
  * Swift side is split per *file* rather than by `#if` inside function bodies, and this Scala side should not need to
  * change at all for UIKit.
  */
final class AppKitRenderer extends Renderer {

  type Handle = Shim.Handle

  private val kinds = mutable.Map.empty[Handle, WidgetKind]
  private val tapIds = mutable.Map.empty[Handle, Long]
  private val editIds = mutable.Map.empty[Handle, Long]
  private val boolIds = mutable.Map.empty[Handle, Long]

  def platform: String = "appkit"

  /** Every container in the v0 vocabulary is an `NSStackView` or an `NSScrollView`, both of which lay out their own
    * children. Nothing here is FrameBased yet, for the same reason nothing is in GTK.
    */
  def layoutMode(kind: WidgetKind): LayoutMode = LayoutMode.ToolkitManaged

  private def kindCode(kind: WidgetKind): CInt =
    kind match {
      case WidgetKind.Column    => 0
      case WidgetKind.Row       => 1
      case WidgetKind.Label     => 2
      case WidgetKind.Button    => 3
      case WidgetKind.TextField => 4
      case WidgetKind.Checkbox  => 5
      case WidgetKind.Scroll    => 6
      case WidgetKind.Divider   => 7
    }

  def create(
    kind:  WidgetKind,
    props: Seq[Prop]
  ): Handle = {
    val h = Shim.sui_create(kindCode(kind))
    kinds(h) = kind
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
    kinds.remove(handle)
    // Detaching is part of destroying; the shim removes from the superview before
    // releasing, and the reconciler destroys depth-first.
    Shim.sui_destroy(handle)
  }

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
