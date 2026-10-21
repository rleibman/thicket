# 01 — Functional / FP-correctness review (whole repo, main sources)

Scope: every `src/main` Scala file under `modules/`, `examples/`, `tools/` (63 files). Tests read only where needed to
confirm intent. Every finding below was confirmed by reading the code at the cited lines. Native/FFI mutation that is
inherent (handle tables, `Ptr` casts, `Zone`, `CFuncPtr` trampolines, perf-motivated `var`/`null` in the signal
runtime that is already documented) is not flagged.

Severity: **high** = a crash, memory-safety problem or broken feature on a real host; **medium** = a leak, a silent
wrong result, or a hazard that needs one plausible app action to fire; **low** = a style or house-rule issue, or a
latent problem.

---

## HIGH

### H1. `GcState.guarded` is not re-entrant, so a nested callback marks the main thread Unmanaged while Scala is still running on it
- **Where:** `modules/renderer-gtk/src/main/scala/thicket/renderer/gtk/GcState.scala:42-46`;
  `modules/renderer-apple/src/main/scala/thicket/renderer/apple/GcState.scala:51-55` (same shape).
- **Problem:** `guarded` always sets Managed on entry and **Unmanaged in `finally`**. A GTK signal emitted
  *synchronously* from a Scala call re-enters a trampoline. When that inner `guarded` exits, it marks the thread
  Unmanaged, and control then goes back to the outer Scala frame, which keeps running Scala code.
- **Verified path:** `GtkRenderer.update` → `Prop.Text` on a TextField → `gtk_editable_set_text` (GtkRenderer.scala:309)
  → GTK emits `changed` synchronously → `Handles.changed` (Handles.scala:134) → `GcState.guarded` → `finally` sets
  Unmanaged. The same happens with `gtk_switch_set_active`, `gtk_check_button_set_active`,
  `gtk_drop_down_set_selected`, `gtk_range_set_value` and `gtk_toggle_button_set_active` (GtkRenderer.scala:345, 353,
  451, 498, 867). The `suppress` set stops the *app* handler from running. It does not stop the trampoline or its
  `guarded`.
- **Failure scenario:** the user clicks a button whose handler sets a `Var` bound to a TextField. The clicked
  trampoline is Managed, then the nested `changed` trampoline sets Unmanaged, and the rest of the click handler
  (further effects and allocations) runs while the GC treats the thread as executing foreign code. If a ZIO worker
  thread triggers a collection in that window, stop-the-world does not wait for the main thread. The main thread's stack
  roots may not be scanned and it mutates the heap during collection. The result is use-after-free or heap corruption,
  which is intermittent and load-dependent: the class of bug S3 and S9 spent spikes finding. Initial mount has the
  same problem: a TextField with initial text flips the main thread to Unmanaged before `releaseMainThread`.
- **Fix:** make the transition depth-counted, so that only the outermost entry and exit change state:
  ```scala
  private var depth = 0 // main-thread only
  inline def guarded[A](inline body: A): A = {
    if depth == 0 then GcExtern.scalanative_GC_set_mutator_thread_state(Managed)
    depth += 1
    try body
    finally { depth -= 1; if depth == 0 then GcExtern.scalanative_GC_set_mutator_thread_state(Unmanaged) }
  }
  ```
  If callbacks can arrive on more than one thread, use a thread-local counter instead. Make the same change in the Apple
  `GcState`.

### H2. No host installs `UiThread`, so `thicket-effect-zio` writes the signal graph from ZIO worker threads, and every result is silently lost
- **Where:** `modules/core/shared/src/main/scala/thicket/core/UiThread.scala:30` (default poster `f => f()`). The
  missing `UiThread.install` affects `GtkApp.run`, `AppleApp.run`, `MainActivity`/`GalleryActivity`. Writes happen
  at `modules/effect-zio/shared/src/main/scala/thicket/zio/syntax.scala:38, 63-64, 83, 102, 117-121`.
- **Problem:** the scaladoc says "`GtkApp` uses `g_idle_add`; an Android Activity uses a `Handler`…". A repo-wide
  grep finds `UiThread.install` only in effect-zio *tests*. `GtkApp.postToUi` and `AppleApp.postToUi` exist but are
  never wired in. With the default poster, `onUi` runs the `Var.set` inline on the fibre's thread.
