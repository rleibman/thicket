#ifndef S9_BRIDGING_HEADER_H
#define S9_BRIDGING_HEADER_H

/* Types only: the shim implements the sui_* functions with @_cdecl, so Swift must not
   also see their C declarations (S3). */
#include "scalaui_shim_types.h"

extern void ScalaNativeInit(void);
extern void scalaui_s9_start(void *root);

#endif
