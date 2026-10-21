# 08 — Renderer correctness: cross-renderer bug hunt

Scope: every `WidgetKind` / `Prop` in `renderer-api`, traced through `renderer-gtk`, `renderer-android`,
`renderer-apple` (Scala + `Shim+AppKit.swift` + `Shim+UIKit.swift`) and `TestRenderer`, against `main` @ 9c98d28.
Read-only review; nothing was built or run. Each finding below was checked by reading both sides of the code.
I did not re-report the known Apple gaps (#66 IconButton/Glyph/AccessibleLabel, #69 TabView/Tab, #72 unbuilt kinds).
Paths are relative to `/home/rleibman/projects/thicket`.

Kind codes: `Abi.kinds` (tools/shim-gen/.../Abi.scala:191-248), `ShimKind` (Shim.scala:137-164), the header table
(thicket_apple.h:30-59) and both Swift `sui_create` switches agree, codes 0–25. No numeric mismatch found. (Two stale
comments and a stale doc claim are listed under L8.)

---

## HIGH

### H1. GTK `LazyColumn` shows stale rows after any data change that is not an append
- GTK: `modules/renderer-gtk/src/main/scala/thicket/renderer/gtk/GtkRenderer.scala:1288` (`source.onInvalidate(() => syncModel(...))`), `:1297-1313` (`syncModel` only splices at the **end** to match the count)
- Android: `modules/renderer-android/.../AndroidRenderer.scala:1057` (`notifyDataSetChanged()`, which re-binds every visible row)
- AppKit / UIKit: `Shim+AppKit.swift:1184-1189`, `Shim+UIKit.swift:1150-1155` (`reloadData()`, which re-binds every visible row)
- TestRenderer: `modules/core/shared/src/test/scala/thicket/core/TestRenderer.scala:93-96, 124-138` (models "re-bind every visible row on invalidate")

**Problem.** On GTK, invalidating the list only changes the `GtkStringList`'s *length*. If the count does not change, nothing is emitted, so `GtkListView` re-binds nothing. If it shrinks, the splice drops the *last* positions, so the rows before them are never re-bound. `VirtualRows.bind` is the only place a row's `Var` is updated (Reconciler.scala, `row.value.set(item)`), so a row that is not re-bound keeps showing its old item. `TestRenderer` re-binds on invalidate, so `VirtualSpec` passes and does not show the GTK behaviour.

**Failure scenario.** `LazyColumn(items)` over 100 rows, 20 on screen. The app deletes item 0 (`items.set(items.now.tail)`). Count goes 100→99, and the splice removes position 99. On screen, row 0 still shows the deleted item and rows 1–19 are each one item behind. Editing an item's title without changing the count shows no change at all. Android and both Apple renderers are correct here. Every `LazyColumn` in the repo is `Signal.const`, which is why no self-test caught this.

**Fix.** In `syncModel`, emit a change covering the visible range. The simplest form is `gtk_string_list_splice(model, 0, current, <count placeholders>)`, which replaces everything and makes `GtkListView` re-bind, the same as `reloadData` / `notifyDataSetChanged`. A cheaper form calls `g_list_model_items_changed(model, 0, n, n)`. Add a GTK self-test that edits row 0 of a non-const `LazyColumn` and reads back the bound label.

### H2. GTK: a sheet the user closes with the window's close button frees its content while GtkWindow still points at it
- `GtkRenderer.scala:1137-1151`: the close-request handler does `sheetWindow.remove(handle)` and **then** calls the app's `OnDismiss`
- `GtkRenderer.scala:1096-1119`: `destroy` takes the `gtk_window_set_child(win, null)` path only if `sheetWindow` still holds the window. Otherwise it falls through to `gtk_widget_unparent(handle)` (line 1114)
- `modules/signals/.../Signal.scala:134-141`: `Var.set` flushes effects synchronously

**Problem.** The gallery's sheet (Gallery.scala:320-328) and the documented pattern both unmount from `OnDismiss` (`Sheet(...)(model.sheet.set(false))`). That runs `dispose` → `dismiss` → `destroy` *inside* the close-request emission. By that point the map entry is gone, so `destroy` calls `gtk_widget_unparent` on a direct child of a `GtkWindow`. This drops the only reference, which frees the subtree, while the window's own `child` pointer is not cleared. The handler then returns FALSE, GTK destroys the window, and the window's dispose unparents the freed child. This is the "attached through a setter must be detached through that setter" rule the file already documents (lines 68-79, 1097-1102), broken on this one path.

**Failure scenario.** On GTK, open the gallery's sheet and click the window manager's close button. The result is `GTK_IS_WIDGET` criticals or a crash. Only the app-initiated unmount is tested (counter-gtk Todo.scala:384-391), never the user-close path.

**Fix.** Do not remove `sheetWindow` in the close-request handler, so `destroy` keeps the `set_child(null)` path. Alternatively, keep a separate "closed by user" flag and have `destroy` call `gtk_window_set_child(win, null)` whenever the handle's parent is a `GtkWindow`. Also return TRUE (veto) from close-request and let the unmount destroy the window. GTK should not destroy a window whose child the reconciler still owns. Add a self-test that emits `close-request` on the sheet window.

---

## MEDIUM

### M1. GTK leaks every `LazyColumn`'s row closure, and with it the whole `RowSource` graph
- GTK: `GtkRenderer.scala:1249` (`Handles.registerListBinder` and its id), `:1286` (`virtualModels(scroller)`). `destroy` (`:1060-1121`) releases neither.
- Apple equivalent done right: `AppleRenderer.scala:423-426` (`rowIds.remove(handle).foreach(Handles.release)`, commented "an unreleased id keeps every unmounted list alive")

**Problem.** The binder closure captures `source`, which is `VirtualRows` with its rows, owners and signals. `Handles.liveCount` counts `listBinders`, so the leak can be measured.

**Failure scenario.** Navigate to the gallery's "Big list" page and back repeatedly, or toggle a `Show` around a `LazyColumn`. `Handles.liveCount` and memory grow with every unmount.

**Fix.** Keep `binderIds(scroller) = binderId`. In `destroy`, call `Handles.release` on it and remove `virtualModels(scroller)`. Then add the same `liveCount` before/after check the self-test already does for the other ids.

### M2. Android: reordering `Tab`s moves the user off their tab and reports a wrong `OnSelect`
- Android: `AndroidRenderer.scala:847-851, 893-897` has no `moveAfter` override, so a reorder is `removeChild` then `insertAfter`. `AndroidTabs.scala:198-207` (`remove`): if the visible tab is the one being moved, `visible` becomes `None`, and `keepSelection` (`:166-178`) switches to the nearest tab and calls `onSelect(i)`.
- GTK: `GtkRenderer.scala:1021-1025` overrides `moveAfter` for TabView to `tabs.insertAfter`, which keeps the visible page.

**Problem.** The contract (Renderer.scala:246-248) says inserting or removing tabs keeps the user on their tab. A keyed reorder is neither on Android. It tears the visible tab out and reports a selection the user never made.

**Failure scenario.** `TabView(sel)(sel.set)(ForEach(tabs, _.id)(t => Tab(...)))`, with the user on tab B and the app rotating the tab order. On GTK the user stays on B and gets one correct `OnSelect` with B's new index. On Android, removing B shows its neighbour and fires `OnSelect(neighbour)`. Re-inserting B keeps the neighbour visible, so the app's `sel` now points at the wrong tab.

**Fix.** Override `moveAfter` in `AndroidRenderer` so that for `tabs.isTabView(parent)` it calls `tabs.insertAfter(parent, child, after)`, which already handles a tab that is already present (`items.remove(tab)`, `content.removeView`). Use plain remove+insert for everything else.

### M3. `Prop.Grow` means three different things, and `.grow` on a `LazyColumn` is silently dropped
- GTK: `GtkRenderer.scala:678-679`. Only `gtk_widget_set_hexpand`, so Grow does nothing along a Column's (vertical) main axis, and `Spacer()` in a Column takes no space.
- Android: `AndroidRenderer.scala:292-311`. It always sets `lp.width = 0` with weight 1. In a vertical `LinearLayout` that makes the child **0 px wide**, so it is invisible. The ZStack and Grid guards (`:295`, `:298`) test `handle.getParent`, but Grow is a static prop applied in `create` (`:211-212`) **before** `insertAfter`, so `getParent` is always null and the guards never fire. The child then enters the FrameLayout/GridLayout carrying `LinearLayout.LayoutParams(width=0)`, which `generateLayoutParams` copies, so it is 0 wide there too.
- AppKit / UIKit: `Shim+AppKit.swift:815-821`, `Shim+UIKit.swift:722-728`. Lowers hugging on both axes, so it grows along the main axis as the contract says (Renderer.scala:323-324).
- Core: `modules/core/shared/src/main/scala/thicket/core/Element.scala:760-766`. `withAttr` returns non-`Widget` elements unchanged, so `LazyColumn{...}.grow` (Gallery.scala:351, TodoApp.scala:396) is a no-op on every renderer.

**Failure scenarios.**
- `Column()(Label("x").grow, Button(...))` on Android shows no label.
- `ZStack()(Image(...).grow, Label(...))` on Android shows no image.
- `Column()(Header, Spacer(), Footer)` pins the footer to the bottom on Apple and Android, but not on GTK.
- The big-list screens ask the list to take the remaining height. Nothing reaches any renderer: GTK happens to hard-code `vexpand` on the list scroller (`:1282`), Android's `ListView` is WRAP_CONTENT, and Apple's table is a plain stack child.

**Fix.**
- GTK: set `vexpand` instead of `hexpand` when the parent is vertical. Since Grow arrives before the parent is known, record it and apply it in `insertAfter`, or set both and rely on the box's orientation.
- Android: apply Grow in `insertAfter` (or re-apply it there). Use `height = 0` / weight for a vertical parent and `width = 0` / weight for a horizontal one, and skip it for ZStack and Grid.
- Core: make `.grow` on `Element.LazyColumn` carry through. Add an attrs field, or wrap it, then have `virtualList` apply Grow to the handle.

### M4. Changing a `Picker`'s / `SegmentedControl`'s options loses, keeps or spuriously reports the selection, depending on the platform
- GTK Picker: `GtkRenderer.scala:426-439`. The model splice is **not** inside `suppress`. `GtkDropDown`'s single-selection auto-selects when its model changes and emits `notify::selected`, which reaches `OnSelect` (`:463-486`) as if the user chose it.
- GTK Segmented: `:855-857` and Android Segmented: `AndroidRenderer.scala:509-522` both re-apply the stored index (correct).
- Android Picker: `:538-551`. The adapter is refilled in place and `Spinner` keeps the position (correct).
- AppKit Picker / Segmented: `Shim+AppKit.swift:901-920`. `removeAllItems` / `segmentCount = 0` clear the selection, and nothing re-applies it.
- UIKit Picker: `Shim+UIKit.swift:902-919` keeps `pickerSelected` (correct). UIKit Segmented: `removeAllSegments` loses the selection.
- Scala side: `AppleRenderer.scala:304-308` re-adds the options. The reconciler only re-sends `Selected` when its value changes (Reconciler.scala, `if !last.contains(p)`), so a renderer that drops the selection never gets it back.

**Failure scenario.** `Picker(fruitsSignal, picked)(picked.set)` with `picked = 2`, then `fruitsSignal` gets a new list of the same length. GTK fires `OnSelect(0)` and the app's model silently becomes 0. AppKit and the Apple segmented controls show no selection while the model says 2. Android and UIKit's Picker keep 2. The DSL accepts `Signal[Seq[String]]` for both kinds, so this is reachable. No test exercises reactive options.

**Fix.** On GTK, wrap the splice in `suppress += handle … -= handle`, then re-apply the last `Selected` (store it per handle, as `segmentIndex` already does). On Apple, store the last selected index on the Scala side (or in the shim, as UIKit's picker does) and re-apply it after `sui_picker_add_option` finishes. Add a CatalogueSpec/TestRenderer case and a self-test that swap the options.

### M5. `Picker`: `Selected(-1)` and app writes behave differently, and Android reports writes the user never made
- Android: `AndroidRenderer.scala:553-565`. For -1 the previous item stays selected and visible. `:579-595`: `Spinner` delivers `onItemSelected` **asynchronously**, in the layout pass after `setSelection` or the adapter change, by which time `suppress` has already been cleared. This includes the first layout.
- UIKit: `Shim+UIKit.swift:878-899, 921-936`. For -1 no action has `.on`, and `changesSelectionAsPrimaryAction` makes UIKit show the first option as chosen.
- AppKit: `Shim+AppKit.swift:936-940` (`select(nil)`, shown blank). GTK: `GtkRenderer.scala:441-453` (`GTK_INVALID_LIST_POSITION`).

**Failure scenarios.**
- `Picker(opts, -1)(onSelect)` on Android: at the first layout the Spinner reports position 0, so the app's "nothing chosen" becomes 0 with no user action.
- The gallery's **Clear** button (Gallery.scala:159) leaves Android showing "Cherry" and UIKit showing "Apple" while the label reads "(nothing selected)". On Android the user then cannot re-pick the shown item, because a Spinner does not fire for the same position.
- Every app write on Android is echoed back through `OnSelect` one frame later. That is harmless for `Var.set` and wrong for any handler with side effects.

No self-test exercises -1 (GalleryGtk.scala:106-109, GallerySelfTest.scala:136-149).

**Fix.** On Android, ignore `onItemSelected` when `position == lastWrittenIndex` (keep that per Spinner) instead of relying on a synchronous suppress window. For -1, either document it per platform in docs/12 or add a placeholder entry. Add a "Clear" check to both gallery self-tests.

### M6. Apple: `OnTap` / `.link(url)` on anything except a Button or a Column/Row never fires
- AppKit: taps fire only from `Proxy.tapped` (NSControl target/action, set on Button) and `TapView.mouseDown` (Column/Row): `Shim+AppKit.swift:82-84, 136-147`. `sui_on_tap` / `sui_set_open_url` (`:865-882`) just store the callback.
- UIKit: the tap gesture is added only to Column/Row (`Shim+UIKit.swift:445-456`), plus `.touchUpInside` on UIButton (`:464-467`). `UILabel` / `UIImageView` have user interaction off.
- GTK: `GtkRenderer.scala:897-934` adds a click gesture to *any* widget. Android: `AndroidRenderer.scala:810-814` uses `setOnClickListener` on any view.

**Problem.** The contract presents `onTap` and `.link` as working on any element (Element.scala `extension`; Renderer.scala:455-457: "On anything else it is only behaviour, like OnTap"). On Apple they are silently dropped for Label, Image, ZStack, Grid, Spacer, Divider, ProgressBar and the rest.

**Failure scenario.** `Image(avatar).onTap(openProfile)` or `ZStack(...)(card…).onTap(open)` works on GTK and Android and does nothing on macOS and iOS. `Label("Terms").link(url)` opens the page on GTK and Android only.

**Fix.** In `sui_on_tap` / `sui_set_open_url`, attach a click recogniser to any view that is not an NSControl/UIControl or TapView (`NSClickGestureRecognizer` / `UITapGestureRecognizer` plus `isUserInteractionEnabled = true`), routed through `fireTap`. Add a gallery check that taps a tappable Image.

### M7. UIKit drops a `Checkbox`'s label
- `Shim+UIKit.swift:475-479` (Checkbox is a bare `UISwitch`), `:620-647` (`sui_set_text` has no case for `UISwitch`, so `Prop.Text` goes nowhere)
- GTK `GtkRenderer.scala:295-296`, Android (`CheckBox` is a TextView), AppKit `NSButton(checkboxWithTitle:)` + `Shim+AppKit.swift:740-741` all show it.

**Failure scenario.** The gallery's `Checkbox(model.checked, label = "Checkbox")` (Gallery.scala:145) and every form checkbox render on iOS as an unlabelled switch. docs/12:45 says "done" with no note, and decisions.md:761 records only the control class. Nothing records that the caption is lost.

**Fix.** Either build kind 5 on UIKit as a horizontal stack of a `UILabel` and a `UISwitch` (keeping the switch as the event source), or document the gap in docs/12 and make the DSL render the caption as a sibling on UIKit. The first form keeps the contract.

### M8. `Prop.SafeArea` is computed from a detached view on Apple, cannot be opted out of on UIKit, and overwrites `Padding` on Android and UIKit
- AppKit `Shim+AppKit.swift:984-999` and UIKit `Shim+UIKit.swift:979-997` read `v.safeAreaInsets` once, inside `create`. The view is not in a window yet, so the insets are zero.
- UIKit roots and pages are always pinned to `safeAreaLayoutGuide` (`Shim+UIKit.swift:1047-1058`, `place` at `:1659-1665`), whatever the prop says.
- UIKit `sui_set_padding` (`:685-692`) and `sui_set_safe_area` both write `directionalLayoutMargins`, and the last write wins. Android `AndroidRenderer.scala:656-658` (Padding) and `:457-476` (SafeArea listener) both `setPadding`, so the inset listener replaces the app's padding.
- GTK is a deliberate no-op (correct).

**Failure scenarios.**
- `Column(padding = 16)(...).safeArea()` loses its 16 dp padding on Android and iOS, but keeps it on GTK and AppKit.
- A full-bleed screen that does *not* ask for `.safeArea()` is still inset on iOS. The prop's "an edge not chosen runs under the chrome" (Shim+UIKit.swift:984-985) cannot happen.
- A UIKit view that is not the root but asks for `.safeArea(Edge.Top)` gets zero margins.

**Fix.**
- Apple: compute the insets in `safeAreaInsetsDidChange` / `layoutSubviews` (or constrain to `safeAreaLayoutGuide` per edge) rather than at create.
- UIKit: stop pinning roots to the safe-area guide unconditionally.
- Android and UIKit: add the insets to the stored padding instead of replacing it.

### M9. Apple maps `Alignment.Start/End` to `.left/.right`, which is not RTL-aware, and forces `.left` on every label
- `Shim+AppKit.swift:823-831`, `Shim+UIKit.swift:730-738`
- Android `AndroidRenderer.scala:362-371` uses `Gravity.START/END`. GTK `GtkRenderer.scala:681-689` uses `xalign`, which GTK mirrors in RTL. The contract (Renderer.scala:467-468) says Start/End follow the layout direction.

**Failure scenario.** Under Arabic or Hebrew, every `Label` is left-aligned on macOS and iOS, because the DSL always sends `Align(Start)` (Element.scala `Label`), which overrides the toolkit's `.natural` default. They are right-aligned on GTK and Android. `align = Alignment.End` puts text on the wrong side.

**Fix.** Map Start → `.natural`, and End → `.right` or `.left` according to `userInterfaceLayoutDirection` / `effectiveUserInterfaceLayoutDirection`, or use the trailing alignment. Add a Center/End label to the gallery (see L7).

### M10. The Android gallery cannot be built, and its images would be blank
- `examples/gallery/README.md:8` says "gradle under `android/`", and build.sbt:476-477 says the APK is assembled by gradle under `examples/gallery/android`. `git ls-files examples/gallery` contains only `android/scala/.../GalleryActivity.scala`: there is no `build.gradle.kts`, `settings.gradle.kts`, `AndroidManifest.xml` or gradle wrapper (compare `examples/todo-android/`).
- `Gallery.scala:203, 260-261` use `ImageSource.FromFile("docs/assets/thicket-logo.png")`. Android's `FromFile` (`AndroidRenderer.scala:331-332`) is `BitmapFactory.decodeFile(path)` with no fallback, and an app's working directory is `/`. The Apple shims have a bundle fallback (`Shim+UIKit.swift:773-780`). Android has nothing equivalent.
- There is also no Android gallery self-test (GTK and Apple each have one).

**Problem.** docs/12 §12.10 and the README say the gallery is the conformance surface built for all four platforms, and it is the only thing that catches "renders on GTK, does nothing on Android". On Android it is not built and not checked. M2, M3, M5 and M10's own image problem are all Android-side failures that a built gallery would show on screen.

**Fix.** Add the gradle project (copy `examples/todo-android`'s shape), put the logo in `assets/`, and give `FromFile` an `AssetManager` fallback for relative paths (or copy the logo into `filesDir` at start). Add a minimal self-test that mirrors GalleryGtk's checks.

---

## LOW

### L1. `Scroll(padding = n)` is outside the scroller on GTK, inside it on Android, and ignored on Apple
GTK `GtkRenderer.scala:722-726` (margins on the `GtkScrolledWindow`), Android `:656-658` (`setPadding` on the ScrollView, which insets the content), AppKit `Shim+AppKit.swift:783-789` and UIKit `Shim+UIKit.swift:685-692` (stack/grid only, so a scroll view's padding is dropped). **Fix:** pick one meaning (content inset). On Apple, use `contentInsets` / `contentInset`. On GTK, put the margin on the child.

### L2. GTK's indeterminate `ProgressBar` does not animate
`GtkRenderer.scala:525-535` pulses once. The contract says an indeterminate bar animates, and that this distinguishes it from 0.0 (Renderer.scala:349-352). The UIKit gap is in docs/12:176-177. The GTK one is only in a code comment. `ProgressBar(None)` is not in the gallery (L7). **Fix:** add a `g_timeout_add` pulse while the bar is indeterminate and mounted, released in `destroy`, or record the gap in docs/12.

### L3. GTK `DatePicker` likely reports a date when the user only browses months
`GtkRenderer.scala:400-417, 797-807`. `GtkCalendar` emits `day-selected` when its month/year arrows move the date (to keep the same day in the new month). Only app writes are suppressed, so browsing to next month sets the app's model to "the same day next month". Android (dialog OK) and Apple (control action) report only a real choice. I have not verified this against GTK 4 source; check it. **Fix:** also connect `next-month` / `prev-month` / `next-year` / `prev-year` and ignore the `day-selected` that follows, or report only on the popover's day click (`gtk_gesture_click` on the grid).

### L4. Apple horizontal `Scroll`: the height constraint is frozen at insert time and stacks when the child is replaced
`Shim+AppKit.swift:1045` and `Shim+UIKit.swift:1041-1042` add a new `heightAnchor == constant` on the scroll view each time a child is set. The previous one is on the scroll view itself and is never removed. If the child changes (Show/Switch inside a horizontal Scroll), Auto Layout logs unsatisfiable constraints. If the content's height changes (Dynamic Type, a longer label), the scroller does not follow. **Fix:** keep one constraint per scroll view and update or replace it, or tie the scroll view's height to the document view's height through the frame/content guides.

### L5. Re-applying a handler prop stacks on two renderers
Android `OnTextChange` uses `addTextChangedListener` (`AndroidRenderer.scala:247-257`), so each re-apply adds another listener and each keystroke fires N times. GTK `ContextMenu` (`GtkRenderer.scala:550-606`) creates a new popover and a new secondary-click gesture per apply, so the old popover stays parented and two menus pop. Apple and the other GTK handlers replace their callback (`Handles.replace*`). Static in today's DSL, but the contract permits `update`. **Fix:** keep one watcher per EditText and swap the closure; on GTK, unparent the old popover and reuse a single gesture.

### L6. `TabView` with `Selected(-1)` or an out-of-range index shows the first tab on GTK and a blank content area on Android
`GtkTabs.scala:184-195` (AdwViewStack always shows a page) against `AndroidTabs.scala:180-195` (`else tab.setVisibility(View.GONE)`, so every tab ends GONE). **Fix:** on Android, fall back to index 0 when `wanted` is out of range, as GTK does.

### L7. The gallery does not show states its own rules require
README rule 3 (`examples/gallery/README.md:86-89`) requires "each `ContentFit`", but `Gallery.scala:258-262` (section titled "the three content fits") shows only Contain and Cover, never `ContentFit.Fill`. Also absent:
- `ProgressBar(None)` (indeterminate), the state with two known platform gaps (UIKit, L2);
- `Label` with `align = Center/End`, which would have caught M9;
- `Grow` / `Spacer` inside a Column, which would have caught M3;
- a tappable non-button leaf, which would have caught M6.

**Fix:** add a third `Image(..., fit = ContentFit.Fill)`, an indeterminate bar, an aligned label row, and a Column with a Spacer.

### L8. Doc and comment claims the code contradicts
- docs/12:234-246 (§12.4) says "All four renderers handle all 22 props" and "All thirty are honoured everywhere". §12.1 (line 17-18) says 37 props and "35 on every renderer". M6, M7, M8 and L1 show at least `OnTap`/`OpenUrl` (on leaves), `Text` (UIKit Checkbox), `SafeArea`, `Padding` (Scroll) and `Grow` (GTK, Android) are not honoured on every renderer.
- docs/12:230 (§12.3) says "17 kind codes described". `Abi.kinds` has 26 (0–25).
- docs/12:148-161 lists `TabView` and `IconButton` under "Added in phase 2 — done on all four (#4)" while their Apple cells say #69 and #66.
- `tools/shim-gen/src/main/scala/thicket/tools/shim/Abi.scala:172-173` says "Scala refuses to create [a reserved code] rather than let it reach the shim's `default:` branch. Since #18 every code is built." `AppleRenderer.scala:101-104` maps IconButton, TabView and Tab to 23–25 and passes them straight to `sui_create`, which lands in `default:` (an `NSBox` separator / a 1-px `UIView`, Shim+AppKit.swift:605-608, Shim+UIKit.swift:581-588). Children inserted into that "TabView" are pinned edge-to-edge on top of each other by the root-view branch of `sui_insert_after` (Shim+AppKit.swift:1057-1071). That is the #72 territory; the point here is that the ABI doc states the opposite guard, so a reader trusting it will not look for the crash.
- `Shim+AppKit.swift:1092` and `Shim+UIKit.swift:1073` say "Kind 9 rather than kind 6" for the horizontal scroller. It is kind **15**; 9 is Toggle.

**Fix:** correct the text, and either make `AppleRenderer.create` refuse codes whose `Abi.kinds` entry is `None` (as the doc says) or delete that sentence.

### L9. Small leaks on destroy
- GTK `Alert`'s placeholder `GtkBox` (`GtkRenderer.scala:269`) is never parented, so its floating reference is never sunk or released: one box leaks per alert shown.
- Android `destroy` (`AndroidRenderer.scala:907-942`) never clears `alertTitle`, `alertMessage`, `alertShowing` or `sheetShowing`. Those maps are keyed by View, so they keep the placeholder View alive.
- GTK `destroy` never removes `alertTitle`, `alertDetail`, `alertActions`, `alertDismiss` or `alertCancel`.

**Fix:** `g_object_ref_sink` then `g_object_unref` the Alert placeholder in `destroy`, and remove the map entries.

### L10. AppKit container taps fire on mouse-down
`Shim+AppKit.swift:143-146` (`TapView.mouseDown` fires immediately). GTK uses `released` (`GtkRenderer.scala:921-931`), Android and UIKit fire on touch-up. On the Mac, a press that the user drags off still activates the row, and a row tap fires before a drag can start. **Fix:** track `mouseDown`, then fire in `mouseUp` only if the pointer is still inside the bounds.