- **Failure scenario:** every host installs `ThreadGuard.owningThread`. In `fetch.asSignal`, the completion
  `state.set(Done(a))` runs on a ZIO thread, so `ThreadGuard.check` throws `IllegalStateException` inside the
  `foldCause` continuation. ZIO turns that into a defect on a fibre that nobody observes, and the screen shows
  `Loading` forever with nothing logged. Without a guard (JS, or a host that forgets one), the non-thread-safe graph
  is mutated concurrently from worker threads.
- **Fix:** have each host install its poster next to `ThreadGuard.install`, for example
  `UiThread.install(GtkApp.postToUi)` and `UiThread.install(AppleApp.postToUi)`, and on Android
  `UiThread.install(f => handler.post(() => f()))`. Make the default fail loudly, for example
  `f => throw IllegalStateException("UiThread not installed by the host")` or log once, instead of running inline. Add
  a GTK self-test that runs `ZIO.succeed(1).asSignal` to `Done`.

### H3. No exception barrier at FFI entry points, and the signal runtime rethrows a failing effect on every later write
- **Where:** every trampoline in `modules/renderer-gtk/.../Handles.scala` (94, 134, 152, 166, 178, 207, 241, 276) and
  `GtkApp.scala` (`onActivate`, `onPopped`, `onBack`). Also `modules/renderer-apple/.../Handles.scala:148-213` and
  `AppleApp.onReady`. All of them use `GcState.guarded`, which has `finally` but no `catch`. The rethrow is in
  `modules/signals/shared/src/main/scala/thicket/signals/Core.scala:235-265`.
- **Problem (a):** if any app closure (`onClick`, `onTextChange`, `RowSource.bind`, an effect re-run by `Var.set`)
  throws, the exception unwinds through GTK, Swift or ObjC C frames. On Scala Native that is undefined behaviour, and
  in practice an abort with no Scala stack trace. `VirtualRows.bind` throws by design (`Reconciler.scala:328, 330, 345`)
  and is called from `listBind` and `rowTrampoline`.
- **Problem (b):** `Runtime.flush` catches an effect's error, **re-queues the effect** (line 256) and rethrows it. The
  effect stays Dirty, so the *next* `flush`, triggered by any unrelated `Var.set` anywhere, re-runs it, fails again and
  rethrows. One effect that throws on some state turns every later write in the app into a throw at an unrelated call
  site.
- **Failure scenario:** a label's `toProp` divides by a count that becomes 0. From then on every button click or
  keystroke throws out of `Var.set`, through the clicked trampoline, into GTK, and the process aborts. On Android the
  same throw escapes an `OnClickListener` and kills the app.
- **Fix:** add one catch in the trampoline wrapper, for example
  `guarded { try body catch { case NonFatal(e) => ErrorSink.report(e) } }` with an installable sink that logs by
  default. Return `null`/0 from value-returning trampolines (`rowTrampoline` already treats `null` as "no row").
  In `Runtime.flush`, give a failing effect a bounded retry, or mark it failed and stop re-queuing it until one of its
  dependencies changes again. Rethrowing to whichever unrelated writer flushed is the wrong place to report it.

---

## MEDIUM

### M1. An effect whose *first* run throws is permanently dead
- **Where:** `modules/signals/shared/src/main/scala/thicket/signals/Signal.scala:97-102, 207-210`;
  `Core.scala:79-85`.
- **Problem:** `runInitial` leaves `state = Dirty` when `recompute` throws, but the effect is never put in `pending`.
  `markDirty` short-circuits on `state != Dirty`, and `markCheck` only acts when the state is Clean, so no later write
  ever schedules it. This breaks the invariant that the comment at `Core.scala:247-250` exists to protect.
- **Scenario:** a caller catches the mount failure (a host retry, or a test) and then updates the state. The widget
  never updates again and nothing reports it.
- **Fix:** in `Signal.effect`, on an initial-run failure, either `dispose()` the effect before rethrowing (fail
  closed) or `Runtime.schedule(e)` so that it is retried like a failed flush.

### M2. `Owner` created under an already-disposed parent becomes an orphan that is never disposed
- **Where:** `modules/signals/shared/src/main/scala/thicket/signals/Owner.scala:30` (`parent.nn.children += this`).
- **Problem:** `own(d)` disposes `d` immediately when the owner is dead, but the constructor appends to a dead
  parent's buffer without checking. The parent's `dispose` has already run and is idempotent, so the child, and every
  effect created under it, lives for the rest of the process.
