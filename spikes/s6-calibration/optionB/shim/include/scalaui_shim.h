/*
 * S3 — the Scala Native <-> Swift C-ABI contract.
 *
 * Two rules drive every signature here, both inherited from earlier spikes:
 *
 *  1. NO STRUCTS BY VALUE anywhere a Scala callback is involved. S4 found that Scala
 *     Native 0.5.12 returns a small struct from a CFuncPtr in the wrong registers,
 *     silently. UIKit returns CGRect/CGSize/CGPoint by value constantly, so the shim
 *     flattens them: sui_view_set_frame takes four doubles, not a CGRect.
 *     (sui_probe_struct_* below exists purely to re-test that bug on arm64, whose ABI
 *     differs from the x86-64 one S4 measured.)
 *
 *  2. CONTEXT IS AN int64_t, NOT A void*. Callbacks cannot close over state, so every
 *     one carries a handle-table id. S7 found that Long <-> Ptr in Scala Native needs
 *     Intrinsics.castLongToRawPtr, and that the obvious `id.asInstanceOf[Ptr[Byte]]`
 *     compiles and throws at runtime. Typing the context as int64_t deletes that whole
 *     failure mode instead of documenting it.
 */
#ifndef SCALAUI_SHIM_H
#define SCALAUI_SHIM_H

#include <stdint.h>

#include "scalaui_shim_types.h"

/* --- lifecycle --------------------------------------------------------------- */
sui_handle sui_root_view(void);
sui_handle sui_view_new(void);
sui_handle sui_label_new(void);
sui_handle sui_button_new(void);
void sui_destroy(sui_handle h);

/* --- properties -------------------------------------------------------------- */
void sui_label_set_text(sui_handle h, const char *text);
void sui_button_set_title(sui_handle h, const char *title);
void sui_button_on_tap(sui_handle h, sui_tap_cb cb, int64_t ctx);

/* --- tree -------------------------------------------------------------------- */
void sui_view_add_child(sui_handle parent, sui_handle child);
void sui_view_remove(sui_handle h);
void sui_view_set_frame(sui_handle h, double x, double y, double w, double height);

/* --- threading --------------------------------------------------------------- */
/* Posts cb(ctx) to the main queue. Safe to call from a Scala-created thread; the
   callback itself then runs on the main thread, which is the only thread besides
   Scala's own that may re-enter Scala (S1 finding). */
void sui_run_on_main(sui_main_cb cb, int64_t ctx);

/* --- benchmark + diagnostics ------------------------------------------------- */
/* Invokes the button's stored tap callback n times from Swift, without going through
   UIKit event delivery, so the number measured is the C-ABI round trip itself. */
void sui_simulate_taps(sui_handle button, int32_t n);
/* Unlike sui_simulate_taps, this goes through real UIControl event dispatch, so it
   exercises the UIKit -> ObjC target/action -> C callback -> Scala path end to end. */
void sui_button_send_ui_action(sui_handle button);
double sui_rss_mb(void);
const char *sui_label_get_text(sui_handle h);
int32_t sui_live_handle_count(void);

/* --- struct-return probe (re-tests the S4 bug on arm64) ---------------------- */
/* sui_size and the two callback typedefs live in scalaui_shim_types.h. */
void sui_probe_struct_byvalue(sui_measure_byvalue_cb cb, int64_t ctx, sui_size *result);
void sui_probe_struct_outparam(sui_measure_outparam_cb cb, int64_t ctx, sui_size *result);

#endif
