# 04 — Performance review (whole repo, HEAD 9c98d28, 2026-10-20)

Scope: modules/signals, modules/core (Reconciler, NavHost, dsl), modules/effect-zio,
modules/renderer-{gtk,android,apple} (+ Swift shim). Read-only; no sbt run. Every finding
below was checked against the code at the cited line. Costs are estimates, not measurements.
Requirements touched: N-02 (10k-row list, no frame > 32 ms), N-04 (RSS), N-06 (GC pauses).

Severity: CRIT = production impact likely; WARN = noticeable under load; INFO = opportunity.

---

## CRIT-1 GTK: one display-wide CSS provider per themed widget, never removed
- **Where:** `modules/renderer-gtk/.../GtkRenderer.scala:105-148` (`applyCss`), `:1088-1089` (`destroy`).
  Feeding it: `modules/core/.../Element.scala:160` (every `Label` gets `Prop.Tint`), `:201-203` (every `Button` gets `Fill`/`Tint`).
- **Problem:** Under any `Provide(theme)` that sets OnSurface/Accent, *every* Label/Button
  creates a `GtkCssProvider` with a unique class and calls
  `gtk_style_context_add_provider_for_display` — a **display-global** provider. `destroy` only
  removes the Scala map entries; there is no `gtk_style_context_remove_provider_for_display`
  and no unref (grep: no `remove_provider` anywhere). `applyCss` also re-adds the provider and
  re-parses the sheet on every Tint/Fill update.
- **Cost:** providers in the display cascade = themed widgets *ever* created (monotonic).
  GTK's style lookup consults every provider in the cascade for each widget it restyles, so
  restyle cost is O(widgets × providers). A themed `ForEach` of 10 000 rows = 10 000 providers;
  any later restyle (hover, focus, theme change) touches all of them. Each `load_from_string`
  also signals a provider change, which invalidates style for the whole display.
- **Failure scenario:** a themed app with a filtered list; each filter change disposes/mounts
  rows; providers accumulate for the life of the process; frame times and RSS climb.
- **Fix:** one shared provider per (role, colour) generating a fixed class (`.sui-tint-<hex>`)
  — the colour set is tiny — and toggle classes per widget; or at minimum remove the provider
  in `destroy` and add it to the display only on first creation:
  ```scala
  cssProviders.remove(handle).foreach { p =>
    gtk_style_context_remove_provider_for_display(gtk_widget_get_display(handle), p.asInstanceOf[Ptr[GtkStyleProvider]])
    g_object_unref(p.asInstanceOf[gpointer])
  }
  ```

## CRIT-2 GTK: `LazyColumn` leaks its list binder (and with it the whole data set) on unmount
- **Where:** `GtkRenderer.scala:1249` (`Handles.registerListBinder`, id kept only in a local),
  `:1286` (`virtualModels(scroller) = model`), `destroy` `:1060-1120` (neither released).
- **Problem:** The binder closure captures `source: VirtualRows` → `spec.items` signal, the
  row `body`, the renderer and the enclosing `Owner`. `destroy(scroller)` never calls
  `Handles.release(binderId)` and never removes `virtualModels(scroller)` (which then holds a
  dangling `GtkStringList*`). `Handles.liveCount` counts `listBinders`, so the self-test's
  "same size after unmount" check would catch it if a LazyColumn were unmounted in it.
- **Cost:** per unmounted LazyColumn: its entire item Seq + closures, forever.
- **Failure scenario:** navigate push/pop to a screen with a 10 000-item LazyColumn N times →
  N copies of the data retained (N-04).
- **Fix:** `private val listBinderIds = mutable.Map.empty[Ptr[GtkWidget], Long]`; set it in
  `createVirtualList`; in `destroy`: `listBinderIds.remove(handle).foreach(Handles.release);
  virtualModels.remove(handle)`.