- **Scenario:** an async completion (posted through `UiThread`) calls `Owner.scoped { … Signal.effect … }` under a
  screen's owner after the screen was popped. Those effects keep running and keep writing to native handles that may
  already be destroyed.
- **Fix:** `if parent != null then parent.nn.own(this)`. `own` disposes `this` immediately when the parent is dead,
  and `dispose`'s `forget` is a no-op on a dead parent.

### M3. `Computed.now` on a computed born under a dead owner returns `()` cast to `A`
- **Where:** `Signal.scala:90-94` (`owner.own(c)` disposes at once), `159` (`cached: Any = ()`), and `179-182`
  (`updateIfNecessary` is a no-op when disposed, then `cached.asInstanceOf[A]`).
- **Scenario:** `Signal.computed(count() * 2)` is created after its owner was disposed, the same race as M2. For
  `Signal[Int]`, `.now` throws `ClassCastException: BoxedUnit cannot be cast to Integer` at the use site. For a
  reference type it returns a `BoxedUnit` posing as `A`.
- **Fix:** when `!hasValue`, compute once untracked even if disposed, or fail with a clear "computed used after its
  owner was disposed" error. Do not cast the `()` sentinel.

### M4. `NavHost.screen` / `title` / `actions` call `screenFor` afresh on every read, so toolbar actions come from a different `Screen` than the one on screen
- **Where:** `modules/core/shared/src/main/scala/thicket/core/NavHost.scala:80-83`
  (`nav.stack.map(es => screenFor(es.head.route))`). `Signal.map` is a stateless view, so `f` runs on **every**
  read. It is consumed at `GtkApp.scala:203` (`root.actions()`) and by `MainActivity` (`app.actions()`), while the
  content comes from the cached `pages` (`NavHost.scala:55-77`).
- **Problem:** each read builds a new `Screen`. Any state that `screenFor` allocates (`val draft = Var("")` and an
  `Action("Save")(save(draft.now))`) is a *different instance* from the one the mounted content binds to.
- **Scenario:** a screen builder creates a local `Var` for a form and a "Save" toolbar action. The user types, clicks
  Save in the header bar, and the action reads an empty `Var`. Cost is a second problem: `bigScreen`
  (`examples/shared/.../TodoApp.scala:380-382`) builds a 10 000-element `Vector` on every evaluation of `title` and of
  `actions`.
- **Fix:** derive `screen` from the same `built` cache that `pages` uses (`built(es.head.id).screen`). Better still,
  make `pages` a memoised `Signal.computed` under an owner the `NavHost` holds, and derive `screen`, `title` and
  `actions` from it. Document that `screenFor` runs exactly once per entry id.

### M5. `effect-zio` swallows defects and interruptions, so `asSignal` can stay `Loading` forever
- **Where:** `modules/effect-zio/shared/src/main/scala/thicket/zio/syntax.scala:63, 83, 121`
  (`cause.failureOption.fold(())(…)`).
- **Problem:** only typed failures are surfaced. A `Die` (a throw inside `ZIO.succeed`, `.orDie`, the H2 guard
  exception) or an external interruption produces `()`, which is no state change and no log.
- **Scenario:** a decoder bug throws inside `ZIO.succeed(parse(body))`, and the screen shows a spinner forever. This
  defeats the scaladoc claim that the enum makes a forgotten loading or error case impossible.
- **Fix:** match on the cause. Interruption caused by our own dispose is the only case to ignore. Report `dieOption`
  through an installable defect reporter (log by default), or add a `RemoteData.Crashed(Throwable)` / map defects into
  `Failed` when `E >: Throwable`.

### M6. Results and errors are delivered after the owning component is disposed
- **Where:** `syntax.scala:40-46` (`interruptLater` is asynchronous) and `63-64, 83`. The `onUi` callbacks do not
  check the owner.
- **Problem:** disposal only *requests* interruption. A completion that already ran its continuation, or that races
  the interrupt, still posts to the UI. For `launch`, that means `present.show(e)`.
- **Scenario:** the user leaves a screen while its save request is failing, and an error alert for the dead screen
  appears over the next one.
- **Fix:** capture the owner and skip disposed ones: `onUi(() => if !owner.disposed then present.show(e))`. Do the
  same for the `state.set` posts.

