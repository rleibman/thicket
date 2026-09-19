#ifndef SCALAUI_BRIDGING_HEADER_H
#define SCALAUI_BRIDGING_HEADER_H

// Scala Native emits this for a `libraryStatic` build. It must be called once before any
// exported function; it starts the GC and the runtime's thread bookkeeping.
extern void ScalaNativeInit(void);

extern char *scalaui_hello(void);
extern long long scalaui_alloc_stress(int seconds);
extern int scalaui_thread_test(int n);
extern char *scalaui_time(void);
extern void scalaui_register_callback(void (*cb)(char *));
extern void scalaui_fire(void);

#endif