## CRIT-3 GTK: `Image(FromBytes)` leaks the GBytes and the decoded texture on every update
- **Where:** `GtkRenderer.scala:637-656`.
- **Problem:** `g_bytes_new` (returns +1) and `gdk_texture_new_from_bytes` (returns +1) are
  never unreffed; `gtk_picture_set_paintable` takes its own ref. When the picture changes
  paintable or is destroyed, the texture's refcount drops to 1 (ours) and never reaches 0.
  (grep: no `g_bytes_unref`/`g_object_unref` in GtkRenderer.)
- **Cost:** per image set: encoded bytes + decoded RGBA texture (a 1000×1000 photo ≈ 4 MB).
  Also a byte-at-a-time copy loop (`:643-644`) instead of a memcpy.
- **Failure scenario:** a list of thumbnails fetched via `RemoteData` → `Image(src)`; scrolling
  a LazyColumn re-binds rows (`row.value.set`) → new texture per bind → unbounded RSS.
- **Fix:** after `set_paintable`, `g_object_unref(texture)`; after creating the texture,
  `g_bytes_unref(bytes)`; use `g_bytes_new` on the array's backing memory via `memcpy`.

## CRIT-4 Android: every child insert/remove/destroy re-lays-out *all* siblings — O(n²) mount and teardown
- **Where:** `modules/renderer-android/.../AndroidRenderer.scala:705-726` (`applySpacing`),
  `:687-701` (`applyGridSpacing`), called from `insertAfter :879-884`, `removeChild :899-903`,
  `destroy :911-915`.
- **Problem:** `applySpacing` walks every child and calls `setLayoutParams` on each, on every
  insert and every destroy, with no early exit when `gap == 0` (the default, `getOrElse(l, 0)`).
  This defeats the tail-check at `:866-873`, whose comment claims mounting is O(n) — it is
  O(n²) again. Only two children's margins can change on an insert/remove (the child itself and
  the one that became/stopped being first).
- **Cost:** mounting n children = n(n+1)/2 `setLayoutParams` calls (each also `requestLayout`);
  n = 1 000 → 500 k; n = 10 000 → 50 M. Clearing the list costs the same again. GridLayout same.
- **Failure scenario:** `ForEach` of a few thousand rows in a `Column` (or `LazyColumn` on a
  renderer without virtual rows) — multi-hundred-ms mount; `items.set(Nil)` freezes the UI.
- **Fix:** touch only the inserted child and index 0/1:
  ```scala
  private def spaceChild(l: LinearLayout, i: Int): Unit = { /* set lead margin of child i */ }
  // insert at index: spaceChild(l, index); if index == 0 && l.getChildCount > 1 then spaceChild(l, 1)
  // remove at index 0: spaceChild(l, 0)
  ```
  and skip entirely when `gap == 0` and the child has no stale margin. For grids, only children
  at index ≥ insert point change row/column-first status; recompute from there.

---

## WARN-1 Signals: subscribing to / unsubscribing from a widely shared signal is O(observers)
- **Where:** `modules/signals/.../Core.scala:46` (`addObserver` does `observers.contains`),
  `:48-51` (`removeObserver` does `indexOf` + `ArrayBuffer.remove` shift), slow path of
  `relink :164-177` (always taken on an effect's first run, since `deps` is empty), `unlinkAll :180-188`.
- **Problem:** S5 fixed the *recompute* case with a fast path, but first subscription and
  disposal still scan. Every row in a list that reads one shared signal (e.g. `selectedId()`
  for highlighting — the canonical pattern) pays a scan on mount and on unmount.
- **Cost:** N rows → N²/2 comparisons to mount, N²/2 (+ array shifts) to unmount.
  10 000 rows → ~50 M each way.
- **Failure scenario:** 10 000-row ForEach with per-row `isSelected = selected.map(_ == id)`;
  filtering the list to empty takes tens of ms+ just in `removeObserver`.
- **Fix:** in `relink`'s slow path, call an unchecked `observers += this` only for deps that
  were not in the old `deps` (fresh is already `distinct`, so the `contains` is redundant); and make
  observer removal O(1) by storing each edge's slot index (Solid/Preact-signals style:
  `observers(i)` ↔ `deps(j)` back-indices, swap-remove). A cheaper interim: an
  `IdentityHashMap`-backed set when `observers.length > 32`.