### M7. ForEach / Show reconcile: duplicate keys orphan mounted widgets and owners
- **Where:** `modules/core/shared/src/main/scala/thicket/core/Reconciler.scala:538-574`.
- **Problem:** the first mount of `[a, a]` takes fast path 2 and mounts **two** entries for key `a`. On the next
  general reconcile, `byKey = entries…toMap` keeps only the last entry. `wanted` contains `a`, so neither entry is
  disposed, and `rebuilt` contains only the second. The first entry's widget stays in the parent, its `Owner` keeps
  running effects, and `RegionSlot.dispose` can never reach it. The same reused entry also gets `value.set` twice and
  is `moveAfter`'d twice.
- **Scenario:** `ForEach(items, key = _.name)` where two items share a name, followed by a reorder. A ghost row stays
  on screen permanently and its effects leak.
- **Fix:** detect duplicates when building `next` and fail with a clear message, or key the reuse map as
  `Map[Any, List[RegionEntry]]` and consume entries in order.

### M8. Reconcile is not exception-safe, so a throw mid-rebuild leads to a double `destroy` of native handles
- **Where:** `Reconciler.scala:542` (disposes the removed entries) against `574` (`entries = rebuilt` runs only on
  success). Related: `WidgetSlot.dispose` (`267-274`) is not idempotent.
- **Problem:** if `mountEntry` throws while building `rebuilt`, the region's `entries` still lists the entries already
  disposed at 542. The region effect is re-queued (H3b) and runs again, or the region is later disposed. Either way
  `disposeEntry` runs again, `WidgetSlot.dispose` calls `renderer.destroy` on a handle that was already destroyed, and
  on GTK that is `gtk_widget_unparent` / `gtk_widget_get_root` on freed memory.
- **Fix:** commit removals before mounting: `entries = entries.filter(e => wanted(e.key))` straight after line 542.
  Also make `WidgetSlot.dispose` idempotent with a `disposed` flag, so that `Mounted.dispose()` called twice by a
  user is also safe.

### M9. GTK: per-widget CSS providers are registered display-wide and never removed or unreffed
- **Where:** `modules/renderer-gtk/src/main/scala/thicket/renderer/gtk/GtkRenderer.scala:119-147` (`gtk_css_provider_new`
  and `gtk_style_context_add_provider_for_display`), `1088` (`cssProviders.remove(handle)`, which only drops the map
  entry).
- **Problem:** each themed widget adds a provider to the **display's** global cascade. `destroy` never calls
  `gtk_style_context_remove_provider_for_display` or `g_object_unref`, so the provider and its stylesheet live
  forever, and GTK walks every one of them on each style recomputation.
- **Scenario:** a themed `ForEach` (inside `Provide`) churns rows, or the user navigates in and out of a themed
  screen. Providers accumulate without bound, memory grows, and style validation slows down over the session.
- **Fix:** in `destroy`, look the provider up, call `gtk_style_context_remove_provider_for_display(display, provider)`
  (store the display alongside the provider, because the widget is going away), then `g_object_unref(provider)`.

### M10. GTK `destroy` misses several per-handle tables, including the LazyColumn binder
- **Where:** `GtkRenderer.scala:1060-1121` compared with the tables at `42-45` (`alertTitle`, `alertDetail`,
  `alertActions`, `alertDismiss`), `1292` (`virtualModels`) and `1249` (`binderId` from `Handles.registerListBinder`,
  which is never stored or released).
- **Problem:** (a) The binder closure captures the `RowSource`, which is `VirtualRows` with its rows, owners and
  `spec`, so every unmounted `LazyColumn` stays alive in `Handles.listBinders`. Apple releases the equivalent (`rowIds`,
  AppleRenderer.scala:426), so the two renderers disagree. (b) The alert tables are keyed by `Ptr`. After the widget
  is freed, GTK can reuse its address for a new widget. A new Alert at a recycled address that sets no `Message`
  inherits the old alert's detail text (`alertDetail.get(handle)` at 1168), and the same goes for the dismiss closure.
- **Fix:** store `binderId` in a `listBinderIds(scroller)` map and release it in `destroy`. Remove `alertTitle`,
  `alertDetail`, `alertActions`, `alertDismiss` and `virtualModels` in `destroy`. Consider asserting
  `Handles.liveCount` around a LazyColumn mount/unmount in the GTK self-test.

