# S4 — Yoga layout from Scala — REPORT

**Date:** 2026-09-18 · **Machine:** Linux (Ubuntu), clang 21.1.8, gcc 15.2, cmake 4.2.3 **Versions:** Scala **3.9.0** ·
sbt **2.0.9** · Scala Native **0.5.12** · sn-bindgen **0.4.5**
· Yoga **3.2.1** (pinned, commit `042f501`) · munit 1.3.6

## Result: **PASS-WITH-RISK**

Yoga works from Scala Native via sn-bindgen and is **5.7× faster than the budget**. But the
spike found a hard Scala Native limitation — **a Scala callback cannot return a small struct
by value** — which breaks Yoga's measure function, the single most important callback for the
whole renderer design. A 25-line C trampoline fixes it completely and is demonstrated working.
Separately, the JVM/Android route is worse than the brief assumed.

## Measurements

| Criterion                                   | Target      | Measured                                                        | How                                                                                                                     |
|---------------------------------------------|-------------|-----------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| 1 000-node tree layout                      | < 2 ms      | **0.344 ms** (avg of 100 passes, 1001 nodes, dirtied each pass) | `native/testOnly *`, printed as `[S4]`                                                                                  |
| Layout correctness                          | CSS flexbox | **holds**                                                       | Row with `flexGrow:1` + fixed 100pt child on a 300pt parent → 200/100 split; 10 equal flex columns of 390pt → 39pt each |
| Measure callback, native signature          | works       | **BROKEN** — returns `222.0 x 0.0` for `{111, 222}`             | see below                                                                                                               |
| Measure callback, via C trampoline          | works       | **correct: `111.0 x 222.0`**                                    | see below                                                                                                               |
| Tests                                       | all pass    | **4/4**                                                         | `sbt "native/testOnly *"`                                                                                               |
| `libyogacore.a`                             | —           | **492 KB** (Release, PIC)                                       | `cmake --build --target yogacore`                                                                                       |
| Generated bindings                          | —           | **1 795 lines** from one header                                 | sn-bindgen                                                                                                              |
| Scala Native link (releaseFast, incl. Yoga) | —           | ~12 s                                                           | sbt log                                                                                                                 |

## The headline finding: struct-by-value returns from Scala callbacks are broken

`YGMeasureFunc` is `YGSize (*)(YGNodeConstRef, float, YGMeasureMode, float, YGMeasureMode)` —
it returns a two-float struct **by value**. sn-bindgen types this correctly as
`CFuncPtr5[..., YGSize]` and it *compiles*. At runtime, Yoga invokes the Scala function (verified: the call counter
increments), but reads the wrong values:

```
[S4] direct (struct by value): called=1 -> 222.0 x 0.0, wanted 111.0 x 222.0
[S4] via C trampoline:         called=1 -> 111.0 x 222.0, wanted 111.0 x 222.0
```

The returned fields are shifted by one: Yoga reads our `height` as `width` and gets 0 for
`height`. On x86-64 SysV a `struct {float; float}` is returned packed in `XMM0`; Scala Native
0.5.12 evidently does not implement that convention for a `CFuncPtr` it generates. **It fails
silently** — no crash, no warning, just wrong layout — which is the dangerous kind of bug.

Note the asymmetry: calling *into* C works fine, because sn-bindgen emits C glue (`__sn_wrap_…`) for extern functions
that return structs by value. There is no such glue for
callbacks going C → Scala, and that is exactly the direction a UI framework needs.

### The fix (implemented, `native/src/main/resources/scala-native/measure_shim.c`)

A 25-line C trampoline converts the struct return into an out-parameter:

```c
typedef void (*sui_measure_fn)(YGNodeConstRef, float, YGMeasureMode, float, YGMeasureMode, YGSize *out);
static YGSize sui_measure_trampoline(...) { YGSize out = {0,0}; sui_scala_measure(..., &out); return out; }
void sui_set_measure_func(YGNodeRef node, sui_measure_fn fn) { ...; YGNodeSetMeasureFunc(node, sui_measure_trampoline); }
```

Scala then implements `CFuncPtr6[..., Ptr[YGSize], Unit]`, which has no struct return and works
correctly. A regression test (`KNOWN BAD: struct-by-value return …`) asserts the *broken*
behaviour, so it fails loudly if upstream ever fixes it and the shim can be deleted.

## Second finding: Scala Native forbids closures over local state in `CFuncPtr`

```
Closing over local state of value called in function transformed to CFuncPtr
results in undefined behaviour.
```

This is a compile error, not a runtime surprise — good. But it means **every** C callback must
be a static function plus an explicit context, exactly the handle-table design that
`docs/07` §7.6 proposes for the UIKit shim. S4 independently confirms that design is forced,
not optional. It also means the shim's "one global callback" shortcut used here is not viable
for real use: the framework must key on `YGNodeSetContext`/the node pointer.

## Third finding: the Android route is worse than assumed

The brief's option (a) was "the Java binding from the React Native ecosystem". Reality:

- `com.facebook.yoga:yoga:3.2.1` has **`<packaging>pom</packaging>`** and publishes only
  `-debug.aar` / `-release.aar`. **There is no JVM jar**, so a desktop-JVM sbt project cannot
  consume it at all. (The S4 `jvm/` sub-project was removed for this reason.)
