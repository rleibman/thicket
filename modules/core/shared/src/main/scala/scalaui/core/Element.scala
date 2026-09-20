package scalaui.core

import scalaui.renderer.{Prop, WidgetKind}
import scalaui.signals.Signal

/** The declarative description of a UI. Cheap to build and throw away; the reconciler
  * turns it into retained native widgets.
  *
  * Properties may be static or reactive. A reactive property does not make the element
  * tree reactive: the reconciler subscribes to it and patches exactly that one widget,
  * which is the fine-grained-updates property inherited from the signals layer.
  */
sealed trait Attr

object Attr:
  final case class Static(prop: Prop) extends Attr

  /** A property driven by a signal.
    *
    * The signal is kept *unmapped*, with the conversion carried alongside it, because
    * `Signal.map` requires a `using Owner` (S5) and threading a lifetime through every DSL
    * call would put it in app code. The reconciler already owns a lifetime, so it does the
    * mapping at mount time and the DSL stays free of it.
    */
  final case class Reactive[A](signal: Signal[A], toProp: A => Prop) extends Attr

final case class Element(
    kind: WidgetKind,
    attrs: Seq[Attr],
    children: Seq[Element]
)

/** The widget constructors. Deliberately plain functions taking varargs children rather
  * than the context-function builder sketched in §7.3 — that syntax can be layered on
  * later without changing this, and starting simple keeps the reconciler honest.
  */
object dsl:
  import Attr.*

  private def text(v: String | Signal[String]): Attr = v match
    case s: String                    => Static(Prop.Text(s))
    case s: Signal[String] @unchecked => Reactive(s, Prop.Text(_))

  def Label(value: String | Signal[String]): Element =
    Element(WidgetKind.Label, Seq(text(value)), Nil)

  def Button(value: String | Signal[String], enabled: Boolean = true)(
      onTap: => Unit
  ): Element =
    Element(
      WidgetKind.Button,
      Seq(text(value), Static(Prop.OnTap(() => onTap)), Static(Prop.Enabled(enabled))),
      Nil
    )

  def Column(spacing: Int = 0, padding: Int = 0)(children: Element*): Element =
    Element(
      WidgetKind.Column,
      Seq(Static(Prop.Spacing(spacing)), Static(Prop.Padding(padding))),
      children
    )

  def Row(spacing: Int = 0, padding: Int = 0)(children: Element*): Element =
    Element(
      WidgetKind.Row,
      Seq(Static(Prop.Spacing(spacing)), Static(Prop.Padding(padding))),
      children
    )
