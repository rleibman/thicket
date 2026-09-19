#ifndef S3_BRIDGING_HEADER_H
#define S3_BRIDGING_HEADER_H

/* Types only. The shim implements the sui_* functions with @_cdecl, so Swift must NOT
   also see their C declarations — it would treat that as a redeclaration. */
#include "scalaui_shim_types.h"

/* Scala Native exports. ScalaNativeInit must run before scalaui_main. */
extern void ScalaNativeInit(void);
extern void scalaui_main(void *root);

#endif
