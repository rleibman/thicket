/*
 * Types only — no function declarations.
 *
 * The split exists because Swift is on both sides of this header. The shim *implements*
 * these functions with @_cdecl, so if Swift also imported their C declarations it would
 * see two things with the same name and refuse. But Swift does need the typedefs and the
 * struct layout. Types here, functions in scalaui_shim.h, and Swift imports only this one.
 */
#ifndef SCALAUI_SHIM_TYPES_H
#define SCALAUI_SHIM_TYPES_H

#include <stdint.h>

/* Opaque UIView handle: Unmanaged<UIView>.toOpaque(), retained until sui_destroy. */
typedef void *sui_handle;

/* Context is int64_t, not void*: callbacks cannot close over state, so each carries a
   handle-table id. Typing it as a number avoids the Long <-> Ptr laundering that S7 had
   to do for GObject's gpointer, where the obvious cast compiles and throws at runtime. */
typedef void (*sui_tap_cb)(int64_t ctx);
typedef void (*sui_main_cb)(int64_t ctx);

typedef struct {
  double width;
  double height;
} sui_size;

typedef sui_size (*sui_measure_byvalue_cb)(int64_t ctx);
typedef void (*sui_measure_outparam_cb)(int64_t ctx, sui_size *out);

#endif
