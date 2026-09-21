package scalaui.yoga

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scalaui.yoga.generated.aliases.*
import scalaui.yoga.generated.structs.*
import scalaui.yoga.generated.enumerations.*

/** Scala Native rejects a `CFuncPtr` that closes over local state ("undefined
  * behaviour"), so callback state must live where the closure does not capture it.
  * This is the constraint that forces the handle-table design in docs/07 §7.6.
  */
object MeasureProbe {
  var direct: Int = 0
  var viaShim: Int = 0

  /** Yoga's native signature: returns YGSize **by value**. */
  val directFn: YGMeasureFunc = YGMeasureFunc(
    CFuncPtr5.fromScalaFunction {
      (_: YGNodeConstRef, _: Float, _: YGMeasureMode, _: Float, _: YGMeasureMode) =>
        direct += 1
        val out = stackalloc[YGSize]()
        (!out).width = 111f
        (!out).height = 222f
        !out
    }
  )

  /** Out-parameter form used by the C trampoline: no struct return. */
  val shimFn: MeasureShim.ScalaMeasureFn = CFuncPtr6.fromScalaFunction {
    (
        _: YGNodeConstRef,
        _: Float,
        _: YGMeasureMode,
        _: Float,
        _: YGMeasureMode,
        out: Ptr[YGSize]
    ) =>
      viaShim += 1
      (!out).width = 111f
      (!out).height = 222f
  }
}

class YogaSuite extends munit.FunSuite {

  private val Row    = YGFlexDirection.YGFlexDirectionRow
  private val Column = YGFlexDirection.YGFlexDirectionColumn
  private val LTR    = YGDirection.YGDirectionLTR

  private def measureRoot(): (YogaNode, YogaNode) = {
    val root = YogaNode()
    root.setWidth(500f)
    root.setHeight(500f)
    root.setFlexDirection(Column)
    // align-items defaults to `stretch`, which would size the leaf's cross axis from
    // the parent and hide the measured width. flex-start makes it content-sized.
    root.setAlignItems(YGAlign.YGAlignFlexStart)
    (root, YogaNode())
  }

  test("basic flex layout matches CSS semantics") {
    val root = YogaNode()
    root.setWidth(300f)
    root.setHeight(100f)
    root.setFlexDirection(Row)

    val a = YogaNode(); a.setFlexGrow(1f)
    val b = YogaNode(); b.setWidth(100f)
    root.insertChild(a, 0)
    root.insertChild(b, 1)

    root.calculateLayout(Float.NaN, Float.NaN, LTR)

    assertEquals(root.frame, (0f, 0f, 300f, 100f))
    assertEquals(a.frame, (0f, 0f, 200f, 100f), "flexGrow child takes the remaining 200")
    assertEquals(b.frame, (200f, 0f, 100f, 100f))
    root.freeRecursive()
  }

  test("KNOWN BAD: struct-by-value return from a Scala CFuncPtr corrupts the result") {
    // Documents the Scala Native 0.5.12 limitation this spike found. If this test
    // ever starts failing, the upstream bug is fixed and MeasureShim can be deleted.
    MeasureProbe.direct = 0
    val (root, leaf) = measureRoot()
    leaf.setMeasureFunc(MeasureProbe.directFn)
    root.insertChild(leaf, 0)
    root.calculateLayout(Float.NaN, Float.NaN, LTR)

    println(f"[S4] direct (struct by value): called=${MeasureProbe.direct} -> " +
      f"${leaf.layoutWidth}%.1f x ${leaf.layoutHeight}%.1f, wanted 111.0 x 222.0")

    assert(MeasureProbe.direct > 0, "Yoga did invoke the Scala function")
    assert(
      leaf.layoutWidth != 111f || leaf.layoutHeight != 222f,
      "struct-by-value return now works; delete MeasureShim and this test"
    )
    root.freeRecursive()
  }

  test("measure callback via the C trampoline returns correct dimensions") {
    MeasureProbe.viaShim = 0
    val (root, leaf) = measureRoot()
    MeasureShim.sui_set_measure_func(leaf.ref, MeasureProbe.shimFn)
    root.insertChild(leaf, 0)
    root.calculateLayout(Float.NaN, Float.NaN, LTR)

    println(f"[S4] via C trampoline: called=${MeasureProbe.viaShim} -> " +
      f"${leaf.layoutWidth}%.1f x ${leaf.layoutHeight}%.1f, wanted 111.0 x 222.0")

    assert(MeasureProbe.viaShim > 0, "trampoline never reached Scala")
    assertEquals(leaf.layoutWidth, 111f, "measured width survived the C ABI")
    assertEquals(leaf.layoutHeight, 222f, "measured height survived the C ABI")
    root.freeRecursive()
  }

  test("1000-node tree lays out correctly and fast enough") {
    // 10 columns x 100 rows, mixed fixed and flexible.
    val root = YogaNode()
    root.setWidth(390f)
    root.setHeight(844f)
    root.setFlexDirection(Row)
    var c = 0
    while c < 10 do {
      val col = YogaNode()
      col.setFlexGrow(1f)
      col.setFlexDirection(Column)
      var r = 0
      while r < 100 do {
        val cell = YogaNode()
        if r % 3 == 0 then cell.setHeight(8f) else cell.setFlexGrow(1f)
        cell.margin(YGEdge.YGEdgeAll, 1f)
        col.insertChild(cell, r)
        r += 1
      }
      root.insertChild(col, c)
      c += 1
    }

    root.calculateLayout(Float.NaN, Float.NaN, LTR)
    assertEquals(root.childCount, 10)
    assertEquals(root.frame, (0f, 0f, 390f, 844f))
    assertEquals(root.childAt(0).layoutWidth, 39f, "10 equal flex columns of 390pt")

    val iterations = 100
    val t0         = System.nanoTime()
    var i          = 0
    while i < iterations do {
      root.setWidth(390f + (i % 2).toFloat) // dirty the tree so layout really re-runs
      root.calculateLayout(Float.NaN, Float.NaN, LTR)
      i += 1
    }
    val perPassMs = (System.nanoTime() - t0) / 1e6 / iterations

    println(f"[S4] 1001-node layout pass: $perPassMs%.3f ms (target < 2 ms)")
    assert(perPassMs < 2.0, f"layout took $perPassMs%.3f ms per pass, target < 2 ms")
    root.freeRecursive()
  }
}
