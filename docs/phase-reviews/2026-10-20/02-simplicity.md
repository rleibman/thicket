# 02 — Simplicity / duplication / dead-code review (whole repo)

Repo: /home/rleibman/projects/thicket @ 9c98d28 (main, clean). Read-only review; no sbt run.
Every finding below was checked by reading the code at the cited lines. Sizes are rough
diff estimates (lines touched). Severity is how much the issue costs to keep, not how hard it is to fix.

Legend: **H** = fix soon (bug-adjacent, or the shape has already produced bugs), **M** = worth a PR,
**L** = tidy-up.

---

## A. Build / packaging

### A1 [H] build.sbt:9-10, 43-74, 135 — groupId changed to `net.leibman`; everything else still says `dev.thicket`
- Commit #70 (9c98d28) replaced `ThisBuild / organization := "dev.thicket"` with `organization := "net.leibman"`
  (and dropped `ThisBuild /` from every build-wide setting, and removed `GitKeys.baseVersion := "0.1.0"`).
- Still hard-coded `dev.thicket`: `tools/sbt-thicket/src/main/scala/thicket/sbt/ThicketGtkPlugin.scala:62-63`,
  `ThicketApplePlugins.scala:65-66` (the plugin adds these deps to user apps), all three
  `templates/*/project/plugins.sbt`, `README.md:38,177,196-197`, `docs/14-releasing.md:64`.
- Effect: a locally published framework is now `net.leibman:*`, the plugin asks for `dev.thicket:*` →
  `bin/verify-getting-started*.sh` and any user app fail to resolve. Not a simplicity issue strictly, but it is
  the most consequential thing found and it is a one-line-of-truth problem: the groupId should be defined once.
- Suggested change: pick one groupId; put it in `BuildInfo` (add `organization` to `buildInfoKeys` in
  `thicketBuildInfo`) and have `sbt-thicket` read `BuildInfo.organization` instead of the literal, so the plugin
  cannot drift from the build again. Update templates/README/docs in the same change.
- Also stale after #70: comments at build.sbt:36-42 and :111 still talk about "a ThisBuild setting" /
  "`ThisBuild / GitKeys.useGitDescribe`"; build.sbt:60-61 says "`scm` comes from sbt-git via the remote" while
  :23-28 now sets `scmInfo` by hand (with browse URL `https://github.com`, not the repo URL). Note: relies on sbt 2
  applying bare build.sbt settings to all subprojects; if that assumption is wrong, the regression is much larger
  (subprojects lose scalaVersion/licence headers) — worth one `print coreJVM/organization` to confirm.
- Size: ~15 lines + plugin change.

### A2 [M] build.sbt:177-545 — every project repeats the same 4-line preamble
- 16 projects each write `.enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin[, ScalaNativePlugin])`
  `.settings(thicketBuildInfo("..."))` `.settings(commonSettings)`; every example adds `publish / skip := true`;
  four JVM projects add `scalacOptions ++= Seq("-release", "17")`.
- Suggested change: two small helpers, e.g.
  ```scala
  def thicket(p: Project, pkg: String): Project =
    p.enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin)
     .settings(thicketBuildInfo(pkg), commonSettings)
  lazy val exampleSettings = Seq(publish / skip := true)
  lazy val androidJvm      = Seq(scalacOptions ++= Seq("-release", "17"))
  ```
  (crossProjects need a `CrossProject` overload.) Also `sbtThicket` (build.sbt:577-580) re-spells `zioTestJvm`
  instead of using it.
- Size: −60 / +20 lines.

### A3 [L] Orphans at the repo root (untracked, git-ignored — local clutter only)
- `scala-ui.iml` (old repo name, `WEB_MODULE` with no sources), `.bloop/` (Bloop export from an earlier
  sbt; the build is sbt 2 + BSP now), `target/`, `examples/shared/{jvm,native}/` (empty dirs).
  None are tracked (`git check-ignore` confirms), so nothing to change in git; `rm` locally.

---

## B. Renderers — duplication across and within GTK / Android / Apple