### M11. Android `destroy` keeps `alertTitle` / `alertMessage` entries, and so the views themselves
- **Where:** `modules/renderer-android/src/main/scala/thicket/renderer/android/AndroidRenderer.scala:61-62` (populated
  at 237, 613). `destroy` (`912-950`) removes `alertActions` and `alertDismiss` but neither of these.
- **Scenario:** every `Sheet` sets a title. The map is keyed by `View`, and for a Sheet that View is the content root,
  so each sheet opened and closed retains its whole view subtree (and the Activity context through it) for the
  renderer's lifetime. The comment at 931-934 describes exactly this failure mode.
- **Fix:** add `alertTitle.remove(handle)` and `alertMessage.remove(handle)` to `destroy`.

### M12. GTK `syncPages` cannot remove the root page, so `Nav.reset` / `Nav.restore` leave a dead page in the container
- **Where:** `modules/renderer-gtk/src/main/scala/thicket/renderer/gtk/GtkApp.scala:280-298`.
- **Problem:** each id that leaves the stack is torn down with `adw_navigation_view_pop(navView)` and
  `owner.dispose()`. `adw_navigation_view_pop` does nothing when only one page remains (the file's own comment at
  ~322 notes that Adw "will not pop its root"). After `nav.reset(r)` from a one-deep stack, the old root's owner is
  disposed, so its effects are dead, but its page stays in the AdwNavigationView beneath the new one. Its `Mounted` is
  never destroyed, and a back swipe or Alt+Left can reveal it frozen. `onPopped` then disposes the *new* page's owner
  and calls `back()`, which returns false, and the two stacks diverge.
- **Fix:** when the removed ids are not a strict top suffix, or include the root, rebuild with
  `adw_navigation_view_replace(navView, pages, n)` rather than pop/push. Add a `reset` case to the GTK self-test.
  (This needs a check on the device; the code path itself is confirmed.)

### M13. `UiRuntime.fromLayer` never closes its scope, so layer finalizers never run
- **Where:** `modules/effect-zio/shared/src/main/scala/thicket/zio/UiRuntime.scala:51-54`.
- **Problem:** `Runtime.unsafe.fromLayer` returns a `Runtime.Scoped` whose `shutdown()` releases the layer. It is
  discarded, but the scaladoc says services are "released at exit". DB pools and sockets acquired by the layer are
  never finalized. `layer.orDie` also makes a layer failure an uncaught throw from a public constructor.
- **Fix:** keep the `Runtime.Scoped` and expose `shutdown()` for hosts to call from their quit path
  (`g_application` shutdown, `onDestroy`, `applicationWillTerminate`). Return `Either[Throwable, UiRuntime[R]]` or
  document the throw.

### M14. Android host page owners are detached roots, so they are not disposed in `onDestroy`
- **Where:** `examples/todo-android/scala/src/main/scala/example/android/MainActivity.scala:143` (`Owner()`), with
  `221` disposing only the Activity's `owner`. GalleryActivity does the same.
- **Problem:** on a configuration change (rotation) the Activity is destroyed, but every live page's effects survive,
  still bound to the old Activity's views. `AppleApp` gets this right with `Owner.child(using rootOwner)`.
- **Fix:** `val pageOwner = Owner.child(using owner)`.

---

## LOW

- **L1. Library code throws for control flow, against the decisions.md house rule.**
  - `Reconciler.scala:57-60`: `Mounted.handle` throws for a Fragment root. Prefer `def handle: Option[H]` (or
    `rootHandle`).
  - `Reconciler.scala:240`: `region` throws on a parentless Show/ForEach.
  - `Reconciler.scala:328, 330, 345`: `VirtualRows.bind` throws, which also feeds H3.
  - `Renderer.scala:801, 804, 829`: default `present`/`dismiss`/`createVirtualList` throw.
  - `Renderer.scala:559-560`: `CalendarDate`'s `require` makes the case-class constructor partial. Add
    `CalendarDate.of(y, m, d): Option[CalendarDate]` and a private constructor.
  - `AndroidRenderer.scala:785`: an unknown drawable throws inside `update`.
  - `GtkApp.scala:106` and `AppleApp.scala:31`: `build` defaults to a throwing thunk; an `Option` would do.
