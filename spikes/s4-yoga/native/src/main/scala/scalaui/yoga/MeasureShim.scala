package scalaui.yoga

import scala.scalanative.unsafe.*
import scalaui.yoga.generated.aliases.YGNodeRef
import scalaui.yoga.generated.structs.YGSize
import scalaui.yoga.generated.aliases.YGNodeConstRef
import scalaui.yoga.generated.enumerations.YGMeasureMode

/** Bindings to `measure_shim.c` — the out-parameter form of Yoga's measure callback.
  * See the C file for why this exists.
  */
@extern
object MeasureShim {
  type ScalaMeasureFn =
    CFuncPtr6[YGNodeConstRef, Float, YGMeasureMode, Float, YGMeasureMode, Ptr[YGSize], Unit]

  def sui_set_measure_func(node: YGNodeRef, fn: ScalaMeasureFn): Unit = extern
}