## WARN-2 Owner: removing a child is O(siblings); disposed entries/effects/fibres are never forgotten
- **Where:** `modules/signals/.../Owner.scala:32-36` (`forget` = `indexOf` + `remove`),
  `:38-40` (`own`). Callers: `Reconciler.scala:467` (one child `Owner` per region entry, parented
  to the *enclosing* owner, which also holds every widget effect on the screen),
  `Reconciler.scala:452` (`e.owner.dispose()`), `effect-zio/.../syntax.scala:41-48` (`ownFiber`).
- **Problem (a):** removing k entries from a region whose owner has P children is O(k·P) plus
  `ArrayBuffer.remove` shifts. Clearing a 10 000-row ForEach: P ≥ 10 000 → ~50 M ref moves.
- **Problem (b):** a `Disposable` passed to `own` is only removed when the owner dies. An
  `Effect` disposed early (`Signal.effect(...).dispose()`) and — more importantly — every
  completed ZIO fibre stays in `children`. `Button("Save")(save.launch)` on a long-lived screen
  appends one `Disposable` holding a `Fiber.Runtime` (with its result/FiberRefs) per tap.
- **Cost:** (a) quadratic teardown; (b) unbounded growth of `children` for the owner's lifetime.
- **Fix:** (a) store children in a `java.util.LinkedHashSet`/`IdentityHashMap`-backed ordered
  set, or give each child its index and tombstone it (compact on growth); (b) have `ownFiber`
  register `fiber.unsafe.addObserver(_ => onUi(() => owner.forget(d)))` (make `forget`
  `private[thicket]`), and have `Computation.dispose` unlink from its owner.

## WARN-3 Virtual rows are never discarded by any renderer; GTK rows go stale
- **Where:** `Reconciler.scala:356-360` (`VirtualRows.discard`) — no renderer calls it (grep:
  only definition and the trait). GTK `GtkRenderer.scala:1249-1258` connects only `bind`
  (no `unbind`/`teardown`); Android `AndroidRenderer.scala:1044-1057` has no recycler listener;
  UIKit `Shim+UIKit.swift:1091,1109` `materialised` set only grows.
- **Problem:** `rows` shrinks only in `disposeAll`. Any row widget the toolkit drops (GTK list
  items released when the model shrinks, Android `RecycleBin` pruning scrap, UIKit cell pool
  trimming) leaves a `Row` with a live `Owner`, its effects still subscribed, and on GTK a
  dangling handle those effects will `renderer.update` into.
- **Cost:** bounded by "row widgets the toolkit ever created", which grows across data
  reloads/shrinks; each row keeps its subtree's effects in the signal graph (extra work on every
  write to a shared signal, see WARN-1).
- **Fix:** GTK: connect `teardown` on the factory and call `source.discard(child)`. Android:
  `list.setRecyclerListener(v => …)` only covers recycling; additionally discard rows whose
  View has no parent after a layout pass, or move to RecyclerView (`onViewRecycled` /
  `onFailedToRecycleView`). UIKit: `tableView(_:didEndDisplaying:)` is not a discard, but
  dealloc of the cell is — track via a cell subclass `deinit` → callback.
- **Related correctness defect (not perf, found while verifying):** on GTK, `invalidate()` →
  `syncModel` only changes the model *length* (`GtkRenderer.scala:1297-1313`). If items change
  with the same count, or rows are inserted above the viewport, already-bound rows are never
  re-bound and show stale data. Android (`notifyDataSetChanged`) and Apple (`reloadData`) do
  re-bind. Fix: `gtk_string_list_splice(model, 0, current, <count placeholders>)` or emit
  `items-changed(0, n, n)`.

## WARN-4 `LazyColumn.bind` re-evaluates the items signal and may index a List — per row bind
- **Where:** `Reconciler.scala:329-331` (`Signal.untracked(spec.items())`, `items(index)`),
  `:224` (`spec.items().length`); `Element.scala:119` (`items: Signal[Seq[A]]`).
