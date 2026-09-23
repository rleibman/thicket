/*
 * The Scala <-> AppKit contract.
 *
 * NO STRUCTS BY VALUE anywhere a Scala callback is involved, and none returned across the
 * boundary either: S4 found Scala Native returns a small struct from a CFuncPtr in the
 * wrong registers, silently, and S3 confirmed it still does on arm64 with a *different*
 * wrong answer. NSRect/NSSize are pervasive in AppKit, so everything here is flattened to
 * scalars or out-parameters.
 */
#ifndef SCALAUI_APPKIT_H
#define SCALAUI_APPKIT_H

#include <stdint.h>

#include "scalaui_appkit_types.h"

/* --- lifecycle ---------------------------------------------------------------- */
void sui_app_start(int32_t width, int32_t height, const char *title, sui_void_cb ready, int64_t ctx);
sui_handle sui_root_view(void);
void sui_window_set_title(const char *title);

/* --- widget construction ------------------------------------------------------- */
/* kind: 0 Column, 1 Row, 2 Label, 3 Button, 4 TextField, 5 Checkbox, 6 Scroll, 7 Divider */
sui_handle sui_create(int32_t kind);
void sui_destroy(sui_handle h);

/* --- properties ---------------------------------------------------------------- */
void sui_set_text(sui_handle h, const char *text);
void sui_set_placeholder(sui_handle h, const char *text);
void sui_set_checked(sui_handle h, int32_t on);
int32_t sui_get_checked(sui_handle h);
void sui_set_enabled(sui_handle h, int32_t on);
void sui_set_spacing(sui_handle h, int32_t dp);
void sui_set_padding(sui_handle h, int32_t dp);
/* role: 0 Title, 1 Body, 2 Caption */
void sui_set_text_role(sui_handle h, int32_t role);
/* emphasis: 0 Normal, 1 Secondary */
void sui_set_text_emphasis(sui_handle h, int32_t emphasis);
void sui_set_grow(sui_handle h, int32_t on);
/* align: 0 Start, 1 Center, 2 End */
void sui_set_align(sui_handle h, int32_t align);

/* --- events -------------------------------------------------------------------- */
void sui_on_tap(sui_handle h, sui_void_cb cb, int64_t ctx);
void sui_on_text_change(sui_handle h, sui_text_cb cb, int64_t ctx);
void sui_on_checked_change(sui_handle h, sui_bool_cb cb, int64_t ctx);

/* --- tree ----------------------------------------------------------------------- */
void sui_insert_after(sui_handle parent, sui_handle child, sui_handle after);
void sui_remove_child(sui_handle parent, sui_handle child);

/* --- layout ---------------------------------------------------------------------- */
/* Out-parameters, not a returned NSSize: see the header comment. maxW/maxH are NaN for
   "unconstrained", matching Constraints. */
void sui_measure(sui_handle h, double maxW, double maxH, double *outMinW, double *outMinH,
                 double *outNatW, double *outNatH);
void sui_set_frame(sui_handle h, double x, double y, double w, double height);

/* --- threading -------------------------------------------------------------------- */
void sui_run_on_main(sui_void_cb cb, int64_t ctx);

/* --- inspection, for the self-test ------------------------------------------------ */
int32_t sui_child_count(sui_handle h);
sui_handle sui_child_at(sui_handle h, int32_t index);
/* Returns NULL when the view carries no text. The buffer is owned by the shim and valid
   until the next call, so Scala must copy before calling again. */
const char *sui_get_text(sui_handle h);
int32_t sui_is_text_bearing(sui_handle h);

#endif
