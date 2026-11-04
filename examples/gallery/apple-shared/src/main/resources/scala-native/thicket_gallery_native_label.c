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
 * The gallery's Platform section on Apple (#145, docs/17 §17.3): a native label made outside the
 * renderer and its shim, as a camera preview's -apple module makes its view — an NSTextField on
 * macOS, a UILabel on iOS — handed to the renderer +1 retained, as ApplePlatformView.create says.
 *
 * And what the self-test asks of it: whether it is still in a superview, and — through a weak
 * reference, which libobjc clears when the object is deallocated and which holds no retain — whether
 * it is still alive at all. Reading a freed view's text would be a use-after-free; asking a weak
 * reference is not.
 *
 * In C through the Objective-C runtime, as thicket-clipboard-apple's pasteboard is: Scala Native
 * compiles this (it is under resources/scala-native), so there is no build step. Not ARC: the
 * retains are written out. Call from the main thread.
 */
#ifdef __APPLE__

#include <CoreFoundation/CoreFoundation.h>
#include <TargetConditionals.h>
#include <objc/message.h>
#include <objc/runtime.h>
#include <stdlib.h>

__asm__(".linker_option \"-framework\", \"CoreFoundation\"");
__asm__(".linker_option \"-lobjc\"");
#if TARGET_OS_IPHONE
__asm__(".linker_option \"-framework\", \"UIKit\"");
#else
__asm__(".linker_option \"-framework\", \"AppKit\"");
#endif

/* Exported by libobjc, not in a public header: what @autoreleasepool and __weak compile to. */
extern void *objc_autoreleasePoolPush(void);
extern void objc_autoreleasePoolPop(void *);
extern id objc_initWeak(id *location, id value);
extern id objc_loadWeakRetained(id *location);
extern void objc_destroyWeak(id *location);
extern void objc_release(id value);

/* A new label showing `utf8`, in no superview, with one retain the caller owns. */
void *thk_gallery_label_new(const char *utf8) {
  void *pool = objc_autoreleasePoolPush();
  CFStringRef s = CFStringCreateWithCString(NULL, utf8, kCFStringEncodingUTF8);
  id label = NULL;
#if TARGET_OS_IPHONE
  /* alloc/init: the +1 is the caller's. */
  label = ((id (*)(id, SEL))objc_msgSend)((id)objc_getClass("UILabel"), sel_registerName("alloc"));
  label = ((id (*)(id, SEL))objc_msgSend)(label, sel_registerName("init"));
  ((void (*)(id, SEL, id))objc_msgSend)(label, sel_registerName("setText:"), (id)s);
  ((void (*)(id, SEL, long))objc_msgSend)(label, sel_registerName("setNumberOfLines:"), 0);
#else
  /* labelWithString: is autoreleased, so it is retained once for the caller before the pool goes. */
  label = ((id (*)(id, SEL, id))objc_msgSend)((id)objc_getClass("NSTextField"), sel_registerName("labelWithString:"),
                                              (id)s);
  ((id (*)(id, SEL))objc_msgSend)(label, sel_registerName("retain"));
#endif
  CFRelease(s);
  objc_autoreleasePoolPop(pool);
  return label;
}

/* 1 when the view is in a superview. Only for a view known to be alive. */
int thk_gallery_has_superview(void *view) {
  return ((id (*)(id, SEL))objc_msgSend)((id)view, sel_registerName("superview")) != NULL ? 1 : 0;
}

/* A weak reference to `view`, holding no retain: thk_gallery_weak_alive reads it. */
void *thk_gallery_weak_new(void *view) {
  id *slot = malloc(sizeof(id));
  *slot = NULL;
  objc_initWeak(slot, (id)view);
  return slot;
}

/* 1 while the object the weak reference names has not been deallocated. */
int thk_gallery_weak_alive(void *slot) {
  id o = objc_loadWeakRetained((id *)slot);
  if (o == NULL) return 0;
  objc_release(o);
  return 1;
}

void thk_gallery_weak_free(void *slot) {
  objc_destroyWeak((id *)slot);
  free(slot);
}

#endif
