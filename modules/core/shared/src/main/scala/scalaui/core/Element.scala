package scalaui.core

import scalaui.renderer.{Alignment, ContentFit, Emphasis, ImageSource, Orientation, Prop, Rgb as RRgb, TextRole, WidgetKind}
import scalaui.signals.Signal

/** A property of a widget, static or signal-driven. */
sealed trait Attr

object Attr {
  final case class Static(prop: Prop) extends Attr

  /** A property driven by a signal.
    *
    * The signal is kept *unmapped*, with the conversion carried alongside, because
    * `Signal.map` needs a `using Owner` and threading a lifetime through every DSL call
    * would put it in app code. The reconciler already owns a lifetime and maps at mount
    * time, so app code never mentions `Owner`.
    */
  final case class Reactive[A](signal: Signal[A], toProp: A => Prop) extends Attr
}

/** The declarative description of a UI.
  *
  * Most of a tree is [[Element.Widget]], which is fixed once mounted — only its properties
  * change. The two *dynamic* cases are where structure changes over time, and they are
  * deliberately the only two: everything else a UI needs to do structurally
  * (switch, optional, tabs) can be expressed with them, and each one costs real complexity
  * in the reconciler.
  */
sealed trait Element

object Element {
  final case class Widget(
      kind: WidgetKind,
      attrs: Seq[Attr],
      children: Seq[Element]
  ) extends Element

  /** Mounts `body` while `when` holds, and unmounts it — disposing its effects — when it
    * does not. `body` is by-name because it must be re-evaluated on each remount.
    */
  final case class Show(when: Signal[Boolean], body: () => Element) extends Element

  /** A keyed list. Items that keep their key keep their widgets, so reordering moves
    * existing widgets rather than rebuilding them — which is what preserves focus,
    * scroll position and animations.
    *
    * The body receives a `Signal[A]`, not an `A`. That is deliberate: when an item's key
    * survives but its *data* changes, the row must update without being rebuilt. Handing
    * the body a plain value would make the row's content a snapshot taken at mount time,
    * and the list would silently show stale data — which is the failure mode keying
    * otherwise introduces. With a signal, a changed field patches exactly the widget
    * bound to it.
    */
  final case class ForEach[A, K](
      items: Signal[Seq[A]],
      key: A => K,
      body: Signal[A] => Element
  ) extends Element

  /** Several elements in one slot, with no widget of their own. Lets a dynamic region
    * produce more than one child.
    */
  final case class Fragment(children: Seq[Element]) extends Element

  /** A long list, where only the visible rows need to exist.
    *
    * Semantically identical to [[ForEach]] — same keying, same `Signal[A]` per row — but it
    * asks the renderer for a virtualising container. Renderers that have one materialise
    * only what is on screen; the rest fall back to mounting every row, which is correct and
    * simply heavier. A row must have a single root widget, the same constraint every
    * recycling container imposes.
    */
  final case class LazyColumn[A, K](
      items: Signal[Seq[A]],
      key: A => K,
      body: Signal[A] => Element
  ) extends Element
}

/** The widget constructors.
  *
  * Plain functions with varargs children rather than the context-function builder sketched
  * in §7.3 — that syntax can be layered on later without changing any of this.
  */
object dsl {

  /** Resolve a role against the app's theme. `None` means "leave it to the platform",
    * which is both the default and the native-looking answer.
    */
  private def tintOf(role: ColorRole): Option[RRgb] =
    Theme.active.get(role).map(c => RRgb(c.r, c.g, c.b))

  import Attr.*
  import Element.*

  private def text(v: String | Signal[String]): Attr = v match {
    case s: String                    => Static(Prop.Text(s))
    case s: Signal[String] @unchecked => Reactive(s, Prop.Text(_))
  }

  def Label(
      value: String | Signal[String],
      style: TextRole = TextRole.Body,
      align: Alignment = Alignment.Start,
      emphasis: Emphasis = Emphasis.Normal
  ): Element =
    Widget(
      WidgetKind.Label,
      Seq(
        text(value),
        Static(Prop.Style(style)),
        Static(Prop.Align(align)),
        Static(Prop.TextEmphasis(emphasis)),
        Static(Prop.Tint(tintOf(emphasis match {
          case Emphasis.Secondary => ColorRole.OnSurfaceSecondary
          case Emphasis.Normal    => ColorRole.OnSurface
        })))
      ),
      Nil
    )

  /** A hairline rule, drawn at the platform's own weight and colour. */
  def Divider(): Element = Widget(WidgetKind.Divider, Nil, Nil)

  /** A picture from local data.
    *
    * There is no URL overload on purpose. Fetch with the app's effect system and render the
    * result — `RemoteData(imageBytes) { case Loading => …; case Done(src) => Image(src) }` —
    * so loading and failure are handled the same way as every other async value, and the
    * framework stays out of HTTP, caching and retry policy.
    */
  def Image(
      source: ImageSource | Signal[ImageSource],
      fit: ContentFit = ContentFit.Contain
  ): Element = {
    val attr = source match {
      case s: ImageSource                    => Static(Prop.Picture(Some(s)))
      case s: Signal[ImageSource] @unchecked => Reactive(s, v => Prop.Picture(Some(v)))
    }
    Widget(WidgetKind.Image, Seq(attr, Static(Prop.Fit(fit))), Nil)
  }

