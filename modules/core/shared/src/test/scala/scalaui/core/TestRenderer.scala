package scalaui.core

import scala.collection.mutable
import scalaui.renderer.*

/** An in-memory renderer, so the reconciler can be tested without a toolkit.
  *
  * This doubles as the reference implementation of the contract: if something cannot be
  * expressed here, the contract is leaking platform assumptions.
  */
final class TestRenderer extends Renderer {
  final case class Node(
      id: Int,
      kind: WidgetKind,
      props: mutable.Map[String, String] = mutable.Map.empty,
      children: mutable.ArrayBuffer[Int] = mutable.ArrayBuffer.empty,
      var taps: Int = 0,
      var onTap: Option[() => Unit] = None,
      var onTextChange: Option[String => Unit] = None,
      var onCheckedChange: Option[Boolean => Unit] = None
  )

  type Handle = Int

  val nodes: mutable.Map[Int, Node] = mutable.Map.empty
  private var nextId                = 0
  var destroyed: List[Int]          = Nil

  /** Counters so tests can assert that reconciliation did *no* work, not merely that it
    * produced the right answer. "Right output via rebuild-everything" is the failure mode
    * keying exists to prevent.
    */
  var createCount: Int = 0
  var opCount: Int     = 0

  /** Writes that actually changed a widget. The contract says a renderer must not disturb a
    * widget written a value it already shows, so this stays flat when the app echoes a
    * user's edit back through a signal.
    */
  var textWrites: Int = 0

  def platform: String                         = "test"
  def layoutMode(kind: WidgetKind): LayoutMode = LayoutMode.ToolkitManaged

  def create(kind: WidgetKind, props: Seq[Prop]): Handle = {
    createCount += 1
    opCount += 1
    nextId += 1
    nodes(nextId) = Node(nextId, kind)
    update(nextId, props)
    nextId
  }

  def update(handle: Handle, patch: Seq[Prop]): Unit = {
    opCount += 1
    val n = nodes(handle)
    patch.foreach {
      case Prop.Text(v) =>
        if n.props.get("text").contains(v) then ()
        else {
          textWrites += 1
          n.props("text") = v
        }
      case Prop.Spacing(v) => n.props("spacing") = v.toString
      case Prop.Padding(v) => n.props("padding") = v.toString
      case Prop.Enabled(v) => n.props("enabled") = v.toString
      case Prop.OnTap(f)          => n.onTap = Some(f)
      case Prop.Placeholder(v)    => n.props("placeholder") = v
      case Prop.Checked(v)        => n.props("checked") = v.toString
      case Prop.OnTextChange(f)   => n.onTextChange = Some(f)
      case Prop.OnCheckedChange(f) => n.onCheckedChange = Some(f)
      case Prop.Style(role)       => n.props("style") = role.toString
      case Prop.Grow(v)           => n.props("grow") = v.toString
      case Prop.Align(a)          => n.props("align") = a.toString
      case Prop.TextEmphasis(e)   => n.props("emphasis") = e.toString
    }
  }

  def insertAfter(parent: Handle, child: Handle, after: Option[Handle]): Unit = {
    opCount += 1
    val kids = nodes(parent).children
    after match {
      case None => kids.prepend(child)
      case Some(a) =>
        // Appending is the overwhelmingly common case — mounting a list is n appends —
        // and `indexOf` would make that O(n^2). Check the tail first.
        if kids.nonEmpty && kids.last == a then kids.append(child)
        else kids.insert(kids.indexOf(a) + 1, child)
    }
  }

  def removeChild(parent: Handle, child: Handle): Unit = {
    opCount += 1
    val _ = nodes(parent).children.subtractOne(child)
  }

  /** Detaches as well as releases, per the contract. */
  def destroy(handle: Handle): Unit = {
    opCount += 1
    destroyed = handle :: destroyed
    nodes.valuesIterator.foreach(n => n.children.subtractOne(handle))
    val _ = nodes.remove(handle)
  }

  def measure(handle: Handle, c: Constraints): Measurement = {
    val len = nodes(handle).props.getOrElse("text", "").length.toFloat
    Measurement(minW = len, minH = 10f, natW = len * 8f, natH = 18f)
  }

  def setFrame(handle: Handle, frame: Frame): Unit = ()
  def runOnUiThread(f: () => Unit): Unit           = f()

  // -- test helpers ---------------------------------------------------------
  def text(handle: Handle): String       = nodes(handle).props.getOrElse("text", "")
  def kind(handle: Handle): WidgetKind   = nodes(handle).kind
  def childrenOf(handle: Handle): Seq[Int] = nodes(handle).children.toSeq
  /** Simulate the user typing. Mirrors a real renderer: the app is told, and the widget
    * shows what was typed — the app is *not* obliged to write it back.
    */
  def typeText(handle: Handle, value: String): Unit = {
    val n = nodes(handle)
    n.props("text") = value
    n.onTextChange.foreach(_(value))
  }

  def toggle(handle: Handle): Unit = {
    val n   = nodes(handle)
    val now = !n.props.getOrElse("checked", "false").toBoolean
    n.props("checked") = now.toString
    n.onCheckedChange.foreach(_(now))
  }

  def tap(handle: Handle): Unit = {
    val n = nodes(handle)
    n.taps += 1
    n.onTap.foreach(_())
  }
}
