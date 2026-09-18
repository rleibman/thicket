# S4 — Yoga layout from Scala — BRIEF

**Machine:** Linux. **Budget:** 1 week. **Depends on:** nothing. **Gate:** no
(fallback exists: write a Flexbox subset in Scala).

## Question
Can Yoga's C API be used from Scala Native via sn-bindgen, and from the JVM (for
Android), with acceptable performance, so one Flexbox implementation serves all
native renderers (`07` §7.5)?

## Deliverables
1. `native/` — sbt Scala Native project:
   - Build Yoga from source (git submodule or vendored tarball; pin the version;
     CMake, C++20). Produce `libyogacore.a`. Note: Yoga's public C API is in
     `yoga/Yoga.h` (`YGNodeNew`, `YGNodeStyleSet*`, `YGNodeCalculateLayout`,
     `YGNodeLayoutGet*`, `YGNodeSetMeasureFunc`).
   - Run **sn-bindgen** (sbt plugin) over `Yoga.h`; commit the generated Scala
     under `src/main/scala/scalaui/yoga/generated/` (vendoring decision S-02).
   - A thin idiomatic wrapper: `YogaNode` class with `style.flexDirection = Row`, etc.
   - A **measure callback** from C into Scala (`YGNodeSetMeasureFunc` with a
     `CFuncPtr`) returning an intrinsic size — this is how native leaves report size.
   - Test: build a 1 000-node tree (10 columns × 100 rows, mixed flex/fixed), lay out
     at 390×844, check a few known positions; time `YGNodeCalculateLayout` ×100.
2. `jvm/` — sbt JVM project evaluating two routes; implement the one that works
   first, write one paragraph on the other:
   - (a) the Java binding from the React Native ecosystem (`com.facebook.yoga:yoga`
     / `yoga-layout` artefacts on Maven Central — check what exists in 2026 and what
     native libs/SoLoader it drags in);
   - (b) our own JNI: compile Yoga + a small JNI shim to a `.so` (x86_64 Linux for the
     test; note the Android ABIs would need the NDK) and call from Scala.
   Same 1 000-node test and timing.
3. `REPORT.md` with timings (target: 1 000 nodes < 2 ms on both), binding gaps found
   in sn-bindgen (C++ leakage? `YGValue` struct returns by value? enums?), and a
   recommendation for the Android route.

## Do not
- Write a renderer. Do not integrate with GTK/UIKit.