- **Problem:** `Signal.map` is deliberately non-memoising (`Signal.scala:58`), so the idiomatic
  `LazyColumn(todos.map(_.filter(p)), …)` re-runs the filter on **every row bind**. And `Seq`
  admits `List`, where `items(index)` and `.length` are O(n).
- **Cost:** scrolling binds ~1 row per frame-row; each bind O(n). At n = 10 000 with a filter,
  ~10 k predicate calls per row per frame → N-02 violated on fling.
- **Fix:** snapshot once per change in the list effect and bind from the snapshot:
  ```scala
  Signal.effect { val xs = spec.items().toIndexedSeq; source.setItems(xs); source.invalidate() }
  // bind: val items = snapshot  (IndexedSeq, O(1) apply)
  ```

## WARN-5 Apple: stack and grid inserts are O(n); grid rebuilds every constraint per insert
- **Where:** `Shim+UIKit.swift:1064-1070` and `Shim+AppKit.swift:1075-1081` (`arrangedSubviews`
  read up to 3× — a bridged array copy each — plus `firstIndex(of:)`); grids:
  `Shim+UIKit.swift:276-284` → `relayout()` `:298+` deactivates/recreates all constraints and
  guides; `Shim+AppKit.swift:336-343` → `reflow()`. GTK grid the same shape:
  `GtkRenderer.scala:948-961` `placeGridCells` on every insert/remove/destroy (`:976-981,1010-1012,1114`).
- **Problem:** the Android tail-check fix (docs/11 §11.10) was never ported to Apple; grids are
  O(n) FFI/constraint work per cell on every platform.
- **Cost:** mounting n stack children O(n²) element comparisons + n array copies of size ≤ n;
  an n-cell grid: O(n²) constraints created (UIKit) / O(n²) FFI calls (GTK: ~4 per cell per insert).
  1 000-cell grid → ~2 M GTK FFI calls on mount, same again on teardown.
- **Fix:** Apple stacks: `let subs = stack.arrangedSubviews` once; check `subs.last === target`
  first. Grids: batch — have the reconciler bracket a mount (`renderer.beginBatch/endBatch`) and
  relayout once at the end, or place only cells from the insertion index onward.

## WARN-6 Reconciler: `mountChildren`/`fragment` copy the sibling buffer per child; region `lastHandle` allocates O(entries)
- **Where:** `Reconciler.scala:202-206` and `:183-189` (`slots.toVector` per kid; closure captures
  it); `:439` `RegionSlot.handles = entries.flatMap(_.slot.handles)` used by the default
  `lastHandle` (`:88`); `:264` `WidgetSlot.handles` allocates `Vector(handle)` each call.
- **Problem:** k static children → k vectors of sizes 0..k-1 = O(k²) copying; region slots
  retain their `anchor` closure, so a container of k regions keeps O(k²) references alive.
  Any sibling that follows a large `ForEach` computes its anchor via `lastHandle` →
  `flatMap` over all entries (10 000-element Vector) on every `Show` toggle or reconcile.
- **Cost:** 1 000 children → ~500 k ref copies at mount; `Show` after a 10 k ForEach → 10 k-ref
  allocation per toggle.
- **Fix:** capture `(slotsBuffer, index)` instead of a copy:
  `val i = slots.length; () => (i-1 to 0 by -1).iterator.flatMap(j => slots(j).lastHandle).nextOption()...`;
  override `lastHandle` in `RegionSlot` to scan `entries` backwards and return the first
  `slot.lastHandle`; in `WidgetSlot`, `override def lastHandle = if presented then None else Some(handle)`.

## WARN-7 ZIO stream → signal has no conflation or backpressure onto the UI thread
- **Where:** `effect-zio/.../syntax.scala:96-104` (`runForeach(a => ZIO.succeed(onUi(...)))`),
  `:110-122`; GTK post path `GtkRenderer.scala:1315-1318` (`Handles.register` + `g_idle_add` per post).
- **Problem:** each element posts a separate UI task; `ZIO.succeed` returns immediately so the
  stream is never slowed by the UI. Only the latest value matters for a signal.
