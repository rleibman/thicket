# 03 — Architecture review (whole repo, main @ 9c98d28)

Scope: module layering (signals / renderer-api / core / effect-zio / renderers), cross-renderer
consistency (kind codes, node protocol, prop handling), build wiring, and drift from
docs/decisions.md, docs/07, docs/12. Read-only. No sbt was run; every finding below was checked by
reading the code at the cited line.

## Summary

The layering is clean. `signals` imports only `scala.collection.mutable`, `renderer-api` imports
nothing, `core` depends on signals and renderer-api only, and `effect-zio` depends on core and signals
only. There are no cycles. The `WidgetKind`/`Prop` enums with exhaustive matches are working: each
renderer has to name every kind. The problems are in what the types cannot check:

1. The UI-thread seam is never installed by any real host.
2. Per-handle state in each renderer is kept in 13 to 30 parallel maps, and each one has to be
   cleaned up in `destroy` by hand. Three of them already leak.
3. The host layer (navigation page stack, chrome) is copied three times, and Android has none in its
   module.
4. Several build and documentation facts disagree with the code.

## Positive patterns observed

- **Dependency direction.** It runs toward abstractions. The reconciler is generic over
  `Renderer { type Handle = H }`, and `effect-zio` reaches the UI thread through `core.UiThread`, not
  through a renderer.
- **`WidgetKind.presented`.** This is a good framework-level fact. The reconciler handles it once
  (Reconciler.scala:165, 272) instead of each renderer handling it 3 times.
- **ABI kind codes are generated** from one table (`tools/shim-gen/.../Abi.scala:191`) and checked by
  `ConsistencySpec`. `alignCode` is spelled out instead of using `ordinal`
  (AppleRenderer.scala:114), which is correct for ABI numbers.
- **The `GtkTabs` and `AndroidTabs` extraction** is the right pattern: per-kind behaviour lives in its
  own collaborator. It should be applied more widely (see F4).
- **`ThicketNativeFlags` has one copy**, compiled into both the meta-build and the plugin
  (build.sbt:213). This is good DRY at the build level.

---

## Findings

### HIGH-1 — `UiThread.install` is never called by any host, so effect-zio writes signals from ZIO worker threads
**Location**
- modules/core/shared/src/main/scala/thicket/core/UiThread.scala:30 (default poster is `f => f()`)
- modules/effect-zio/shared/src/main/scala/thicket/zio/syntax.scala:38 (`onUi` posts through `UiThread.run`)

The hosts only install `ThreadGuard`:
- modules/renderer-gtk/src/main/scala/thicket/renderer/gtk/GtkApp.scala:376
- modules/renderer-apple/src/main/scala/thicket/renderer/apple/AppleApp.scala:131
- examples/todo-android/.../MainActivity.scala:50
- examples/gallery/android/.../GalleryActivity.scala:48

**Category:** Structural / Missing.

**Problem.** `grep UiThread.install` matches only test code (`TestUiThread.scala:39`,
`EffectZioSpec.scala:146`). On every real host the poster keeps its default, which runs the callback
inline on the calling thread. `toSignal` and `launch` results are therefore written to a `Var` from a
ZIO worker thread. Either `ThreadGuard` throws (if it is installed and checks writes) or the graph is
written cross-thread. The `UiThread.scala:27` docstring says "GtkApp uses g_idle_add; an Android
Activity uses a Handler…", and none of them does. No example uses effect-zio, so nothing has exercised
this yet. The decision log entry at decisions.md:809 already notes that `f => f()` is the wrong stub,
yet it is the production default.

There are now **two parallel seams for the same concern**: `Renderer.runOnUiThread`
(Renderer.scala:836, implemented by all 3 renderers and called by nothing outside tests) and
`core.UiThread`. `poster` is also a plain `var` that is written on the main thread and read from fibre
threads, with no `@volatile` (UiThread.scala:30). On the JVM (Android) that is a visibility hazard.

**Why it matters.** The first app to use `effect-zio` on a device fails or races. This is the exact
A-06 failure the design was built to prevent.

**Suggested fix.**
1. Make each host install the poster from its renderer, next to `ThreadGuard`:
   `UiThread.install(f => renderer.runOnUiThread(f))` in `GtkApp.run`, in `AppleApp`, and in an
   Android host (see HIGH-3).
2. Make the default fail loudly, for example
   `f => throw IllegalStateException("UiThread not installed by the host")`, or have it check
   `ThreadGuard`.
3. Mark `poster` `@volatile`.
4. Longer term, fold both into one `Host.install(guard, post)` call so they cannot be installed
   separately. Add an effect-zio use to the gallery or the todo example so a device exercises it.

