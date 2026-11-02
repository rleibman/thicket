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
 * thicket-share-apple's share sheet (#134): UIActivityViewController on iOS, NSSharingServicePicker
 * on macOS.
 *
 * In C through the Objective-C runtime, as thicket_pasteboard.c is (#123), not Swift. The one
 * thing that looked like it needed Swift is the subject: iOS asks for it through
 * UIActivityItemSource, a protocol, so the text has to be an object that answers
 * activityViewController:subjectForActivityType:. The runtime makes that class in a few lines
 * (objc_allocateClassPair, class_addMethod, class_addProtocol), which is still less than a Swift
 * library with a build script, an archive carried in the jar and a shim-gen ABI. It cannot be a
 * .m file: Scala Native compiles .c, .cpp and .S only.
 *
 * Strings cross as UTF-8 bytes with a length. The frameworks are named in the object itself
 * (LC_LINKER_OPTION), so an app links them with no flags of its own. Call from the main thread.
 */
#ifdef __APPLE__

#include <CoreFoundation/CoreFoundation.h>
#include <CoreGraphics/CGGeometry.h>
#include <TargetConditionals.h>
#include <objc/message.h>
#include <objc/runtime.h>
#include <stdio.h>
#include <string.h>

__asm__(".linker_option \"-framework\", \"CoreFoundation\"");
__asm__(".linker_option \"-lobjc\"");
#if TARGET_OS_IPHONE
__asm__(".linker_option \"-framework\", \"UIKit\"");
#else
__asm__(".linker_option \"-framework\", \"AppKit\"");
#endif

/* Not in a public header, but exported by libobjc: what @autoreleasepool compiles to. */
extern void *objc_autoreleasePoolPush(void);
extern void objc_autoreleasePoolPop(void *);

static id send0(id o, const char *sel) { return ((id (*)(id, SEL))objc_msgSend)(o, sel_registerName(sel)); }
static id send1(id o, const char *sel, id a) {
  return ((id (*)(id, SEL, id))objc_msgSend)(o, sel_registerName(sel), a);
}
static long sendLong(id o, const char *sel) { return ((long (*)(id, SEL))objc_msgSend)(o, sel_registerName(sel)); }
static BOOL sendBool(id o, const char *sel) { return ((BOOL (*)(id, SEL))objc_msgSend)(o, sel_registerName(sel)); }
static id at(id array, long i) {
  return ((id (*)(id, SEL, unsigned long))objc_msgSend)(array, sel_registerName("objectAtIndex:"), (unsigned long)i);
}
static BOOL isKind(id o, const char *cls) {
  Class c = objc_getClass(cls);
  return o && c && ((BOOL (*)(id, SEL, Class))objc_msgSend)(o, sel_registerName("isKindOfClass:"), c);
}
static void release(id o) {
  if (o) send0(o, "release");
}

/* A +1 NSString (a CFString: toll-free bridged) from UTF-8 bytes, or NULL if they are not UTF-8. */
static id string(const char *utf8, long long len) {
  return (id)CFStringCreateWithBytes(NULL, (const UInt8 *)utf8, (CFIndex)len, kCFStringEncodingUTF8, false);
}

/* A +1 file NSURL (a CFURL) for the path, or NULL. */
static id fileURL(const char *utf8, long long len) {
  return (id)CFURLCreateFromFileSystemRepresentation(NULL, (const UInt8 *)utf8, (CFIndex)len, false);
}

/* A +1 NSArray of one object. */
static id one(id item) {
  const void *items[1] = {item};
  return (id)CFArrayCreate(NULL, items, 1, &kCFTypeArrayCallBacks);
}

/* Copies a CFString into buf as UTF-8, NUL-terminated and cut at cap. */
static void copyOut(id s, char *buf, long long cap) {
  if (cap <= 0) return;
  buf[0] = 0;
  if (s) CFStringGetCString((CFStringRef)s, buf, (CFIndex)cap, kCFStringEncodingUTF8);
}

/* How many share sheets this process has shown: a self-test's count. */
static long long shownCount = 0;
long long thk_share_shown_count(void) { return shownCount; }

#if TARGET_OS_IPHONE

/* ---- iOS ---------------------------------------------------------------------------------- */

/* The text and its subject, held by an object of a class made here at run time that adopts
 * UIActivityItemSource: the share sheet asks it for the text (placeholder and item) and, for an
 * activity that has one (Mail), the subject. Kept as associated objects, so they go with it. */
static const char textKey = 0, subjectKey = 0;

static id sourcePlaceholder(id self, SEL cmd, id vc) {
  (void)cmd;
  (void)vc;
  return objc_getAssociatedObject(self, &textKey);
}
static id sourceItem(id self, SEL cmd, id vc, id type) {
  (void)cmd;
  (void)vc;
  (void)type;
  return objc_getAssociatedObject(self, &textKey);
}
static id sourceSubject(id self, SEL cmd, id vc, id type) {
  (void)cmd;
  (void)vc;
  (void)type;
  id s = objc_getAssociatedObject(self, &subjectKey);
  return s ? s : (id)CFSTR("");
}

static Class sourceClass(void) {
  static Class cls = NULL;
  if (cls) return cls;
  if ((cls = objc_getClass("ThicketShareTextSource"))) return cls;
  Class c = objc_allocateClassPair(objc_getClass("NSObject"), "ThicketShareTextSource", 0);
  if (!c) return NULL;
  class_addMethod(c, sel_registerName("activityViewControllerPlaceholderItem:"), (IMP)sourcePlaceholder, "@@:@");
  class_addMethod(c, sel_registerName("activityViewController:itemForActivityType:"), (IMP)sourceItem, "@@:@@");
  class_addMethod(c, sel_registerName("activityViewController:subjectForActivityType:"), (IMP)sourceSubject,
                  "@@:@@");
  Protocol *p = objc_getProtocol("UIActivityItemSource");
  if (p) class_addProtocol(c, p);
  objc_registerClassPair(c);
  return cls = c;
}

/* The key window of the app's scenes, or the first window. */
static id window(void) {
  id app = send0((id)objc_getClass("UIApplication"), "sharedApplication");
  if (!app) return NULL;
  id scenes = send0(send0(app, "connectedScenes"), "allObjects");
  long n = sendLong(scenes, "count");
  id first = NULL;
  for (long i = 0; i < n; i++) {
    id scene = at(scenes, i);
    if (!isKind(scene, "UIWindowScene")) continue;
    id windows = send0(scene, "windows");
    long m = sendLong(windows, "count");
    for (long j = 0; j < m; j++) {
      id w = at(windows, j);
      if (sendBool(w, "isKeyWindow")) return w;
      if (!first) first = w;
    }
  }
  return first;
}

/* The view controller on top: the window's root, then whatever it presents, and so on (a Thicket
 * sheet, say), so the share sheet goes over everything already up. */
static id topController(void) {
  id w = window();
  id vc = w ? send0(w, "rootViewController") : NULL;
  for (id next = vc ? send0(vc, "presentedViewController") : NULL; next; next = send0(vc, "presentedViewController"))
    vc = next;
  return vc;
}

/* The last text item shown, kept (+1) so a self-test can ask it what UIKit asks it. */
static id lastSource = NULL;

/* Presents a UIActivityViewController for the one item. 1 when presented; 0 with no window. */
static int present(id item) {
  id top = topController();
  if (!top || !item) return 0;
  id items = one(item);
  Class avcClass = objc_getClass("UIActivityViewController");
  id avc = avcClass ? ((id (*)(id, SEL, id, id))objc_msgSend)(send0((id)avcClass, "alloc"),
                                                              sel_registerName("initWithActivityItems:applicationActivities:"),
                                                              items, NULL)
                    : NULL;
  release(items);
  if (!avc) return 0;
  /* On an iPad the sheet is a popover and must be anchored, or UIKit raises: the top controller's
   * view, at its centre. There is no popover on an iPhone. */
  id popover = send0(avc, "popoverPresentationController");
  if (popover) {
    id view = send0(top, "view");
    send1(popover, "setSourceView:", view);
    CGRect b = ((CGRect (*)(id, SEL))objc_msgSend)(view, sel_registerName("bounds"));
    ((void (*)(id, SEL, CGRect))objc_msgSend)(popover, sel_registerName("setSourceRect:"),
                                             CGRectMake(CGRectGetMidX(b), CGRectGetMidY(b), 1, 1));
  }
  ((void (*)(id, SEL, id, BOOL, id))objc_msgSend)(top, sel_registerName("presentViewController:animated:completion:"),
                                                  avc, YES, NULL);
  release(avc);
  shownCount++;
  return 1;
}

int thk_share_text(const char *utf8, long long len, const char *subject, long long subjectLen, int hasSubject) {
  void *pool = objc_autoreleasePoolPush();
  int ok = 0;
  Class c = sourceClass();
  id text = string(utf8, len);
  id subj = hasSubject ? string(subject, subjectLen) : NULL;
  if (c && text && (!hasSubject || subj)) {
    id source = send0(send0((id)c, "alloc"), "init");
    objc_setAssociatedObject(source, &textKey, text, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    if (subj) objc_setAssociatedObject(source, &subjectKey, subj, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    ok = present(source);
    if (ok) {
      release(lastSource);
      lastSource = source;
    } else
      release(source);
  }
  release(text);
  release(subj);
  objc_autoreleasePoolPop(pool);
  return ok;
}

int thk_share_file(const char *path, long long len) {
  void *pool = objc_autoreleasePoolPush();
  id url = fileURL(path, len);
  int ok = url ? present(url) : 0;
  release(url);
  objc_autoreleasePoolPop(pool);
  return ok;
}

/* For a self-test: the UIActivityViewController on top of the window, if one is. */
static id shownController(void) {
  id vc = topController();
  return isKind(vc, "UIActivityViewController") ? vc : NULL;
}

/* For a self-test: 1 when the share sheet is up; its class name in buf. */
int thk_share_showing(char *buf, long long cap) {
  id vc = topController();
  if (cap > 0) snprintf(buf, (size_t)cap, "%s", vc ? class_getName((Class)send0(vc, "class")) : "");
  return isKind(vc, "UIActivityViewController") ? 1 : 0;
}

/* For a self-test: takes the share sheet down, as its close button would. 1 if one was up. */
int thk_share_dismiss(void) {
  id vc = shownController();
  if (!vc) return 0;
  ((void (*)(id, SEL, BOOL, id))objc_msgSend)(vc, sel_registerName("dismissViewControllerAnimated:completion:"), YES,
                                              NULL);
  return 1;
}

/* For a self-test: what the last text shared answers an activity of `type` (e.g.
 * "com.apple.UIKit.activity.Mail") for its item and its subject, asked as UIKit asks it, with the
 * sheet on top as the controller. 1 if there is a sheet up and a text was shared. */
int thk_share_ask(const char *type, char *text, long long textCap, char *subject, long long subjectCap) {
  void *pool = objc_autoreleasePoolPush();
  int found = 0;
  id vc = shownController();
  if (vc && lastSource) {
    id t = (id)CFStringCreateWithCString(NULL, type, kCFStringEncodingUTF8);
    SEL item = sel_registerName("activityViewController:itemForActivityType:");
    SEL subj = sel_registerName("activityViewController:subjectForActivityType:");
    copyOut(((id (*)(id, SEL, id, id))objc_msgSend)(lastSource, item, vc, t), text, textCap);
    copyOut(((id (*)(id, SEL, id, id))objc_msgSend)(lastSource, subj, vc, t), subject, subjectCap);
    release(t);
    found = 1;
  }
  objc_autoreleasePoolPop(pool);
  return found;
}

/* macOS's, for one set of externs: the picker's delegate is macOS-only. */
int thk_share_proposed(char *buf, long long cap) {
  if (cap > 0) buf[0] = 0;
  return -1;
}

#else

/* ---- macOS -------------------------------------------------------------------------------- */

/* The picker shown last, kept (+1) while it is up: AppKit does not keep it. */
static id picker = NULL;

/* What the picker's delegate heard, for a self-test: the services it proposed, one title per line,
 * and how it ended (-1 not yet, 0 closed with no choice, 1 a service chosen). */
static char proposed[2048] = "";
static int outcome = -1;

/* sharingServicePicker:sharingServicesForItems:proposedSharingServices: — keeps every service the
 * system proposes, and notes their titles. AppKit asks more than once per picker (measured on macOS
 * 27: twice, with 5 then 0 services for a text, 6 then 9 for a file), so the titles accumulate. */
static id delegateServices(id self, SEL cmd, id p, id items, id services) {
  (void)self;
  (void)cmd;
  (void)items;
  if (p != picker) return services; /* an earlier picker's, late */
  long n = services ? sendLong(services, "count") : 0;
  for (long i = 0; i < n; i++) {
    char title[256], line[260];
    copyOut(send0(at(services, i), "title"), title, sizeof title);
    snprintf(line, sizeof line, "\n%s\n", title);
    size_t used = strlen(proposed);
    char padded[sizeof proposed + 2];
    snprintf(padded, sizeof padded, "\n%s\n", proposed);
    if (title[0] && !strstr(padded, line))
      snprintf(proposed + used, sizeof proposed - used, "%s%s", used ? "\n" : "", title);
  }
  return services;
}

/* sharingServicePicker:didChooseSharingService: — nil when it closed with no choice. */
static void delegateChose(id self, SEL cmd, id p, id service) {
  (void)self;
  (void)cmd;
  if (p == picker) outcome = service ? 1 : 0;
}

static id pickerDelegate(void) {
  static id instance = NULL;
  if (instance) return instance;
  Class c = objc_getClass("ThicketSharePickerDelegate");
  if (!c) {
    c = objc_allocateClassPair(objc_getClass("NSObject"), "ThicketSharePickerDelegate", 0);
    if (!c) return NULL;
    class_addMethod(c, sel_registerName("sharingServicePicker:sharingServicesForItems:proposedSharingServices:"),
                    (IMP)delegateServices, "@@:@@@");
    class_addMethod(c, sel_registerName("sharingServicePicker:didChooseSharingService:"), (IMP)delegateChose,
                    "v@:@@");
    Protocol *p = objc_getProtocol("NSSharingServicePickerDelegate");
    if (p) class_addProtocol(c, p);
    objc_registerClassPair(c);
  }
  return instance = send0(send0((id)c, "alloc"), "init");
}

/* The app's key window, else its main window, else its first visible one. */
static id window(void) {
  id app = send0((id)objc_getClass("NSApplication"), "sharedApplication");
  if (!app) return NULL;
  id w = send0(app, "keyWindow");
  if (!w) w = send0(app, "mainWindow");
  if (!w) {
    id windows = send0(app, "windows");
    long n = sendLong(windows, "count");
    for (long i = 0; i < n && !w; i++)
      if (sendBool(at(windows, i), "isVisible") && !isKind(at(windows, i), "NSPanel")) w = at(windows, i);
  }
  return w;
}

/* Shows an NSSharingServicePicker for the one item, below the window's content view. 1 when shown;
 * 0 with no window. Not modal: it is a popover, and the call returns at once (measured). */
static int present(id item) {
  id w = window();
  id view = w ? send0(w, "contentView") : NULL;
  Class pc = objc_getClass("NSSharingServicePicker");
  if (!view || !item || !pc) return 0;
  id items = one(item);
  id p = send1(send0((id)pc, "alloc"), "initWithItems:", items);
  release(items);
  if (!p) return 0;
  send1(p, "setDelegate:", pickerDelegate());
  if (picker && ((BOOL (*)(id, SEL, SEL))objc_msgSend)(picker, sel_registerName("respondsToSelector:"),
                                                       sel_registerName("close")))
    send0(picker, "close");
  release(picker);
  picker = p;
  proposed[0] = 0;
  outcome = -1;
#if defined(__x86_64__)
  CGRect b;
  ((void (*)(CGRect *, id, SEL))objc_msgSend_stret)(&b, view, sel_registerName("bounds"));
#else
  CGRect b = ((CGRect (*)(id, SEL))objc_msgSend)(view, sel_registerName("bounds"));
#endif
  /* NSMinYEdge (1): below the content view's bounds, so above its bottom edge on screen. */
  ((void (*)(id, SEL, CGRect, id, unsigned long))objc_msgSend)(
      p, sel_registerName("showRelativeToRect:ofView:preferredEdge:"), b, view, 1UL);
  shownCount++;
  return 1;
}

int thk_share_text(const char *utf8, long long len, const char *subject, long long subjectLen, int hasSubject) {
  (void)subject;
  (void)subjectLen;
  (void)hasSubject; /* No share service on macOS takes a subject for a plain text. */
  void *pool = objc_autoreleasePoolPush();
  id text = string(utf8, len);
  int ok = text ? present(text) : 0;
  release(text);
  objc_autoreleasePoolPop(pool);
  return ok;
}

int thk_share_file(const char *path, long long len) {
  void *pool = objc_autoreleasePoolPush();
  id url = fileURL(path, len);
  int ok = url ? present(url) : 0;
  release(url);
  objc_autoreleasePoolPop(pool);
  return ok;
}

/* For a self-test: the app's visible popover windows whose content is a remote view — the
 * picker's, which the sharing service draws out of process. Their count; the first's class names
 * (window, content view) in buf. */
int thk_share_showing(char *buf, long long cap) {
  id app = send0((id)objc_getClass("NSApplication"), "sharedApplication");
  id windows = app ? send0(app, "windows") : NULL;
  long n = windows ? sendLong(windows, "count") : 0;
  int count = 0;
  if (cap > 0) buf[0] = 0;
  for (long i = 0; i < n; i++) {
    id w = at(windows, i);
    id content = send0(w, "contentView");
    /* -class, not object_getClass: AppKit observes these windows, and KVO's subclass is the real one. */
    const char *wc = class_getName((Class)send0(w, "class"));
    if (!sendBool(w, "isVisible") || !strstr(wc, "Popover") || !content) continue;
    if (!isKind(content, "NSRemoteView")) continue;
    if (count++ == 0 && cap > 0) snprintf(buf, (size_t)cap, "%s/%s", wc, class_getName((Class)send0(content, "class")));
  }
  return count;
}

/* For a self-test: closes the picker, as clicking away would. 1 if there was one to close. */
int thk_share_dismiss(void) {
  if (!picker || !((BOOL (*)(id, SEL, SEL))objc_msgSend)(picker, sel_registerName("respondsToSelector:"),
                                                         sel_registerName("close")))
    return 0;
  send0(picker, "close");
  return 1;
}

/* For a self-test: the services the last picker proposed, one title per line, into buf; and how it
 * ended (-1 not yet, 0 closed with no choice, 1 chosen). */
int thk_share_proposed(char *buf, long long cap) {
  if (cap > 0) snprintf(buf, (size_t)cap, "%s", proposed);
  return outcome;
}

/* iOS's, for one set of externs: there is no item source on macOS. */
int thk_share_ask(const char *type, char *text, long long textCap, char *subject, long long subjectCap) {
  (void)type;
  (void)text;
  (void)textCap;
  (void)subject;
  (void)subjectCap;
  return 0;
}

#endif

#endif