- **L2. `SvgPath.parse` is partial and silently wrong on valid SVG** (`SvgPath.scala:52-148`).
  - Truncated data (`"M 10"`) throws `IndexOutOfBoundsException` from `tokens(i)` (line 64), not the intended
    message.
  - A bare `"-"` throws `NumberFormatException` at 145.
  - Exponent notation (`1e-3`) is tokenised as `1`, `-3`, giving wrong geometry with no error.
  - Return `Either[String, Vector[Cmd]]`, bounds-check in `num()`, and either support or reject exponents.
- **L3. `SubscriptionRef.asSignal` blocks the caller** (`syntax.scala:140`): `unsafe.run(ref.get).getOrThrow()` on the
  UI thread, which also throws. It is redundant, because `ref.changes` emits the current value first. Seed with a
  placeholder, or document the blocking `get`.
- **L4. Process-global mutable state written by one thread and read by others, without `@volatile`.**
  `ThreadGuard.current` (`ThreadGuard.scala:41`), `UiThread.poster` (`UiThread.scala:30`, read from ZIO threads) and
  the guard's `owner` (`ThreadGuardPlatform.scala:23`, a first-touch race between two threads). `Theme.current` is
  UI-thread only, which is fine. Mark the first three `@volatile`; `UrlOpener.opener` already is.
- **L5. Side effect inside `Signal.map`** (`NavHost.scala:58-77`): `pages` mutates `built` on every read, including
  `.now` reads from `AppleApp`'s popped handler. It is idempotent today, but it makes a supposedly pure view
  stateful. It goes away with the M4 fix (one memoised computed).
- **L6. Apple `Handles.count` omits `ints`** (`apple/Handles.scala:128`). Picker and segmented handlers
  (`registerInt`, AppleRenderer.scala:326, 335) are invisible to the leak check, which is the failure the GTK
  `liveCount` comment describes.
- **L7. Stringly-typed value callbacks** (`gtk/Handles.scala:48, 124-130`): `readers: () => Any` → `toString` →
  `s.toInt` / `s.toDouble` / `s == "true"` (GtkRenderer.scala:362, 467, 506). Typed tables (`Int => Unit`,
  `Double => Unit`), as Apple has, remove the `Any` and the parse.
- **L8. Public mutable, nullable fields**: `GtkApp.rootHandle` (`GtkApp.scala:119`), `AppleApp.rootHandle` and
  `AppleApp.renderer` (`AppleApp.scala:38, 43`). Make them `private var` with `Option` getters.
- **L9. Stale scaladoc** (`Element.scala:46-48`): it says "`Signal.map` needs a `using Owner`". It does not
  (`Signal.scala:58`), and the reasoning in that paragraph no longer holds.
- **L10. `Owner.dispose` is not exception-safe** (`Owner.scala:49-59`): `isDisposed` is set first, so if a child's
  `dispose` throws, the remaining children are never disposed and a retry is a no-op. Dispose each child in its own
  try, collect the errors, and rethrow once.
- **L11. Style:** chains of early `return`s in `GtkRenderer.insertAfter` / `removeChild` (`965-996`) and
  `AndroidRenderer.insertAfter` (`848-896`) would read better as one `match` on a computed container kind. In
  `tools/shim-gen/.../Parse.scala:98, 159, 205`, `if ret.isDefined && … then … ret.get` should be
  `for r <- ret; as <- args.sequence yield Decl(name, r, as)`.
- **L12. `ThicketNativeFlags.xcrunSdkPath`** (`tools/sbt-thicket/shared/.../ThicketNativeFlags.scala:176-182`)
  catches only `IOException`. `!!` throws `RuntimeException("Nonzero exit value")` when `xcrun` exists but the SDK
  does not, which bypasses the friendly message. Use `.!(logger)` with an exit-code check, as `pkgConfig` does.

---

## Things done well (not issues)
- The signal runtime's use of `null` and `var` is confined, documented and measured (N-05). `relink`'s fast path
  and `untracked` are correct.
- `RemoteData` is a proper ADT with exhaustive views. `ErrorPresenter[Nothing]` as the only `given` is a good
  forcing function.
- The handle tables are lock-free on purpose (S9). GTK's `close-request` trampoline returns an explicit `gboolean`.
  The `Long`⇄`Ptr` casts go through intrinsics.
- `Reconciler` keeps region bodies untracked and captures `declaredTheme`, which is the subtle part, done right.