### HIGH-2 — Per-handle state is N parallel maps, each released by hand in `destroy`, and three already leak
**Location**
- GtkRenderer.scala:33-88, 150, 741, 763-765, 815-817, 886-887, 938, 1292: **30** `mutable.Map`/`Set`
  fields declared across the file.
- GtkRenderer.scala:1060 (`destroy`).
- AndroidRenderer.scala:45-66, 680-805, 907 (`destroy`).
- AppleRenderer.scala:35-55, 382 (`destroy`).

**Category:** Code smell (shotgun surgery, temporal coupling).

**Problem.** Every new prop that needs state adds a map, and `destroy` has to remember to drop it. The
code already records two past misses (GtkRenderer.scala:1069-1072: `valueIds`, `selectIds`). Three
more are still present:
- **GTK** `destroy` never removes `alertTitle`, `alertDetail`, `alertActions` or `alertDismiss`
  (declared at :42-45, written at :299 and :735-737, never removed). It also never removes
  `virtualModels` (:1292, written at :1286).
  - The leaked closures capture app state.
  - **The keys are raw `Ptr[GtkWidget]` addresses.** If the allocator reuses an address for a new
    Alert or Sheet that does not set the same props, the new widget inherits the old actions or
    `OnDismiss` handler. That is a correctness bug, not just a leak.
- **Android** `destroy` (:907-942) removes `alertActions` and `alertDismiss` but not `alertTitle` or
  `alertMessage` (:61-62).
  - The map is keyed by `View`, so the View and its context stay alive. The comment at :931-934 says
    that this is exactly the trap.
  - `case g: RadioGroup` (:925) matches before `LinearLayout`, so a RadioGroup's `spacing` entry is
    never removed.
- **Apple.** `Handles.count` (Apple Handles.scala:128) omits `ints`. That is the table behind
  `OnSelect` and `OnDateChange` (Picker, SegmentedControl, DatePicker, TabView). The leak check the
  comments rely on cannot see the class of leak it was introduced to catch. GTK `liveCount`
  (gtk Handles.scala:72) likewise omits `chosen`, which self-releases and is less of a concern.

**Why it matters.** Each new widget makes this worse, and the leak oracle has blind spots. With
pointer reuse on GTK, this becomes a correctness issue.

**Suggested fix.** Use one record per handle and one removal:
```scala
final class NodeState {          // per renderer
  var kind: WidgetKind = ...
  val handlerIds = mutable.ArrayBuffer.empty[Long]
  var alert: Option[AlertSpec] = None
  var cleanups: List[() => Unit] = Nil   // registered at the point state is created
}
private val nodes = mutable.Map.empty[Handle, NodeState]
def destroy(h: Handle) = nodes.remove(h).foreach { s => s.handlerIds.foreach(Handles.release); s.cleanups.foreach(_()) }
```
Register the cleanup where the state is created (`state(h).onDestroy(...)`), so adding a map cannot
forget its removal. Also make `Handles.count` sum every table, or derive it from a single
`Map[Long, Callback]` with a sealed `Callback` ADT instead of six typed tables.

### HIGH-3 — The host layer is copied three times, and Android has none in its module
**Location**
- GtkApp.scala:250-305 (`syncPages`)
- AppleApp.scala:64-104 (`pageMounts`)
- examples/todo-android/scala/.../MainActivity.scala:115-160 (`syncPages`)
- examples/gallery/android/.../GalleryActivity.scala:41-97 (`ThreadGuard`, mount, title, up-button
  and options-menu effects, written again)

**Category:** Structural (DRY, missing abstraction, inconsistent module responsibility).

**Problem.** GTK and Apple ship their host (`GtkApp`, `AppleApp`) inside the renderer module. Android
has no `AndroidApp` or base Activity, so the navigation stack, chrome and guard installation live in
*example* code. Each app has to copy about 120 lines. `GalleryActivity` already diverges: it mounts
`app.element`, not `app.pages`. The page-diff algorithm is the same in all three hosts, including the
invariant "mount untracked, or the page's effects become children of the sync effect". Each copy
documents that invariant as "learned the hard way" (MainActivity.scala:131-134).

**Why it matters.**
- An outside Android developer, who is the 0.1 claim in docs/13, has no host to depend on.
- A fix to the page-sync invariant has to land three times.
- HIGH-1's `UiThread.install` has no Android place to live.

**Suggested fix.**
1. Move the diff into core, for example
   `final class PageStack[H](mount: NavPage => Mounted[H], attach: (Long, H) => Unit, detach: Long => Unit)`
   with `sync(pages: Seq[NavPage])`. It would do the untracked mount and owner disposal once, and could
   be tested in core with `TestRenderer`.
