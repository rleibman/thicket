package scalaui.renderer

/** The seam between the framework and a platform toolkit.
  *
  * Revised after S7 built it against GTK4 and found four defects in the §7.4 sketch:
  * `setFrame` is not universally expressible, `insertChild`-by-index has no GTK primitive,
  * `measure` must report min *and* natural size, and a type named `Size` is shadowed by
  * `scalanative.unsafe.Size`. All four are addressed here.
  */

/** Who positions a container's children.
  *
  * The §7.4 sketch assumed the framework always does, and that the toolkit obeys `setFrame`.
  * A `GtkBox` does not: it lays out its own children, and forcing absolute positioning would
  * mean `GtkFixed` everywhere, discarding GTK's sizing, RTL handling and baseline alignment.
  * So the renderer *declares* which model each container uses and the reconciler adapts.
  */
enum LayoutMode {
  /** The framework computes rectangles (Yoga) and calls `setFrame` on every child.
    * e.g. a plain `UIView`, a `GtkFixed`, an Android `FrameLayout` used as a canvas.
    */
  case FrameBased

  /** The toolkit lays out its own children; `setFrame` is ignored. The framework instead
    * hands the container its layout *style* (spacing, padding, alignment) as properties.
    * e.g. `GtkBox`, `UIStackView`, Android `LinearLayout`.
    */
  case ToolkitManaged
}

/** A widget's minimum and natural size along both axes.
  *
  * One size is not enough: GTK reports minimum and natural separately (as do CSS
  * `min-content`/`max-content`), and collapsing them loses information the toolkit needs.
  */
final case class Measurement(minW: Float, minH: Float, natW: Float, natH: Float)

object Measurement {
  def exact(w: Float, h: Float): Measurement = Measurement(w, h, w, h)
}

final case class Frame(x: Float, y: Float, w: Float, h: Float)

/** `NaN` on an axis means "unconstrained". */
final case class Constraints(maxW: Float, maxH: Float)

object Constraints {
  val unbounded: Constraints = Constraints(Float.NaN, Float.NaN)
}

/** The widget vocabulary a renderer must understand. Deliberately small for v0; every
  * addition costs work in every renderer, so the list grows only with evidence.
  */
enum WidgetKind {
  case Column, Row, Label, Button

  /** Single-line text input. The first widget whose data flows *back* to the app. */
  case TextField

  /** A boolean toggle. */
  case Checkbox

  /** A scrolling viewport around exactly one child. Unlike Column/Row it does not hold a
    * list, which the reconciler has to respect: inserting a second child replaces the first.
    */
  case Scroll
}

/** A property change. An enum rather than `Map[String, Any]` so the compiler checks that
  * each renderer handles every case — one of the reasons for doing this in Scala at all.
  */
enum Prop {
  case Text(value: String)
  case OnTap(handler: () => Unit)
  case Spacing(dp: Int)
  case Padding(dp: Int)
  case Enabled(value: Boolean)

  /** Greyed-out hint shown while a text field is empty. */
  case Placeholder(value: String)

  /** Fired as the user edits. The renderer must *not* fire this when the app pushes a new
    * value in — that would be an echo, and with a signal bound to it, a loop.
    */
  case OnTextChange(handler: String => Unit)

  case Checked(value: Boolean)
  case OnCheckedChange(handler: Boolean => Unit)

  /** Type role, mapped to each platform's own type scale rather than to a pixel size.
    * Asking for "17pt semibold" would be exactly the cross-platform lowest-common-
    * denominator this project exists to avoid.
    */
  case Style(role: TextRole)

  /** Whether this child should absorb spare space along its parent's main axis. */
  case Grow(value: Boolean)

  /** Horizontal alignment of a widget's own content. */
  case Align(value: Alignment)
}

enum TextRole {
  case Title, Body, Caption
}

enum Alignment {
  case Start, Center, End
}

trait Renderer {
  /** An opaque per-renderer widget reference. */
  type Handle

  def platform: String

  /** Declares how this container positions children. See [[LayoutMode]]. */
  def layoutMode(kind: WidgetKind): LayoutMode

  def create(kind: WidgetKind, props: Seq[Prop]): Handle
  /** Apply properties to an existing widget.
    *
    * **A renderer must not disturb a widget when written a value it already shows.** The
    * reconciler cannot enforce this: after a user edits a text field, the app writes the
    * new value back through a signal, and from the reconciler's side that is a genuine
    * change it has never applied. Only the renderer can compare against what the widget
    * actually holds. Skipping the write is what stops the caret jumping to the end on every
    * keystroke, and — for renderers whose widgets emit a change event when set
    * programmatically — what stops a bound field looping.
    */
  def update(handle: Handle, patch: Seq[Prop]): Unit

  /** Insert `child` into `parent` directly after `after`, or first when `after` is `None`.
    *
    * Specified by preceding sibling rather than index because every toolkit can express
    * that, while several (GTK among them) have no insert-at-index primitive.
    */
  def insertAfter(parent: Handle, child: Handle, after: Option[Handle]): Unit

  def removeChild(parent: Handle, child: Handle): Unit

  /** Move an already-attached `child` to sit directly after `after` (first when `None`).
    *
    * Keyed list reconciliation reorders existing widgets, and doing that as
    * remove-then-insert destroys focus, scroll position and in-flight animations on most
    * toolkits. The default is still remove+insert so a renderer need not implement it;
    * anything with a native reorder should override.
    */
  def moveAfter(parent: Handle, child: Handle, after: Option[Handle]): Unit = {
    removeChild(parent, child)
    insertAfter(parent, child, after)
  }
  /** Detach `handle` from its parent, if attached, and release it.
    *
    * Detaching is part of destroying, not a separate step the caller performs first. GTK
    * forced this: `gtk_box_remove` frees the removed widget's whole subtree, so a reconciler
    * that removed a container and *then* destroyed its children would be destroying freed
    * memory. The reconciler therefore destroys depth-first — children before parents — and
    * each renderer detaches whatever it is given.
    */
  def destroy(handle: Handle): Unit

  def measure(handle: Handle, constraints: Constraints): Measurement

  /** Position a child. Only meaningful when its parent is [[LayoutMode.FrameBased]];
    * renderers may ignore it otherwise.
    */
  def setFrame(handle: Handle, frame: Frame): Unit

  /** Run `f` on the UI thread. Safe to call from any thread the platform permits.
    *
    * On Apple targets, "any thread" means the main thread or a Scala-created thread —
    * never a GCD queue, which segfaults in the GC allocator (S1).
    */
  def runOnUiThread(f: () => Unit): Unit
}
