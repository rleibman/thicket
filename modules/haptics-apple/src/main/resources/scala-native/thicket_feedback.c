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
 * thicket-haptics-apple's feedback calls (#123): UIImpactFeedbackGenerator and
 * UISelectionFeedbackGenerator on iOS, NSHapticFeedbackManager's performer on macOS.
 *
 * In C through the Objective-C runtime, as thicket_pasteboard.c is, and for the same reason:
 * a handful of messages, compiled by Scala Native's own clang with no build step. The frameworks
 * are named in the object (LC_LINKER_OPTION). Call from the main thread.
 *
 * Strength: 0 light, 1 medium, 2 heavy - UIImpactFeedbackStyleLight/Medium/Heavy on iOS. macOS has
 * patterns, not strengths: light is NSHapticFeedbackPatternAlignment (the subtlest), medium Generic,
 * heavy LevelChange (the deeper click of a Force Touch level).
 */
#ifdef __APPLE__

#include <TargetConditionals.h>
#include <objc/message.h>
#include <objc/runtime.h>

__asm__(".linker_option \"-lobjc\"");
#if TARGET_OS_IPHONE
__asm__(".linker_option \"-framework\", \"UIKit\"");
#else
__asm__(".linker_option \"-framework\", \"AppKit\"");
#endif

extern void *objc_autoreleasePoolPush(void);
extern void objc_autoreleasePoolPop(void *);

static id send(id o, const char *sel) { return ((id (*)(id, SEL))objc_msgSend)(o, sel_registerName(sel)); }

#if TARGET_OS_IPHONE

/* One generator per strength, made on first use and kept: Apple's advice, and what lets a
 * prepare() before a tap take effect. Never released. */
static id impactGenerators[3];
static id selectionGenerator;

static id impactGenerator(int strength) {
  if (strength < 0 || strength > 2) strength = 1;
  if (!impactGenerators[strength]) {
    Class c = objc_getClass("UIImpactFeedbackGenerator");
    if (!c) return NULL;
    id g = send((id)c, "alloc");
    impactGenerators[strength] = ((id (*)(id, SEL, long))objc_msgSend)(g, sel_registerName("initWithStyle:"), strength);
  }
  return impactGenerators[strength];
}

#else

/* [NSHapticFeedbackManager defaultPerformer]. */
static id performer(void) {
  Class c = objc_getClass("NSHapticFeedbackManager");
  return c ? send((id)c, "defaultPerformer") : NULL;
}

static void perform(long pattern) {
  id p = performer();
  /* NSHapticFeedbackPerformanceTimeNow = 1. */
  if (p) ((void (*)(id, SEL, long, unsigned long))objc_msgSend)(p, sel_registerName("performFeedbackPattern:performanceTime:"), pattern, 1UL);
}

#endif

/* 1 when the platform's feedback classes are there to call: the generators on iOS, a default
 * performer on macOS. Neither says whether anything will be felt - the simulator plays nothing,
 * and a Mac without a Force Touch trackpad has nothing to play on. */
int thk_feedback_ready(void) {
  void *pool = objc_autoreleasePoolPush();
#if TARGET_OS_IPHONE
  int ok = objc_getClass("UIImpactFeedbackGenerator") && objc_getClass("UISelectionFeedbackGenerator");
#else
  int ok = performer() != NULL;
#endif
  objc_autoreleasePoolPop(pool);
  return ok;
}

void thk_feedback_impact(int strength) {
  void *pool = objc_autoreleasePoolPush();
#if TARGET_OS_IPHONE
  id g = impactGenerator(strength);
  if (g) send(g, "impactOccurred");
#else
  /* NSHapticFeedbackPatternGeneric 0, Alignment 1, LevelChange 2. */
  perform(strength <= 0 ? 1 : strength == 1 ? 0 : 2);
#endif
  objc_autoreleasePoolPop(pool);
}

void thk_feedback_selection(void) {
  void *pool = objc_autoreleasePoolPush();
#if TARGET_OS_IPHONE
  if (!selectionGenerator) {
    Class c = objc_getClass("UISelectionFeedbackGenerator");
    if (c) selectionGenerator = send(send((id)c, "alloc"), "init");
  }
  if (selectionGenerator) send(selectionGenerator, "selectionChanged");
#else
  perform(1);
#endif
  objc_autoreleasePoolPop(pool);
}

#endif