2. Add `thicket.renderer.android.ThicketActivity` (or `AndroidApp.attach(activity, ui)`) to
   `renderer-android`. It would install the guard and the UI thread, run `PageStack`, and apply
   `AppRoot` chrome.
3. Hosts would then only supply the platform container.

### MED-4 — Prop meaning depends on kind, through runtime guards that differ per renderer
**Location**
- GtkRenderer.scala:419-424 (`Selected`/`OnSelect`/`Options` guarded by `tabs.isTabView` /
  `isSegmented`, then a generic fallthrough), :299 (`Text` routed to `alertTitle` by kind), :699
  (`Glyph` on Tab).
- AndroidRenderer.scala:218, 295-298 (`Grow` guarded on parent type), 412, 506-527 (guarded by
  `handle.isInstanceOf[RadioGroup]`).

**Category:** Design pattern (missing Strategy per kind).

**Problem.** `Prop.Selected` means "tab index", "segment index" or "picker index" depending on kind.
Each renderer finds out differently: GTK by `kinds` lookup, Android sometimes by
`isInstanceOf[RadioGroup]`, which is an implementation class. Apple pushes it into the shim. With
`update` written as one big match per renderer (GTK `update` is about 450 lines, :286-739), adding a
kind that reuses `Selected` means editing guard order in three files. A guard placed wrongly falls
through to the generic branch without any error.

**Why it matters.** This is the cause of the size of `GtkRenderer` (1,319 lines) and
`AndroidRenderer` (1,100 lines), and of the shotgun surgery that comes with each new widget.

**Suggested fix.** Complete the pattern `GtkTabs`/`AndroidTabs` started: a per-kind handler,
```scala
trait KindHandler[H] { def create(props: Seq[Prop]): H; def update(h: H): PartialFunction[Prop, Unit]; def destroy(h: H): Unit }
```
with `update` doing `handlerFor(kind).update(h).applyOrElse(p, common(h))`. Extract `GtkAlert`,
`GtkSegmented`, `GtkDatePicker` and `GtkGrid` (and the Android equivalents) the same way. Key the
dispatch on `WidgetKind`, never on the toolkit class.

### MED-5 — Reserved Apple kinds 23-25 are created silently, contrary to the ABI doc
**Location**
- AppleRenderer.scala:101-104: `IconButton`, `TabView` and `Tab` map to `ShimKind` 23/24/25, with the
  comment "the shims build them as their default view until then".
- tools/shim-gen/src/main/scala/thicket/tools/shim/Abi.scala:172-173: "`None` is a code that is
  reserved… Scala refuses to create it rather than let it reach the shim's `default:` branch. Since #18
  every code is built."
- Abi.scala:245-247: all three are `None`.

**Category:** Structural (doc and code contradiction, silent failure).

