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
    *
    * Scrolls vertically unless created with [[Prop.Axis]]. A horizontal one is the answer
    * to `Row` overflow: `Row` itself clips, and every toolkit here has a scroller but only
    * some have a wrapping box.
    */
  case Scroll

  /** A hairline rule between items, drawn by the platform at the platform's own weight
    * and colour.
    */
  case Divider

  /** A picture. */
  case Image

  /** A boolean control shown as a sliding switch rather than a box with a tick.
    *
    * The same state as [[Checkbox]] and the same props — [[Prop.Checked]] and
    * [[Prop.OnCheckedChange]] — deliberately a separate kind rather than a style flag,
    * because the platforms disagree about which one a given setting *is*: a `GtkSwitch` is
    * not a themed `GtkCheckButton`, and `UISwitch` is a different class from a checkbox
    * AppKit has and iOS does not. An app that picks one is making a platform-idiom choice,
    * and the framework should not silently make it for them.
    */
  case Toggle

  /** Blank, flexible space. Has no appearance of its own; its whole purpose is to take up
    * the room its siblings do not, which it does through [[Prop.Grow]].
    *
    * A `Row` with a `Spacer` between two children pushes them to the edges. This is how
    * every toolkit here expects that to be expressed, and it is why `Spacer` is a widget
    * rather than an alignment prop on the parent.
    */
  case Spacer

  /** Determinate or indeterminate progress, per [[Prop.Progress]]. */
  case ProgressBar

  /** A continuous value chosen by dragging, within [[Prop.Range]]. */
  case Slider

  /** Single-line text input that does not show what it holds.
    *
    * A separate kind rather than a flag on [[TextField]] because AppKit makes it one:
    * `NSSecureTextField` is a different class, and a renderer whose widget is chosen at
    * `create` cannot switch later. GTK could have done it with a property and Android with
    * an input type, but a kind that two renderers must have anyway is cheaper than a prop
    * that two renderers must refuse to honour.
    */
  case SecureField

  /** A spinner: work is happening and its extent is unknown.
    *
    * It has no "running" prop. It spins while it is mounted, which makes `Show(loading)` the
    * way to stop it — the same mechanism as any other conditional subtree, rather than a
    * second way to express the same thing.
    */
  case ActivityIndicator
}

/** Where a picture's data comes from.
  *
  * Deliberately only *local* data. The framework does not fetch, cache or retry: an app
  * that needs an image over the network already has an effect system for that, and
  * `RemoteData[E, ImageSource]` composes with everything else — loading and failure states
  * become the same exhaustive match as any other async value. Building an HTTP client and
  * a cache eviction policy into a UI framework would duplicate what the app already has,
  * and do it worse.
  */
enum ImageSource {
  case FromFile(path: String)
  case FromBytes(data: Array[Byte])
}

/** How a picture fills the space it is given. */
enum ContentFit {
  case Contain, Cover, Fill
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

  /** An explicit colour override for this widget's foreground, or `None` for the
    * platform's own token.
    */
  case Tint(color: Option[Rgb])

  /** An explicit colour override for this widget's background, or `None`. */
  case Fill(color: Option[Rgb])

  case Picture(source: Option[ImageSource])
  case Fit(value: ContentFit)

  /** Which way a [[WidgetKind.Scroll]] scrolls. Default [[Orientation.Vertical]].
    *
    * **Read at `create`, not at `update`.** On Android the two directions are different
    * widget classes (`ScrollView` and `HorizontalScrollView`), so a renderer cannot honour
    * a later change without replacing the widget. A renderer may ignore this prop in
    * `update`; the framework does not animate or toggle it.
    */
  case Axis(value: Orientation)

  /** How far along a [[WidgetKind.ProgressBar]] is.
    *
    * `Some(fraction)` for determinate, clamped to 0.0–1.0 by the renderer; **`None` means
    * indeterminate** — the work is happening and its extent is unknown. Every toolkit here
    * distinguishes the two, and the distinction is visible: an indeterminate bar animates
    * and a determinate one at 0.0 does not, so collapsing them would make "nothing has
    * happened yet" and "we cannot say" look identical.
    */
  case Progress(value: Option[Double])

  /** Where a [[WidgetKind.Slider]] currently sits, in the units of its [[Range]].
    *
    * Not a fraction. An app choosing a volume between 0 and 11 should say 7, not 0.636,
    * and a renderer whose control is integral underneath (Android's `SeekBar`) is the one
    * that converts — which is where the conversion belongs, since only it knows its own
    * resolution.
    */
  case Value(value: Double)

  /** A slider's bounds. Sent before [[Value]] by the framework, because a value outside the
    * range is meaningless and every toolkit clamps it silently.
    */
  case Range(min: Double, max: Double)

  case OnValueChange(handler: Double => Unit)

  /** How prominent text should be, relative to the platform's own foreground colours.
    *
    * Not a colour. There is deliberately no way to say "grey #767676": a theme that pushes
    * its own palette at every platform is how cross-platform apps come to look like none
    * of them, and it breaks dark mode and accessibility contrast settings that the platform
    * would otherwise handle. Roles map onto each platform's own tokens.
    */
  case TextEmphasis(value: Emphasis)
}

enum Emphasis {
  case Normal, Secondary
}

/** An app-supplied colour for a role, resolved by the framework and handed to the renderer.
  *
  * `None` means "the platform's own token", which is the default and the native-looking
  * choice. A renderer must treat `None` as "do not set a colour at all" rather than as a
  * colour of its own — that is the difference between following the user's theme and
  * ignoring it.
  */
final case class Rgb(r: Int, g: Int, b: Int)

enum TextRole {
  case Title, Body, Caption
}

enum Alignment {
  case Start, Center, End
}

enum Orientation {
  case Vertical, Horizontal
}

/** Rows for a virtualising container to pull from.
  *
  * This is the one place the framework hands control *to* the renderer. Everywhere else the
  * reconciler builds a tree and the renderer obeys; a `ListView`, `RecyclerView`,
  * `GtkListView` or `UITableView` instead asks for the row it is about to show, and recycles
  * the ones it is not. Implemented by the framework, called by the renderer.
  */
trait RowSource[H] {

  /** How many rows exist right now. */
  def count: Int

  /** Produce the handle for row `index`.
    *
    * `recycled` is a handle the renderer previously got from this source and is no longer
    * showing. Returning it re-bound — rather than a fresh one — is what makes scrolling a
    * long list cheap, and the framework does that by writing the new item into the row's
    * own signal, so only the widgets bound to changed fields are touched.
    */
  def bind(index: Int, recycled: Option[H]): H

  /** The renderer will never show this handle again; the framework may dispose it. */
  def discard(handle: H): Unit

  /** The renderer registers here to be told when the data changed. */
  def onInvalidate(callback: () => Unit): Unit
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

  /** Whether this renderer has a container that materialises only visible rows.
    *
    * Default `false`, and a renderer that says so is not deficient — the framework falls
    * back to mounting every row, which is correct, just heavier. That fallback is what lets
    * a renderer be written without a virtualising container on day one.
    */
  def supportsVirtualRows: Boolean = false

  /** Create a container that pulls rows from `source`. Only called when
    * [[supportsVirtualRows]] is true.
    */
  def createVirtualList(source: RowSource[Handle]): Handle =
    throw new UnsupportedOperationException(s"$platform cannot virtualise rows")

  /** Run `f` on the UI thread. Safe to call from any thread the platform permits.
    *
    * On Apple targets, "any thread" means the main thread or a Scala-created thread —
    * never a GCD queue, which segfaults in the GC allocator (S1).
    */
  def runOnUiThread(f: () => Unit): Unit
}
