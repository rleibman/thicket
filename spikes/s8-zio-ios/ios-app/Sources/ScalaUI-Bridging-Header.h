#ifndef S8_BRIDGING_HEADER_H
#define S8_BRIDGING_HEADER_H

/* Types only: the shim implements the sui_* functions with @_cdecl, so Swift must not
   also see their C declarations. */
#include "scalaui_shim_types.h"

extern void ScalaNativeInit(void);
extern void scalaui_zio_start(void *root, int tick_seconds);

#endif