### B1 [H] GtkRenderer.scala:31-..., 1060-1110 (30 per-handle maps); AppleRenderer.scala:33-46, 387-410 (11 maps)
- GtkRenderer keeps **30** `mutable.Map/Set` keyed by handle (tapIds, editIds, toggleIds, selectIds, valueIds,
  alertTitle/Detail/Actions/Dismiss/Cancel, sheetWindow/CloseId, menuPopover/menuIds, cssProviders/cssClasses,
  calendars, dateChanged, segments, segmentIndex/Chosen, stackAlignment, gridColumns, ...). `destroy` must
  remember each one by hand. The code's own comments (GtkRenderer.scala:1069-1072, Handles.scala:47-53) record
  two leaks from exactly this (`valueIds` on GTK, `selectIds` on Apple). AppleRenderer has the same shape
  (tapIds/editIds/boolIds/valueIds/selectIds/dateIds/actionIds/dismissIds/menuIds/rowIds).
- Suggested change: one `mutable.Map[Handle, WidgetState]`, where `WidgetState` holds `kind`, `ids: ArrayBuffer[Long]`
  (every registered callback id, regardless of shape) and an `Option` per feature (alert, sheet, menu, css...).
  `destroy` becomes `states.remove(h).foreach(_.release())`, and forgetting a field can no longer leak a handler.
  AndroidTabs already uses this pattern (`Parts`, AndroidTabs.scala:41-54); GtkTabs (8 maps, destroy at :226-243)
  should too.
- Size: GTK ~150 lines touched, Apple ~60, GtkTabs ~40. Behaviour-neutral if done mechanically.

### B2 [H] Apple Handles.scala:128 — leak counter omits `ints`
- `def count = taps.size + texts.size + bools.size + values.size + rows.size` — `ints` (Picker/SegmentedControl
  selection, DatePicker, pages-popped) is missing, so the self-test's leak check is blind to exactly the class of
  leak (`selectIds`) that the comment says was found on Apple. GTK's `liveCount` (Handles.scala:56) includes all
  five tables.
- Fix: add `+ ints.size`. 1 line. Better: B3 removes the possibility.

### B3 [M] Apple Handles.scala:31-114 — six copy-pasted register/replace pairs
- `register`, `registerText`, `registerBool`, `registerValue`, `registerInt`, `registerRow` are identical except
  the map; same for the five `replace*`. GTK Handles.scala repeats it three more times (`register`,
  `registerValued`, `registerListBinder`, `registerChosen`).
- Suggested change:
  ```scala
  private def put[F](m: ConcurrentHashMap[java.lang.Long, F], f: F): Long = { val id = nextId.getAndIncrement(); m.put(id, f); id }
  private val tables = List(taps, texts, bools, values, ints, rows)
  def release(id: Long): Unit = tables.foreach(_.remove(id))
  def count: Int = tables.map(_.size).sum
  ```
  `count`/`release` derived from one list cannot omit a table (fixes B2 by construction).
- Size: −50 lines per file.

### B4 [M] AppleRenderer.scala:228-345 (7×) and GtkRenderer (OnTextChange/OnCheckedChange/OnSelect/...): "get-or-register" block
- Each callback prop repeats:
  ```scala
  xIds.get(handle) match {
    case Some(id) => Handles.replaceX(id, f)
    case None => val id = Handles.registerX(f); xIds(handle) = id; Shim.sui_on_x(handle, Handles.xTrampoline, id)
  }
  ```
- Suggested change: `private def bindCallback(ids: mutable.Map[Handle, Long], h: Handle)(replace: Long => Unit, register: => Long)(connect: Long => Unit)`.
  Combined with B1 the per-kind maps disappear entirely (key the id by `(handle, propName)`).
- Size: −40 lines Apple, −50 GTK.

### B5 [M] GTK `g_signal_connect_data` boilerplate — 18 copies
- GtkRenderer.scala:126, 328, 379, 408, 477, 514, 574, 597, 837, 903, 923, 1143, 1260; GtkApp.scala:172, 209,
  230, 386; GtkTabs.scala:214. Each is the same 9-line `Zone { val _ = g_signal_connect_data(x.asInstanceOf[gpointer],
  toCString(sig).asInstanceOf[Ptr[gchar]], GCallback.fromPtr(cb), Handles.idToPointer(id), null..., GConnectFlags.define(0)) }`.