  def Button(
      value: String | Signal[String],
      enabled: Boolean = true,
      role: ColorRole = ColorRole.Accent
  )(onTap: => Unit): Element =
    Widget(
      WidgetKind.Button,
      Seq(
        text(value),
        Static(Prop.OnTap(() => onTap)),
        Static(Prop.Enabled(enabled)),
        Static(Prop.Fill(tintOf(role))),
        // A branded background carries its own foreground, derived if not given.
        Static(Prop.Tint(if role == ColorRole.Accent then tintOf(ColorRole.OnAccent) else None))
      ),
      Nil
    )

  /** A single-line text field bound to a signal.
    *
    * Two-way: the field shows `value`, and edits are reported through `onChange`. Nothing
    * here closes the loop for you — the app decides whether to write the edit back, which
    * is what makes validation, rejection and transformation possible.
    */
  def TextField(
      value: String | Signal[String],
      placeholder: String = ""
  )(onChange: String => Unit): Element =
    Widget(
      WidgetKind.TextField,
      Seq(text(value), Static(Prop.Placeholder(placeholder)), Static(Prop.OnTextChange(onChange))),
      Nil
    )

  def Checkbox(
      checked: Boolean | Signal[Boolean],
      label: String = ""
  )(onChange: Boolean => Unit): Element = {
    val checkedAttr = checked match {
      case b: Boolean                    => Static(Prop.Checked(b))
      case s: Signal[Boolean] @unchecked  => Reactive(s, Prop.Checked(_))
    }
    Widget(
      WidgetKind.Checkbox,
      Seq(checkedAttr, Static(Prop.Text(label)), Static(Prop.OnCheckedChange(onChange))),
      Nil
    )
  }

  /** A scrolling viewport around one child.
    *
    * `axis` is fixed for the widget's lifetime — see [[scalaui.renderer.Prop.Axis]]. A
    * horizontal `Scroll` around a `Row` is how an app handles a row too wide for the
    * screen; `Row` on its own clips.
    */
  def Scroll(padding: Int = 0, axis: Orientation = Orientation.Vertical)(
    child:           Element
  ): Element =
    Widget(
      WidgetKind.Scroll,
      Seq(Static(Prop.Padding(padding)), Static(Prop.Axis(axis))),
      Seq(child)
    )

  def Column(spacing: Int = 0, padding: Int = 0)(children: Element*): Element =
    Widget(
      WidgetKind.Column,
      Seq(Static(Prop.Spacing(spacing)), Static(Prop.Padding(padding))),
      children
    )

  def Row(spacing: Int = 0, padding: Int = 0)(children: Element*): Element =
    Widget(
      WidgetKind.Row,
      Seq(Static(Prop.Spacing(spacing)), Static(Prop.Padding(padding))),
      children
    )

  /** Re-render `view` whenever `signal` changes.
    *
    * `Show` keys on a boolean, so it mounts and unmounts. This keys on the value itself,
    * which is what an `RemoteData` match needs: Loading -> Done is a different subtree, not a
    * visibility change.
    */
  def Switch[A](signal: Signal[A])(view: A => Element): Element =
    Element.ForEach[A, A](signal.map(Seq(_)), identity, s => view(s.now))

  /** Show `body` only while `when` is true. */
  def Show(when: Signal[Boolean])(body: => Element): Element =
    Element.Show(when, () => body)

  /** Render one element per item, identified by `key`.
    *
    * `body` receives a `Signal[A]` so a row updates in place when its item's data changes.
    */
  def ForEach[A, K](items: Signal[Seq[A]], key: A => K)(
      body: Signal[A] => Element
  ): Element = Element.ForEach(items, key, body)

  /** As [[ForEach]], but asks the renderer to materialise only the visible rows.
    *
    * Use it when the list can be long. On a renderer without a virtualising container it
    * behaves exactly like `ForEach`.
    */
  def LazyColumn[A, K](items: Signal[Seq[A]], key: A => K)(
      body: Signal[A] => Element
  ): Element = Element.LazyColumn(items, key, body)

  /** Group elements without introducing a widget. */
  def Fragment(children: Element*): Element = Element.Fragment(children)

  /** Nothing. Useful as the `else` of a `Show`-like conditional. */
  val Empty: Element = Element.Fragment(Nil)

  /** Modifiers.
    *
    * Extension methods that add a property to an already-built element, so a row can be
    * made tappable or made to grow without every constructor growing another parameter.
    * `§7.3` left the choice between modifiers and named parameters open; both are here —
    * named parameters for what a widget always has, modifiers for what any widget might.
    */
  extension (element: Element) {

    /** Make any element respond to a tap, not just a `Button`.
      *
      * This is what a list row needs: a tappable container, with the platform's own press
      * feedback, rather than a button pretending to be a row.
      */
    def onTap(handler: => Unit): Element = withAttr(Static(Prop.OnTap(() => handler)))

    /** Absorb spare space along the parent's main axis. */
    def grow: Element = withAttr(Static(Prop.Grow(true)))

    def padding(dp: Int): Element = withAttr(Static(Prop.Padding(dp)))

    private def withAttr(attr: Attr): Element = element match {
      case w: Widget => w.copy(attrs = w.attrs :+ attr)
      case other     =>
        // Regions and fragments have no widget of their own to carry a property.
        other
    }
  }
}
