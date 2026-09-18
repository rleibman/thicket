/*
 * S4 workaround: Scala Native 0.5.12 cannot return a small struct by value from a
 * CFuncPtr. Yoga's YGMeasureFunc returns YGSize{float,float} by value, so a Scala
 * measure function returns the fields in the wrong registers (observed: Yoga reads
 * width=<our height>, height=0).
 *
 * This trampoline flips the struct return into an out-parameter, which Scala Native
 * handles correctly. It is exactly the "thin C shim" pattern docs/07 §7.6 proposes
 * for UIKit, arrived at independently here for a pure-C library.
 */
#include <yoga/Yoga.h>

typedef void (*sui_measure_fn)(
    YGNodeConstRef node,
    float width, YGMeasureMode widthMode,
    float height, YGMeasureMode heightMode,
    YGSize *out);

static sui_measure_fn sui_scala_measure = 0;

static YGSize sui_measure_trampoline(
    YGNodeConstRef node,
    float width, YGMeasureMode widthMode,
    float height, YGMeasureMode heightMode) {
  YGSize out = {0.0f, 0.0f};
  if (sui_scala_measure) {
    sui_scala_measure(node, width, widthMode, height, heightMode, &out);
  }
  return out;
}

/* One global callback is enough for the spike; the framework would key on the node
   pointer or on YGNodeSetContext. */
void sui_set_measure_func(YGNodeRef node, sui_measure_fn fn) {
  sui_scala_measure = fn;
  YGNodeSetMeasureFunc(node, sui_measure_trampoline);
}
