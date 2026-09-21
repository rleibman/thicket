package scalaui.yoga

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scalaui.yoga.generated.functions.*
import scalaui.yoga.generated.aliases.*
import scalaui.yoga.generated.enumerations.*

/** A thin, idiomatic wrapper over Yoga's C API.
  *
  * Deliberately minimal: S4 is testing whether the binding *works*, not designing the
  * layout API. Nodes are manually freed — Yoga owns C memory that the Scala Native GC
  * knows nothing about, which is itself a finding for the renderer design.
  */
final class YogaNode private (private[yoga] val ref: YGNodeRef) {

  /** sn-bindgen maps `YGNode*` and `const YGNode*` to two *distinct* opaque types,
    * both `Ptr[YGNode]` underneath, with no conversion between them. Every read-only
    * call therefore needs this widening. See REPORT.md.
    */
  private def constRef: YGNodeConstRef = ref.asInstanceOf[YGNodeConstRef]

  def free(): Unit           = YGNodeFree(ref)
  def freeRecursive(): Unit  = YGNodeFreeRecursive(ref)

  // Plain setters rather than `x.width = v`: Scala 3 requires a matching getter for
  // assignment syntax, and Yoga's style getters are not free.
  def setWidth(v: Float): Unit     = YGNodeStyleSetWidth(ref, v)
  def setHeight(v: Float): Unit    = YGNodeStyleSetHeight(ref, v)
  def setFlexGrow(v: Float): Unit  = YGNodeStyleSetFlexGrow(ref, v)
  def setFlexBasis(v: Float): Unit = YGNodeStyleSetFlexBasis(ref, v)
  def margin(edge: YGEdge, v: Float): Unit  = YGNodeStyleSetMargin(ref, edge, v)
  def padding(edge: YGEdge, v: Float): Unit = YGNodeStyleSetPadding(ref, edge, v)

  def setFlexDirection(d: YGFlexDirection): Unit = YGNodeStyleSetFlexDirection(ref, d)
  def setJustifyContent(j: YGJustify): Unit      = YGNodeStyleSetJustifyContent(ref, j)
  def setAlignItems(a: YGAlign): Unit            = YGNodeStyleSetAlignItems(ref, a)

  def insertChild(child: YogaNode, index: Int): Unit =
    YGNodeInsertChild(ref, child.ref, index.toUInt)

  def childCount: Int = YGNodeGetChildCount(constRef).toInt

  def childAt(index: Int): YogaNode = new YogaNode(YGNodeGetChild(ref, index.toUInt))

  /** Leaves report their own intrinsic size — this is how a native widget's
    * `sizeThatFits` / `measure()` reaches the layout engine (docs/07 §7.5).
    */
  def setMeasureFunc(f: YGMeasureFunc): Unit = YGNodeSetMeasureFunc(ref, f)

  def calculateLayout(w: Float, h: Float, dir: YGDirection): Unit =
    YGNodeCalculateLayout(ref, w, h, dir)

  def left: Float   = YGNodeLayoutGetLeft(constRef)
  def top: Float    = YGNodeLayoutGetTop(constRef)
  def layoutWidth: Float  = YGNodeLayoutGetWidth(constRef)
  def layoutHeight: Float = YGNodeLayoutGetHeight(constRef)

  def frame: (Float, Float, Float, Float) = (left, top, layoutWidth, layoutHeight)
}

object YogaNode {
  def apply(): YogaNode = new YogaNode(YGNodeNew())
}