- Suggested change: `private[gtk] def connect(obj: Ptr[?], signal: String, cb: CVoidPtr, id: Long = 0L): Unit` in
  `Handles` (or a `Gtk` util object). Each call site becomes one line.
- Size: −140 lines.

### B6 [M] GTK small helpers defined 2–3 times
- `gbool` / `gboolTrue`: GtkRenderer.scala:1057, GtkTabs.scala:246, GtkApp.scala:366.
- `isButton`: GtkApp.scala:99-103 (private) duplicates `GtkInspect.isButton` (:213).
- "emit clicked": GtkApp.scala:75-79 and :92-96 duplicate `GtkInspect.click` (:153-165).
- null-safe `fromCString` (`if p == null then "" else fromCString(p)`): GtkInspect.scala ~8 times (menuButtonLabel,
  buttonIconName, tooltip, allTexts×2, labelTexts, stackPages has a local `str`), GtkRenderer.scala:~339.
  Apple Handles.scala:153 and both Swift shims (`str`) have the same helper.
- Suggested change: one `private[gtk] object GtkUtil { def gbool; def str(CString); def connect; def emit }`.
- Size: −40 lines.

### B7 [M] Three hosts re-implement the "live pages" diff
- GtkApp.scala:116-117, 245-291; AppleApp.scala:85-118; examples/todo-android/.../MainActivity.scala:110-160.
  Each keeps a `LinkedHashMap[id, (handle, Mounted, Owner)]` (tuples accessed as `_._2._1`, `entry._3`), removes ids
  no longer in `AppRoot.pages`, mounts new ones under a fresh `Owner`, untracked. Only the toolkit push/pop differs
  (and GTK deliberately does not call `mounted.dispose()`).
- Suggested change: a `PageSet[H]` in `thicket.core` that owns the bookkeeping and calls back
  `onRemoved(id, mounted)` / `onAdded(page, mounted)`; hosts supply the toolkit calls. Replaces the tuples with a
  named case class. Also stops Android's host logic living in an *example* (the next Android app has to copy it).
- Size: +60 core, −90 across hosts.

### B8 [M] Android host chrome duplicated in examples, not in the renderer
- `examples/gallery/android/.../GalleryActivity.scala:57-91` and `examples/todo-android/.../MainActivity.scala:85-104,
  165-209` duplicate: title effect, Up-arrow effect, `currentActions` + `invalidateOptionsMenu`,
  `onCreateOptionsMenu` (identical body), `onOptionsItemSelected`. GTK and Apple put this in `GtkApp`/`AppleApp`;
  Android has no equivalent, so each app re-writes it.
- Suggested change: an `AndroidApp`/`ThicketActivity` base class in `renderer-android` mirroring `GtkApp.run`.
- Related: GalleryActivity's `onOptionsItemSelected` ignores `android.R.id.home` (Up does nothing) — the
  divergence a shared base class would have prevented.
- Size: +80 renderer, −100 examples.

