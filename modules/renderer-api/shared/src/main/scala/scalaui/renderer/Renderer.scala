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
enum LayoutMode:
  /** The framework computes rectangles (Yoga) and calls `setFrame` on every child.
    * e.g. a plain `UIView`, a `GtkFixed`, an Android `FrameLayout` used as a canvas.
    */
  case FrameBased

  /** The toolkit lays out its own children; `setFrame` is ignored. The framework instead
    * hands the container its layout *style* (spacing, padding, alignment) as properties.
    * e.g. `GtkBox`, `UIStackView`, Android `LinearLayout`.
    */
  case ToolkitManaged

/** A widget's minimum and natural size along both axes.
  *
  * One size is not enough: GTK reports minimum and natural separately (as do CSS
  * `min-content`/`max-content`), and collapsing them loses information the toolkit needs.
  */
final case class Measurement(minW: Float, minH: Float, natW: Float, natH: Float)

object Measurement:
  def exact(w: Float, h: Float): Measurement = Measurement(w, h, w, h)

final case class Frame(x: Float, y: Float, w: Float, h: Float)

/** `NaN` on an axis means "unconstrained". */
final case class Constraints(maxW: Float, maxH: Float)

object Constraints:
  val unbounded: Constraints = Constraints(Float.NaN, Float.NaN)

/** The widget vocabulary a renderer must understand. Deliberately small for v0; every
  * addition costs work in every renderer, so the list grows only with evidence.
  */
enum WidgetKind:
  case Column, Row, Label, Button

/** A property change. An enum rather than `Map[String, Any]` so the compiler checks that
  * each renderer handles every case — one of the reasons for doing this in Scala at all.
  */
enum Prop:
  case Text(value: String)
  case OnTap(handler: () => Unit)
  case Spacing(dp: Int)
  case Padding(dp: Int)
  case Enabled(value: Boolean)

trait Renderer:
  /** An opaque per-renderer widget reference. */
  type Handle

  def platform: String

  /** Declares how this container positions children. See [[LayoutMode]]. */
  def layoutMode(kind: WidgetKind): LayoutMode

  def create(kind: WidgetKind, props: Seq[Prop]): Handle
  def update(handle: Handle, patch: Seq[Prop]): Unit

  /** Insert `child` into `parent` directly after `after`, or first when `after` is `None`.
    *
    * Specified by preceding sibling rather than index because every toolkit can express
    * that, while several (GTK among them) have no insert-at-index primitive.
    */
  def insertAfter(parent: Handle, child: Handle, after: Option[Handle]): Unit

  def removeChild(parent: Handle, child: Handle): Unit
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