- It depends on **Facebook SoLoader**, which pulls Android-specific native-library loading.
- The AAR ships JNI for **`arm64-v8a`, `armeabi-v7a`, `x86`** — **no `x86_64`**. The default
  Android emulator image is x86_64, so Yoga's native library is missing there unless the image
  supports 32-bit x86 libraries. A physical arm64 device is fine.
- AAR size: 527 KB compressed, 1.3 MB uncompressed (relevant to N-03's 4 MB APK budget).

Option (b), our own JNI build, was **not implemented** — deliberately. It is only needed for a *desktop* JVM, and the
plan has no desktop JVM target (desktop is Scala Native). For Android,
the remaining choices are: ship the AAR and require arm64 (fine for devices, awkward for
emulators), or build Yoga for all four Android ABIs with the NDK and write a small JNI shim. **Recommendation: decide
this inside S2/M2 with a real emulator and device**, since it is an
Android packaging question, not a layout question.

## Fourth finding: sn-bindgen toolchain frictions (all worked around)

1. **libclang version pinning.** The prebuilt `bindgen` binary is dynamically linked against
   `libclang-17.so.17`; Ubuntu ships 20 and 21, so it fails with exit 127 and a bare
   "cannot open shared object file". A user-local symlink to `libclang-20.so.20` works fine (libclang's C API is
   stable), needs no root, and is documented in `README.md`. For CI this
   must be pinned properly — it is a real bus-factor/reproducibility risk (S-02).
2. **C glue is generated into both Compile and Test scopes**, so the test binary links two
   copies and fails with ~20 `multiple definition of __sn_wrap_…` errors. Setting
   `Test / bindgenBindings := Seq.empty` plus a clean build fixes it.
3. **`const` pointers become distinct opaque types.** `YGNodeRef` and `YGNodeConstRef` are both
   `Ptr[YGNode]` but are separate opaque types with no conversion, so every read-only call needs
   an `asInstanceOf` widening. A hand-written wrapper should hide this once.
4. Two harmless warnings on every run: `Unknown type: __float128` / `_Complex __float128`
   (from system headers, not Yoga).
5. Yoga's own gtest suite does not compile under gcc 15 (warnings-as-errors); build with
   `-DBUILD_TESTING=OFF` and only the `yogacore` target.

## What was built

```
spikes/s4-yoga/
  build.sbt, project/, README.md, .scalafmt.conf
  third-party/README.md            how to re-create the pinned Yoga checkout (gitignored)
  native/src/main/scala/scalaui/yoga/
    Yoga.scala                     idiomatic YogaNode wrapper
    MeasureShim.scala              @extern bindings to the C trampoline
  native/src/main/resources/scala-native/measure_shim.c
  native/generated-reference/scalaui.yoga.generated.scala   1795 lines, for review
  native/src/test/scala/scalaui/yoga/YogaSuite.scala        4 tests
```

Generated bindings are currently *managed* sources (regenerated each build). The brief asked
for them to be vendored; a copy is committed under `native/generated-reference/` for review.
Proper vendoring uses `bindgenMode := BindgenMode.Manual(dir)` and should be set up at M0,
together with pinning the bindgen binary and its libclang.

## Recommendations for the plan

1. **`docs/07` §7.5 — record that the measure callback needs a C shim.** The section assumes
   `YGNodeSetMeasureFunc` with a `CFuncPtr` works directly. It does not. Every renderer that
   reports intrinsic sizes (all of them) goes through the trampoline.
2. **`docs/07` §7.6 — the handle-table design is mandatory, not a preference.** Scala Native
   rejects closures over local state in `CFuncPtr` at compile time. Also: the shim's global
   callback slot must become per-node context via `YGNodeSetContext`.
3. **`docs/04` §4.8 risk register — add "Scala Native struct-by-value ABI".** This will recur
   wherever a C or Objective-C API returns a small struct: `CGSize`, `CGRect`, `CGPoint` are *everywhere* in
   UIKit/AppKit. **S3 must test `CGSize`/`CGRect` returns explicitly**, and the
   Swift shim should adopt out-parameters as a blanket rule rather than discovering this per-API.
   This is the most transferable finding of the spike.
4. **`docs/09` — replace open question 6** ("Yoga on JVM: RN artefact vs our own JNI") with the
   answer above: the RN artefact is an Android-only AAR with no x86_64 ABI; decide packaging in S2/M2.
5. **`docs/09` — add open question 7 answer:** sn-bindgen consumed Yoga's header including
   function-pointer typedefs, opaque handles, enums and by-value structs, producing usable code
   from a single `Binding`. It is fit for purpose; its risks are toolchain pinning, not capability.
6. **Performance is a non-issue.** 0.344 ms for 1 001 nodes means layout is ~2% of a 16.6 ms
   frame budget at a tree size far larger than a real screen. The Flexbox-subset fallback in the
   brief is not needed.

## Time spent vs budget

Budget was 1 week; actual work was a few hours, most of it on the struct-ABI investigation and
the sn-bindgen toolchain frictions rather than on Yoga itself.