### B9 [M] Tab-selection policy implemented twice
- GtkTabs.scala:44-61, 140-200 (`known`/`wanted`/`chosen`/`reportIfMoved`) and AndroidTabs (`Parts.selected/wanted/onSelect`)
  implement the same rule ("keep the user on their tab; report a moved index; hold `Selected` until the tab exists").
  Apple will need it a third time (#69).
- Suggested change: a pure `TabSelection` state machine in renderer-api (inputs: insert/remove/select/user-choice,
  outputs: what to show / what to report), unit-tested once on the JVM; renderers only apply its output.
- Size: +80 shared, −60 per renderer.

### B10 [L] `isHorizontal(props)` written three times
- GtkRenderer.scala:~243-246, AndroidRenderer.scala:~196-199, AppleRenderer.scala:126-130 — the same
  `props.exists { case Prop.Axis(Orientation.Horizontal) => true; case _ => false }`.
- Suggested change: `Prop.isHorizontal(props)` (or `Orientation.of(props)`) in renderer-api.
  `props.contains(Prop.Axis(Orientation.Horizontal))` works too if `Prop` is a case-class enum. 3 lines each.

### B11 [L] AppleRenderer.scala:186-193 — `Prop.Align` re-inlines `alignCode`
- `alignCode` (:118-123) exists, and `Prop.Align` repeats the same match inline. Replace with
  `Shim.sui_set_align(handle, alignCode(a))`. Also `if v then 1 else 0` five times (:149,:152,:178, menu):
  `private inline def cbool(b: Boolean): CInt`. −10 lines.

### B12 [L] Orphaned comments in `create`
- GtkRenderer.scala:195-203: the SegmentedControl and DatePicker comments sit above `case WidgetKind.Grid`;
  :262-266: the Alert comment sits above `case WidgetKind.Sheet`.
- AndroidRenderer.scala:134-142: SegmentedControl and DatePicker comments sit above `case WidgetKind.Grid`.
- Move each comment to its case. Pure readability, ~20 lines moved.

### B13 [L] GtkRenderer.scala:~232-247 Scroll policy branches differ only in argument order
- `if horizontal then set_policy(sw, AUTOMATIC, NEVER) else set_policy(sw, NEVER, AUTOMATIC)` → compute the pair:
  `val (h, v) = if horizontal then (AUTOMATIC, NEVER) else (NEVER, AUTOMATIC)`. −8 lines.

### B14 [L] GtkInspect.scala:129-137, 266-275 — manual `Zone.open()/try/finally close` where `stackalloc` (as
  `GtkRenderer.measure` does) or `Zone { ... }` suffices. −8 lines.

### B15 [M] GcState.scala duplicated verbatim (GTK and Apple)
- `modules/renderer-gtk/.../GcState.scala` and `modules/renderer-apple/.../GcState.scala` are the same extern +
  `guarded` + `releaseMainThread` (diff shows only package and comments; Apple also exposes enterScala/leaveScala).
- Suggested change: a tiny `renderer-native-common` (Native-only) module, or put it in `core.native` under
  `thicket.native`. The comments disagree slightly about the rule; one copy would fix that too. −50 lines.

### B16 [M] Swift shims: toolkit-neutral helpers copied in both files
- `str`, `epochDay`, `dateOf`, `resolveImagePath`, `scratch`, `fireTap`, the five callback structs
  (Shim+AppKit.swift:22-71, 647-655, 1312; Shim+UIKit.swift:24-73, 773-780, 1267) are identical.
- Suggested change: `Sources/Shim+Common.swift`, compiled with either file by `build-shim.sh` (`SRC` becomes two
  files). Needs a Mac to verify. −80 lines.

### B17 [L] Leaks found in passing (not simplicity, flagging because B1 touches the code)
- GTK `cssProviders`: each themed widget's provider is added *to the display*
  (`gtk_style_context_add_provider_for_display`, GtkRenderer.scala:~140) and `destroy` only drops the map entry
  (:1088) — never `gtk_style_context_remove_provider_for_display` / `g_object_unref`. Every destroyed themed widget
  leaves a provider in the display's cascade.
- effect-zio `asSignal` (syntax.scala:55-64): `cause.failureOption.fold(())(...)` ignores defects/interrupts, so a
  dying effect leaves the signal in `Loading` forever.

---

## C. Core / signals / effect-zio

### C1 [M] Element.scala — `value | Signal[value]` → Static/Reactive match written 12 times
- Lines 144, 183, 223, 312, 405, 432, 436, 460, 479, 483, 525, 547.
- Suggested change:
  ```scala
  private def attr[A](v: A | Signal[A])(p: A => Prop): Attr = v match {
    case s: Signal[?] => Reactive(s.asInstanceOf[Signal[A]], p)
    case a            => Static(p(a.asInstanceOf[A]))
  }
  ```
  Then `Checkbox` is `Seq(attr(checked)(Prop.Checked(_)), ...)`. `Picker` and `SegmentedControl` (:424-490) share
  their whole body and can call one private `choice(kind, options, selected, onSelect)`.
  Safe because no `A` used here is itself a `Signal`.
- Size: −60 lines.

### C2 [M] Reconciler.scala:491-534 — the two fast paths duplicate each other
- "same keys" (:491-507) is "prefix append" (:511-534) with zero rows added; both run the same key-compare loop and
  the same value-update loop, then `return`.
