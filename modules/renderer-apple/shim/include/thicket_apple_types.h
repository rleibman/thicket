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

#endif
