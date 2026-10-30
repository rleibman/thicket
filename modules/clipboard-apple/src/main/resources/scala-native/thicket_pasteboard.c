/*
 * Copyright 2026 Roberto Leibman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * thicket-clipboard-apple's pasteboard calls (#123): NSPasteboard on macOS, UIPasteboard on iOS.
 *
 * In C, not Swift, though the pasteboards are Objective-C objects: a few messages through the
 * Objective-C runtime (objc_getClass, sel_registerName, objc_msgSend) are shorter than a Swift
 * library with its build script, an archive carried in the jar and a shim-gen ABI. Scala Native
 * compiles this file with its own clang (it is under resources/scala-native), so there is no
 * build step. It cannot be a .m file: Scala Native compiles .c, .cpp and .S only.
 *
 * Strings cross as UTF-8 bytes with a length, never NUL-terminated, so the text may hold any
 * character. NSString and CFString are toll-free bridged, so CoreFoundation makes and reads them.
 *
 * The frameworks are named in the object itself (LC_LINKER_OPTION), as thicket_keychain.c does,
 * so an app links them with no flags of its own. Call from the main thread.
 */
#ifdef __APPLE__

#include <CoreFoundation/CoreFoundation.h>
#include <TargetConditionals.h>
#include <objc/message.h>
#include <objc/runtime.h>
#include <stdlib.h>
#include <string.h>

__asm__(".linker_option \"-framework\", \"CoreFoundation\"");
__asm__(".linker_option \"-lobjc\"");
#if TARGET_OS_IPHONE
__asm__(".linker_option \"-framework\", \"UIKit\"");
#else
__asm__(".linker_option \"-framework\", \"AppKit\"");
/* NSPasteboardTypeString, "public.utf8-plain-text": an NSString, so a CFStringRef. */
extern const CFStringRef NSPasteboardTypeString;
#endif

/* Not in a public header, but exported by libobjc: what @autoreleasepool compiles to. */
extern void *objc_autoreleasePoolPush(void);
extern void objc_autoreleasePoolPop(void *);

/* The general pasteboard: [NSPasteboard generalPasteboard] / [UIPasteboard generalPasteboard]. */
static id general(void) {
#if TARGET_OS_IPHONE
  Class c = objc_getClass("UIPasteboard");
#else
  Class c = objc_getClass("NSPasteboard");
#endif
  if (!c) return NULL;
  return ((id (*)(id, SEL))objc_msgSend)((id)c, sel_registerName("generalPasteboard"));
}

/* Puts the text on the general pasteboard, replacing what was there. 1 when the pasteboard took
 * it; 0 when there is no pasteboard or the bytes are not UTF-8. */
int thk_pasteboard_set(const char *utf8, long long len) {
  void *pool = objc_autoreleasePoolPush();
  int ok = 0;
  id pb = general();
  CFStringRef s = CFStringCreateWithBytes(NULL, (const UInt8 *)utf8, len, kCFStringEncodingUTF8, false);
  if (pb && s) {
#if TARGET_OS_IPHONE
    ((void (*)(id, SEL, id))objc_msgSend)(pb, sel_registerName("setString:"), (id)s);
    ok = 1;
#else
    ((long (*)(id, SEL))objc_msgSend)(pb, sel_registerName("clearContents"));
    ok = ((BOOL (*)(id, SEL, id, id))objc_msgSend)(pb, sel_registerName("setString:forType:"), (id)s,
                                                   (id)NSPasteboardTypeString)
             ? 1
             : 0;
#endif
  }
  if (s) CFRelease(s);
  objc_autoreleasePoolPop(pool);
  return ok;
}

/* The general pasteboard's text: 1 and a malloc'd UTF-8 copy in *out (free with
 * thk_pasteboard_free), or 0 when it holds no text (empty, or an image only). On iOS 16 and later,
 * reading another app's text may show the system's "Allow Paste" prompt; a refusal reads as no
 * text. */
int thk_pasteboard_get(char **out, long long *outLen) {
  void *pool = objc_autoreleasePoolPush();
  int found = 0;
  *out = NULL;
  *outLen = 0;
  id pb = general();
  CFStringRef s = NULL;
  if (pb) {
#if TARGET_OS_IPHONE
    s = (CFStringRef)((id (*)(id, SEL))objc_msgSend)(pb, sel_registerName("string"));
#else
    s = (CFStringRef)((id (*)(id, SEL, id))objc_msgSend)(pb, sel_registerName("stringForType:"),
                                                          (id)NSPasteboardTypeString);
#endif
  }
  if (s) {
    CFIndex chars = CFStringGetLength(s);
    CFIndex cap = CFStringGetMaximumSizeForEncoding(chars, kCFStringEncodingUTF8);
    char *buf = malloc(cap > 0 ? (size_t)cap : 1);
    CFIndex used = 0;
    if (buf) {
      CFStringGetBytes(s, CFRangeMake(0, chars), kCFStringEncodingUTF8, 0, false, (UInt8 *)buf, cap, &used);
      *out = buf;
      *outLen = used;
      found = 1;
    }
  }
  objc_autoreleasePoolPop(pool);
  return found;
}

void thk_pasteboard_free(char *p) { free(p); }

/* The pasteboard's change count, which every write by any app raises; -1 with no pasteboard. */
long long thk_pasteboard_change_count(void) {
  id pb = general();
  return pb ? (long long)((long (*)(id, SEL))objc_msgSend)(pb, sel_registerName("changeCount")) : -1;
}

#endif
