package scalaui.core

import scalaui.renderer.{Prop, WidgetKind}
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
}

/** The widget constructors.
  *
  * Plain functions with varargs children rather than the context-function builder sketched
  * in §7.3 — that syntax can be layered on later without changing any of this.
  */
object dsl {
  import Attr.*
  import Element.*

  private def text(v: String | Signal[String]): Attr = v match {
    case s: String                    => Static(Prop.Text(s))
    case s: Signal[String] @unchecked => Reactive(s, Prop.Text(_))
  }

  def Label(value: String | Signal[String]): Element =
    Widget(WidgetKind.Label, Seq(text(value)), Nil)

  def Button(value: String | Signal[String], enabled: Boolean = true)(
      onTap: => Unit
  ): Element =
    Widget(
      WidgetKind.Button,
      Seq(text(value), Static(Prop.OnTap(() => onTap)), Static(Prop.Enabled(enabled))),
      Nil
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

  /** Group elements without introducing a widget. */
  def Fragment(children: Element*): Element = Element.Fragment(children)

  /** Nothing. Useful as the `else` of a `Show`-like conditional. */
  val Empty: Element = Element.Fragment(Nil)
}