**Problem.** Nothing refuses. On macOS and iOS an `IconButton` or `TabView` becomes the shim's default
view (the Divider's `UIView`/`NSBox` per Abi.scala:200). This is the "silently does nothing" outcome
that `Renderer.present`'s throwing default (Renderer.scala:796-801) was designed to prevent. The gallery
rule in CLAUDE.md is meant to catch this, but only by eye. "Since #18 every code is built" is stale.

**Suggested fix.** Generate a `ShimKind.isBuilt(code)` (or a `Set` of reserved codes) from `Abi.kinds`.
Have `AppleRenderer.create` fail with a clear message for a reserved code, or render a visible
"unsupported on this platform" placeholder. Update the Abi doc comment.

### MED-6 — Renderer modules are not in the root aggregate, so `sbt scalafmtAll` from root skips them, and they are unformatted
**Location**
- build.sbt:583-601 (`root.aggregate`: only signals, renderer-api, core, effect-zio, shimGen,
  sbtThicket).
- README.md:347: "The Apple modules are not in the root aggregate, on purpose, so Linux and Android
  builds are unaffected". This implies GTK and Android *are* aggregated. They are not.

**Category:** Build wiring.

**Problem.** `rendererGtk`, `rendererAndroid` and every example are outside the aggregate. The
CLAUDE.md rule "Run `sbt scalafmtAll` before every commit" therefore never formats them. The evidence:
- `.scalafmt.conf` sets `verticalMultiline.arityThreshold = 2`.
- GtkRenderer.scala:167 (`def create(kind: WidgetKind, props: Seq[Prop]): Handle`), :286, :965, :1005
  and AndroidRenderer.scala:92, :216, :847 are single-line multi-parameter definitions that scalafmt
  would rewrite.
- AppleRenderer.scala (also not aggregated, but formatted on the Mac) shows the vertical form.

Seven GTK and Android files are affected: GtkRenderer, GtkApp, GtkInspect, GtkTabs, Handles,
UrlOpener, AndroidRenderer and AndroidTabs. `testOnly *`, `headerCheck` and `-Werror` compiles from
root also skip them.

**Why it matters.**
- The first contributor whose editor formats on save produces a diff full of reflows, which is the
  thing the CLAUDE.md rule exists to prevent.
- CI from root does not compile the GTK renderer.

**Suggested fix.**
- Add a `linux` aggregate (`rendererGtk`, `rendererAndroid`, `galleryShared`, `galleryGtk`,
  `galleryAndroid`, `examplesShared`, `counterGtk`, `todoAndroid`), or add the renderers to `root`
  behind an env flag.
- Document `sbt scalafmtAll rendererGtk/scalafmtAll rendererAndroid/scalafmtAll …` (or a command
  alias `fmtAll`).
- Fix README.md:347.

### MED-7 — The layout half of the renderer contract has no caller
**Location**
- Renderer.scala:33-87 (`LayoutMode`, `Measurement`, `Frame`, `Constraints`), :735 (`layoutMode`),
  :806-818 (`measure`, `setFrame`).

**Category:** Code smell (speculative generality).

**Problem.** `grep layoutMode|measure(|setFrame|FrameBased` over `core` matches nothing outside
`TestRenderer`. All renderers return `ToolkitManaged` for every kind and no-op `setFrame`, yet all
three implement `measure` non-trivially (AppleRenderer.scala:486, AndroidRenderer.scala:944, GTK
:1220), with no caller.

**Why it matters.** Each new renderer (Win32, DOM) has to implement and keep in step three methods
that are never exercised. They will drift untested until Yoga arrives, if it does.

**Suggested fix.** Either move them into a separate `trait FrameLayout { this: Renderer => … }` mixin
that the reconciler checks for when Yoga lands, or add a decision-log line that they are intentionally
dormant and a test that calls `measure` on each renderer's self-test.

### MED-8 — Modifiers on non-widget elements are silently dropped
**Location**
- modules/core/shared/src/main/scala/thicket/core/Element.scala:760-766 (`withAttr`: `case other =>
  other`).

**Category:** Missing validation.

**Problem.** `Provide(theme)(Column(...)).onTap(...)`, `Show(x)(...).padding(8)` and
`Fragment(...).safeArea()` all compile and do nothing. `Provide` has exactly one child, so it could
forward. The reconciler is also inconsistent about invalid roots:
- A root `Show` or `ForEach` throws (Reconciler.scala:240).
- A root `Fragment` silently mounts nothing (Reconciler.scala:180-182).

**Suggested fix.**
- In `withAttr`, recurse into `Provide(theme, child)`.
- For `Show`/`ForEach`/`Fragment`, either wrap the element in a `Column` (documented) or make the
  modifiers available only on a `Widget` subtype. A refined return type such as
  `def Button(...): Element.Widget` lets the compiler reject the rest.
- Make the root-`Fragment` case fail the same way the root-region case does.

### MED-9 — The effect bridge contract (`effect-api`) does not exist, and the drift is unrecorded
**Location**
- docs/decisions.md:51 lists `thicket-effect-api`.
- docs/07 §7.1 (line 19) and §7.13 (`trait EffectBridge[F[_]]`).
- build.sbt:288 has only `effectZio`, which depends directly on `core`.

**Category:** Doc and code drift.

**Problem.** The design says the renderer contract and the effect contract are "the two seams of the
framework". Only one exists. effect-zio is an implementation with no interface. The `UiRuntime` scope
and `launch` semantics differ from the sketched `launch(fa)(using ComponentScope)` and `resource`, and
JS is dropped (§7.1 says `%%% all`; build.sbt:288 is JVM and Native only). There is no decision-log
entry recording any of this. docs/07 §7.13 allows the contract to stay provisional until a second
bridge exists, but the module name in decisions.md should not claim an artefact that is not there.

**Suggested fix.** Add a dated decision-log entry: "effect-api deferred until the cats-effect bridge;
effect-zio is JVM and Native only because …". Optionally add a 20-line `EffectBridge` trait in `core`
that `effect-zio` implements, so the shape is fixed before a second implementation copies the first.

### LOW-10 — `.scalafmt.conf` and decisions.md disagree
**Location**
- .scalafmt.conf:1 (`version = "3.9.0"`) against decisions.md:18 ("scalafmt 3.11.5").
- `trailingCommas` is set twice in the same file (`always`, then `never` further down). The second
  wins silently.
- decisions.md:49 repo layout names `shims/` and `tooling/`. The code uses
  `modules/renderer-apple/shim/` and `tools/`.

**Suggested fix.** Bump to the decided version (or log the pin), delete the dead `trailingCommas`
line, and add a decision-log line for the actual layout.

### LOW-11 — Material Symbols path data and an SVG parser sit in the "pure types" seam, with coverage turned off
**Location**
- modules/renderer-api/shared/src/main/scala/thicket/renderer/{MaterialSymbols,SvgPath}.scala.
- build.sbt:186-192: `coverageEnabled := false`, with the stated reason "pure types with no tests of
  its own… three default method bodies".

**Problem.** The comment is stale. `renderer-api` now has a 151-line parser, Android-only icon data,
and calendar arithmetic (`CalendarDate.toEpochDay` and `fromEpochDay`, Renderer.scala:565-590). All of
it is excluded from the coverage ratchet, although `IconSpec` and `DatePickerSpec` in core do exercise
it. Every GTK and Apple binary also links Android's icon geometry. That is small, but it is the
contract module carrying one platform's assets. The placement is a recorded decision
(decisions.md:102); the coverage exclusion is the part that contradicts it.

**Suggested fix.** Fix the cause in the comment (create the scoverage-data directory with
`Test / compile / ... := IO.createDirectory`) and turn coverage back on, or move `SvgPath` and
`MaterialSymbols` into a small `renderer-icons` cross-module that `renderer-android` depends on.

### LOW-12 — Implicit prop-ordering contract (`Range` before `Value`)
**Location**
- AppleRenderer.scala:257-259 ("`Range` always arrives before `Value` (CatalogueSpec)").
- AndroidRenderer.scala:433-451 (Value falls back to `(0.0, 1.0)` if Range has not arrived).
- Element.scala:409-412.

**Problem.** Correctness depends on the DSL's `Seq` order and on the reconciler passing statics before
reactives. Neither the `Renderer.update` scaladoc nor the types state this. A future reactive `Range`,
or a `create` that reorders props, would clamp a value against default bounds.

**Suggested fix.**
- Short term: document "props in a `create`/`update` batch are applied in order" on
  `Renderer.update`.
- Better: `Prop.Value` handlers store the raw value, and `Range` re-applies it, so the order does not
  matter.

### LOW-13 — Stringly and duplicated details in AppleRenderer
- AppleRenderer.scala:58: `platform = "appkit"` is also returned on UIKit. The same class serves iOS.
  It is only used in exception messages today, but it is wrong; use an enum or pass it in.
- AppleRenderer.scala:178-185: `Prop.Align` re-inlines the 0/1/2 mapping that `alignCode` (:114)
  exists for. TextRole, Emphasis and ContentFit codes are also inlined at :150-210 while `alignCode` is
  factored out. Generate these from `Abi` the way `ShimKind` is generated, so the C side and the Scala
  side cannot disagree.
- `null.asInstanceOf[Handle]` (:376, :470): FFI-unavoidable, but worth a `ShimNull` constant so the
  "no null" rule in decisions.md:52 has one documented exception instead of scattered ones.

### LOW-14 — Package `thicket.zio` shadows the root `zio` package inside `thicket.*`
**Location**
- modules/effect-zio/shared/src/main/scala/thicket/zio/*.scala (`import _root_.zio.*` everywhere).
- build.sbt:288 (`thicketBuildInfo("thicket.zio.buildinfo")`).

**Problem.** Any file in package `thicket.*` that imports `zio.*` gets `thicket.zio` instead. That is
a trap for framework contributors, and a later `thicket.cats` would hit the same issue.

**Suggested fix.** Rename the package to `thicket.effect.zio` (or `thicket.zioBridge`) before 0.1
freezes it.

---

## Not issues (checked)

- **No dependency cycles.** Renderer modules depend on `core` only because `GtkApp` and `AppleApp`
  (hosts) live there. The `*Renderer` classes import only `thicket.renderer.*`. Splitting
  `host-<platform>` from `renderer-<platform>` would make the renderer depend only on `renderer-api`,
  as docs/07 §7.1 draws it. This is optional; record it if you keep the merge.
- **Exhaustiveness.** Every renderer's `create` and `update` matches all 26 `WidgetKind`s and all 37
  `Prop`s, and the compiler enforces it. The gaps (Apple `Glyph`/`AccessibleLabel` no-ops at
  AppleRenderer.scala:276-277) are tracked as #66.
- **ABI kind numbering 0-25** is dense, unique, and generated. The 15-for-horizontal-Scroll quirk is
  documented (decisions.md:953).
