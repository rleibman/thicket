package scalaui.core

import scala.collection.mutable
import scalaui.renderer.*

/** An in-memory renderer, so the reconciler can be tested without a toolkit.
  *
  * This doubles as the reference implementation of the contract: if something cannot be
  * expressed here, the contract is leaking platform assumptions.
  */
final class TestRenderer extends Renderer:
  final case class Node(
      id: Int,
      kind: WidgetKind,
      props: mutable.Map[String, String] = mutable.Map.empty,
      children: mutable.ArrayBuffer[Int] = mutable.ArrayBuffer.empty,
      var taps: Int = 0,
      var onTap: Option[() => Unit] = None
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

  def platform: String                         = "test"
  def layoutMode(kind: WidgetKind): LayoutMode = LayoutMode.ToolkitManaged

  def create(kind: WidgetKind, props: Seq[Prop]): Handle =
    createCount += 1
    opCount += 1
    nextId += 1
    nodes(nextId) = Node(nextId, kind)
    update(nextId, props)
    nextId

  def update(handle: Handle, patch: Seq[Prop]): Unit =
    opCount += 1
    val n = nodes(handle)
    patch.foreach:
      case Prop.Text(v)    => n.props("text") = v
      case Prop.Spacing(v) => n.props("spacing") = v.toString
      case Prop.Padding(v) => n.props("padding") = v.toString
      case Prop.Enabled(v) => n.props("enabled") = v.toString
      case Prop.OnTap(f)   => n.onTap = Some(f)

  def insertAfter(parent: Handle, child: Handle, after: Option[Handle]): Unit =
    opCount += 1
    val kids = nodes(parent).children
    after match
      case None    => kids.prepend(child)
      case Some(a) => kids.insert(kids.indexOf(a) + 1, child)

  def removeChild(parent: Handle, child: Handle): Unit =
    opCount += 1
    val _ = nodes(parent).children.subtractOne(child)

  def destroy(handle: Handle): Unit =
    opCount += 1
    destroyed = handle :: destroyed
    val _ = nodes.remove(handle)

  def measure(handle: Handle, c: Constraints): Measurement =
    val len = nodes(handle).props.getOrElse("text", "").length.toFloat
    Measurement(minW = len, minH = 10f, natW = len * 8f, natH = 18f)

  def setFrame(handle: Handle, frame: Frame): Unit = ()
  def runOnUiThread(f: () => Unit): Unit           = f()

  // -- test helpers ---------------------------------------------------------
  def text(handle: Handle): String       = nodes(handle).props.getOrElse("text", "")
  def kind(handle: Handle): WidgetKind   = nodes(handle).kind
  def childrenOf(handle: Handle): Seq[Int] = nodes(handle).children.toSeq
  def tap(handle: Handle): Unit =
    val n = nodes(handle)
    n.taps += 1
    n.onTap.foreach(_())
