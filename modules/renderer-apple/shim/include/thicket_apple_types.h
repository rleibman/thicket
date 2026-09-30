/*
 * Types only — no function declarations.
 *
 * Swift implements the sui_* functions with @_cdecl, so it must not also import their C
 * declarations: that is a redeclaration error. Swift imports this file; sn-bindgen reads
 * thicket_appkit.h, which includes it (S3).
 */
#ifndef THICKET_APPLE_TYPES_H
#define THICKET_APPLE_TYPES_H

#include <stdint.h>

/* Opaque NSView handle: Unmanaged<NSView>.toOpaque(), retained until sui_destroy. */
typedef void *sui_handle;

/* Context is int64_t, never void*: callbacks cannot close over state, so each carries a
   handle-table id, and a numeric context avoids the Long<->Ptr laundering (S7). */
typedef void (*sui_void_cb)(int64_t ctx);
typedef void (*sui_text_cb)(int64_t ctx, const char *value);
typedef void (*sui_bool_cb)(int64_t ctx, int32_t value);

/* Asks Scala for the view of row `index`, returning it. `recycled` is a view the table is
   no longer showing, or NULL; handing it back is what makes scrolling cheap.

   This is the only callback that returns a value. A pointer is a single register, which is
   not the case S4 and S3 found broken — that was a small *struct* returned by value, in the
   wrong registers and silently. Verified here rather than assumed: see the round-trip check
   in the renderer's REPORT. */
typedef sui_handle (*sui_row_cb)(int64_t ctx, int32_t index, sui_handle recycled);
/* A slider's new value, in the app's own units. */
typedef void (*sui_value_cb)(int64_t ctx, double value);

#endif
