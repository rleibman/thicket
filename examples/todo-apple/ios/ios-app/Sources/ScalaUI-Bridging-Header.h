#ifndef TODO_IOS_BRIDGING_HEADER_H
#define TODO_IOS_BRIDGING_HEADER_H

/* Types only. The shim implements the sui_* functions with @_cdecl, so Swift must not
   also see their C declarations — it would then emit them as imported calls and the
   definitions would collide (S3). */
#include "scalaui_apple_types.h"

/* Provided by the Swift shim, for the host's use rather than Scala's. */
extern void sui_set_root_view(void *root);

/* Provided by the Scala Native static archive. */
extern void ScalaNativeInit(void);
extern void scalaui_todo_start(void);

#endif