- **Cost:** a 10 kHz stream (sensor, log tail, `SubscriptionRef` updated in a loop) enqueues
  10 k idle sources/s, each a map put/remove + `Var.set` + flush; the main loop queue grows
  without bound when the UI is slower than the producer.
- **Fix:** conflate: keep an `AtomicReference[Option[A]]` "latest" and post only when it was
  empty (the UI task swaps it out and sets once); or `stream.throttleShape`/`aggregateAsync`
  with a latest-wins sink.

## WARN-8 NavHost: `screenFor` re-run on every chrome read
- **Where:** `core/.../NavHost.scala:80-83`; consumers `GtkApp.scala:181-203`, `AppleApp.scala:83`.
- **Problem:** `screen` is a non-memoising `map`, and `title` and `actions` each map it, so each
  navigation runs `screenFor(route)` twice more (title effect, actions effect) in addition to the
  built copy in `pages` — constructing the full `Screen.content` element tree each time. If
  `screenFor` creates state (`Var`s, `asSignal` fetches — `.asSignal` needs an `Owner`, but
  `Var`s and element construction do not), that work is repeated and discarded.
- **Fix:** derive chrome from the cached `NavPage`: `title = pages.map(_.last.screen.title)`
  (pages is cached per id via `built`), or make `screen` a `Signal.computed` under an owner.

---

## INFO-1 Signals: 3+ allocations per recompute
`Core.scala:124` allocates a fresh `ArrayBuffer` per `trackDependencies`; `:142`
`collected.distinct` allocates another buffer and a hash set. Deps are typically 1–3, so a
linear-scan dedupe in `Runtime.track` (or a reused per-depth buffer) removes all of them. Hot
during LazyColumn scrolling (each bind → `Var.set` → row effects recompute) — relevant to N-06.

## INFO-2 Signals: effects scheduled twice on Check→Dirty
`Core.scala:79-85`: `markCheck` schedules a Clean effect; a later `markDirty` from `Check`
schedules it again (`state != Dirty`). The second run is a no-op, but `pending` grows by up to
2× in diamond graphs. Guard with an `isScheduled` flag.

## INFO-3 ForEach: every items change allocates n tuples
`Reconciler.scala:404-405` builds `Vector[(Any, Any)]` of n boxed tuples per change even on the
fast path; at 10 k rows that is 10 k tuples + Vector per keystroke. Pass `items` and `key`
through and compare in place.

## INFO-4 Reactive attribute effects retain the last `Prop`
`Reconciler.scala:150-156`: `last` keeps e.g. `Prop.Picture(Some(FromBytes(bytes)))` alive for the
widget's life (the image bytes are already retained by the element). Compare a hash or drop
`last` for byte-carrying props.

---

## Looks fine
- Region reconcile general path does minimal moves for insert/remove (only the entry whose
  `after` changed is moved); both fast paths are O(n) with no allocation beyond INFO-3.
- Pull-based validation (`updateIfNecessary`) is glitch-free: an effect re-validates deps
  depth-first before running; nested writes during flush are queued, not re-entered.
- GTK `Handles` table is lock-free and releases on destroy for all widget callbacks except
  the list binder (CRIT-2); Apple releases `rowIds`.
- Android Spinner/RadioGroup and segment updates are proportional to option count.

## Summary
CRIT 4, WARN 8, INFO 4. Highest impact:
1. CRIT-1 GTK per-widget display-global CSS providers, never removed (themed apps degrade over time).
2. CRIT-4 Android `applySpacing` makes every list mount/clear O(n²) (directly threatens N-02).
3. CRIT-2/3 GTK leaks: LazyColumn binder retains the whole data set; image textures never freed.
Architectural: lifetimes are explicit (`Owner`) but O(n) removal in both `Owner.forget` and
`Source.removeObserver`, plus `discard` never being called, means teardown is the weak path
everywhere — add a mount/unmount stress test (10 k rows, shared signal, themed) that asserts on
`Handles.liveCount`, owner child counts and wall time.
