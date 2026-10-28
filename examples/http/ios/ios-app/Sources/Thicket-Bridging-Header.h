#ifndef HTTP_IOS_BRIDGING_HEADER_H
#define HTTP_IOS_BRIDGING_HEADER_H

/* Not thicket_http.h: the Swift library defines the thk_http_* functions with @_cdecl, and
   the host never calls them — Scala does. */

/* Provided by the Scala Native static archive. */
extern void ScalaNativeInit(void);
extern void thicket_http_start(void);

#endif