- Suggested change: one `if next.length >= entries.length && keysArePrefix(next)` branch: update the prefix, mount
  `next.drop(entries.length)` (empty in the equal case), return. Keep the while loops (they are perf-motivated)
  inside `keysArePrefix` / `updatePrefix`. −20 lines, no behaviour change.

### C3 [L] Element.scala:507-516 — two consecutive doc comments on `Toggle`; the first is orphaned. Merge.
  Same in examples/todo-android/.../SelfTest.scala:38-39 (`allTexts`).

### C4 [L] Unused public members (no reference anywhere in modules/examples/tools/templates)
- `Signal.scala:30` `Disposable.noop`; `Core.scala:268` `pendingCount` ("test/benchmark hook", no test uses it);
  `GtkInspect.scala:68` `isWidget`.
- `Element.scala:737` `accessibleLabel` and `syntax.scala:112` `asRemoteSignal` are public API with no test and
  no example use — keep, but add a test or a gallery use (CLAUDE.md's gallery rule).
- `ErrorPresenter.printing` is intended API; fine.

### C5 [L] effect-zio syntax.scala
- :41-48 `owner.own(new Disposable { def dispose() = ... })` → `owner.own(() => rt.interruptLater(fiber))`
  (Disposable is a SAM; `Disposable.noop = () => ()` proves it).
- `rt.fork(...); ownFiber(rt, fiber)` four times → `forkOwned(rt)(zio)`.
- :140 `Unsafe.unsafe(implicit u => ...)` uses the `implicit` keyword the code style forbids (decisions.md);
  ZIO 2 on Scala 3 offers `Unsafe.unsafely(...)` (context function) — check the exact name against ZIO 2.1.26.
- `cause.failureOption.fold(())(e => ...)` → `.foreach(e => ...)`.
- Size: −15 lines.

### C6 [L] `null` sentinels in library hosts (decisions.md: "no null")
- GtkApp.scala:43, 60, 112, 115 (`mainWindow`, `headerBar`, `rootHandle`, `navView`), AppleApp.scala:36, 41
  (`rootHandle`, public `var renderer: AppleRenderer = null`). C pointers are unavoidable at FFI, but the
  Scala-object `renderer` and the public `rootHandle` vars could be `Option`/accessors. GtkApp.scala:35 `appId` is
  a field only assigned and read inside `run` — make it a local.

---

## D. Examples

### D1 [M] Self-test harness copied six times
- `failures` + `check(name, cond, detail)` + final "ALL CHECKS PASSED / N CHECK(S) FAILED":
  counter-gtk/Todo.scala:55-61,431-435; gallery/gtk/GalleryGtk.scala:35-46,305-306;
  gallery/apple-shared/GallerySelfTest.scala:34-45,120-121; todo-apple/AppleSelfTest.scala:54-63,205-206;
  todo-apple/LazyProbe.scala:44-53,113-114; todo-android/SelfTest.scala:36,67-71,555-556.
- The output format is a contract (`build-app.sh` and `verify-apple.sh` grep for it), so six copies of it is six
  places to break the CI grep.
- Suggested change: `final class SelfCheck(tag: String, log: String => Unit = println)` with `check`, `summary()`,
  `failures` — in a tiny `examples/selftest` crossProject (JVM+Native) depended on by all example hosts.
  Also `AppleSelfTest.scala:332,381,533` defines `settled` three times locally.
- Size: +30, −70.

### D2 [M] iOS host scaffolding triplicated
- `build-app.sh` in examples/todo-apple/ios, examples/gallery/ios, templates/hello-thicket-ios differ only in
  name, archive path, bundle id, self-test env var, and gallery's asset copy (diff verified). `Host.swift` differs
  only in the exported entry symbol; `Info.plist` only in names.
- Suggested change: one `bin/ios-build-app.sh <AppName> <sbtProject> <bundleId> <selftestVar> [asset...]` used by
  both examples (the template must stay standalone, so it keeps its copy). −120 lines.

### D3 [M] Android gallery has no APK project
- `examples/gallery/android/` contains only `scala/`; no Gradle project, no manifest. README.md table row
  "Android | `sbt galleryAndroid/compile`, then gradle under `android/`" and build.sbt:476-478 ("the APK is
  assembled by gradle under examples/gallery/android") describe something that does not exist. `GalleryActivity`
  is compiled but can never be launched, so the gallery's "the only thing that catches a widget that does nothing
  on Android" claim (CLAUDE.md) does not hold for Android, and there is no Android gallery self-test.
- Suggested change: either add the Gradle shell (copy todo-android's, parameterise `build.sh`) or fix README/build
  comment to say it is pending. Also `GalleryActivity.scala:41` `private var app: AppRoot = null` is only used inside
  `onCreate` — make it a local `val`.

### D4 [L] TodoMac.scala:28-35 and TodoIos.scala:41-45 — identical env-var dispatch
- Move to `example.TodoApple.start()` in `todo-apple/shared`. The comment at TodoMac.scala:29-30 ("the shared TodoApp
  cannot start on Apple until Forgejo #9") and LazyProbe.scala:26-29 are stale — `AppleSelfTest.build()` runs TodoApp
  on Apple today. GalleryMac.scala:28-30 "Not verified on Linux … point of the file existing now" is stale too
  (README records 58/58 on macOS).

### D5 [L] `PostProbe` (todo-apple/shared/PostProbe.scala, 140 lines) is a spike-style diagnostic for #22
- Wired into both Apple hosts (`THICKET_POSTTEST`) but not into `bin/verify-apple.sh` or CI. If #22 is closed,
  move it to `spikes/` (or delete; git keeps it). `LazyProbe` *is* in verify-apple.sh — keep.

### D6 [L] `examples/counter-gtk` hosts the Todo app
- Project `counterGtk`, dir `counter-gtk`, `mainClass := example.Todo`; Todo is 437 of its lines. Siblings are
  `todo-android`, `todo-apple`. Rename to `todo-gtk` (keeping Counter.scala) for symmetry. Mostly a move.

### D7 [L] examples/mockup/MealPlanner.scala.mockup:8 says "Roughly 15% of this exists today"; docs/11:72 says 40%.

---

## E. Scripts (bin/, tools/)

### E1 [L] bin/verify-getting-started.sh:15 `root=$(pwd)` is never used.
### E2 [M] bin/verify-getting-started.sh:28-30 vs verify-getting-started-apple.sh:32-37 — two version readers
- The Linux one uses `tail -2 | head -1`; the Apple one was hardened (`version_of`: strips ANSI escapes, matches the
  version anywhere) after the naive form failed under CI. The Linux script still has the fragile form.
  `examples/todo-android/build.sh:28` has a third variant.
- Suggested change: `bin/lib.sh` with `version_of`, sourced by all three. Also the Apple script runs `sbt` five
  times in a loop (:24-26) where one invocation with `;`-joined tasks (as the Linux one does) saves 4 JVM starts.
- Size: +10, −15.

---

## Summary

Overall: the core (signals, reconciler, DSL) is compact and well-reasoned; complexity concentrates in the
renderers and the hosts, where every toolkit was written by copying its sibling. The dominant smell is
**per-handle state spread over many parallel maps** (GTK 30, Apple 11, GtkTabs 8), which has already caused two
documented handler leaks and makes `destroy` a checklist. The second is **host logic living outside the
framework** (Android chrome and page sync in examples; self-test harness ×6; iOS host scripts ×3).

Top 3:
1. A1 — fix the `dev.thicket` / `net.leibman` groupId split introduced in #70 and make the plugin read the
   groupId from BuildInfo.
2. B1 + B2 + B3 — one `WidgetState` per handle and a table-driven `Handles` so release/count cannot omit
   anything (B2 is an existing blind spot in the Apple leak check).
3. C1 + B5 + B6 — mechanical helpers (`attr`, `connect`, `gbool`/`str`) that remove ~250 lines of copy-paste.

Positive: comments explain *why* consistently; `-Wunused:all -Werror` keeps private dead code out (only a handful
of unused public members found); spikes have not leaked into `modules/` (no imports, only references in comments);
the shim ABI is generated and consistency-checked rather than hand-synced.
