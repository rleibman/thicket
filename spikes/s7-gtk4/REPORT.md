# S7 — GTK4 renderer smoke test — REPORT

**Date:** 2026-09-18 · **Machine:** Linux (Ubuntu), Wayland session, GTK **4.22.4**
**Versions:** Scala **3.9.0** · sbt **2.0.9** · Scala Native **0.5.12**
· `com.indoorvivants.gnome:gtk4_native0.5_3:0.2.6`

## Result: **PASS**

A counter app built *through the draft renderer contract* runs on GTK4 from Scala Native.
Startup is ~95 ms, the background-thread → UI path works, and accessibility comes free.
The valuable output is not the app but **four concrete defects in the renderer contract of
`docs/07` §7.4**, found by making it meet a real toolkit.

## Measurements

| Criterion | Measured | How |
|---|---|---|
| Startup: process start → window presented | **95.3 ms** median (92.0 / 102.7 / 95.3, releaseFast 95.6) | `System.nanoTime` at process entry vs after `gtk_window_present` |
| `gtk_application_new` + signal connect | **0.2 ms** | same timer |
| Binary size, debug | **3 706 184 B** (3.5 MB) | `stat` |
| Binary size, releaseFast | **2 639 192 B** (2.5 MB) | `stat` |
| Link time, debug | **~4.3 s** | sbt `Total (…)` |
| Link time, releaseFast | **5.7 s** | sbt `Total (…)` |
| `measure()` through the contract | `56.0 x 18.0` for "Count: 0" | `gtk_widget_measure`, natural size |
| Accessibility | **button role=3 (BUTTON), label role=22 (LABEL)** | `gtk_accessible_get_accessible_role` |
| Background thread → UI | works | Scala `Thread` → `runOnUiThread` → `g_idle_add` → label updated |
| Button → Scala handler | works | `gtk_widget_activate` → GTK "clicked" → Scala closure via the handle table |

GTK4 assigns correct semantic roles to its own widgets with no work on our part, which is
the direct evidence for the F-02 claim that native widgets give accessibility for free.
Orca was not run: role assignment is the part we control, screen-reader behaviour is GTK's.

## The four contract defects (the real output)

### 1. `setFrame` has nowhere to go

`Renderer.setFrame(h, Rect)` assumes the framework positions children and the toolkit obeys.
A `GtkBox` positions its own children; there is no way to place a child at an arbitrary
rectangle. Yoga-driven layout would require `GtkFixed` everywhere, which throws away GTK's
own sizing, its RTL handling and its baseline alignment.

**This is the same tension on every native toolkit**, and §7.4 already half-acknowledges it
("whether a container is a Yoga box or a *native container*"). S7 says the distinction cannot
be per-`WidgetKind` as written — it must be a property the renderer *declares*, because on
GTK almost everything is a native container.

**Proposed change:** add `def layoutMode(kind: WidgetKind): LayoutMode` where
`LayoutMode = FrameBased | ToolkitManaged`. A `ToolkitManaged` container ignores `setFrame`
and instead receives its children's *style* (spacing, padding, alignment, flex) so it can do
its own layout. The reconciler must then be able to drive both.

### 2. `insertChild(parent, child, index)` is not universally expressible

`GtkBox` offers `append`, `prepend` and `insert_child_after` — no insert-at-index. Implementing
the contract faithfully means tracking sibling order in Scala and translating to
`insert_child_after(previousSibling)`. Feasible, but the contract should say so, because a
naive `append` (which is what S7 does) silently breaks reordering.

**Proposed change:** specify `insertChild` in terms of the *preceding sibling handle*
(`insertAfter(parent, child, after: Option[Handle])`), which every toolkit can express, rather
than an index, which several cannot.

### 3. `measure` returns one size; toolkits have several

`gtk_widget_measure` reports **minimum** and **natural** size, per orientation, given a
for-size. UIKit's `sizeThatFits` and Android's `onMeasure` have their own shapes. Returning a
single `MeasuredSize` loses the minimum/natural distinction that GTK (and CSS
`min-content`/`max-content`) depends on.

**Proposed change:** `measure` returns `(min: MeasuredSize, natural: MeasuredSize)`, and Yoga's
measure callback uses `natural` clamped to `min`.

### 4. A `Size` type name collides with Scala Native's

`scalanative.unsafe.Size` is in scope in every Native renderer and shadows a contract-level
`Size`, producing a baffling `Required: scala.scalanative.runtime.RawSize`. Renamed to
`MeasuredSize`. Trivial, but the `renderer-api` module must be audited for this class of
collision (`Size`, `Rect`, `Point`, `Tag`, `Zone`) before M0.

## Other findings

- **The handle table from `docs/07` §7.6 is now implemented for real** (`Handles.scala`) and
  works. S4 predicted it would be forced; S7 confirms it on a second toolkit.
- **`Long` ⇄ `Ptr` needs intrinsics, and the obvious spelling is a runtime trap.**
  `id.asInstanceOf[Ptr[Byte]]` compiles and then throws
  `ClassCastException: java.lang.Long cannot be cast to Ptr`. Callback ids must go through
  `Intrinsics.castLongToRawPtr` / `castRawPtrToLong`. Same family as S4's struct-return bug:
  **type-checks, fails at runtime.**
- **GTK's generic `GCallback` is `CFuncPtr0[Unit]`**, so every real handler must be laundered
  through `CFuncPtr.toPtr` / `GCallback.fromPtr`. The type checker is defeated at exactly the
  point a UI framework most wants it. A hand-written `connectSignal[F]` helper should hide this.
- **GTK emits `clicked` on a later main-loop turn**, not synchronously inside
  `gtk_widget_activate` (which returns TRUE immediately). Repeated activations within one turn
  are coalesced — two activations produced one `clicked` (count 101, not 102). Harmless here,
  but a renderer test-suite must assert UI state on a *subsequent* tick, never the same one.
- **The bindings are usable as published.** `gtk4_native0.5_3:0.2.6` resolved and linked against
  Scala 3.9.0 / Scala Native 0.5.12 with no regeneration, contradicting the brief's worry. The
  binding surface is raw C (`gtk_box_new(...)`, `Ptr[GtkWidget]`) with fluent wrappers alongside;
  a renderer would use the raw layer, as S7 does.
- **`pkg-config` integration in sbt is three lines** and worked first time.

## What was built

```
spikes/s7-gtk4/
  build.sbt (pkg-config driven), project/, README.md, .scalafmt.conf
  src/main/scala/scalaui/gtk/
    RendererApi.scala   draft contract: WidgetKind, Prop, Renderer, MeasuredSize
    GtkRenderer.scala   GTK4 implementation of it (Box/Label/Button)
    Handles.scala       the handle table + C trampolines
    Main.scala          counter built through the contract; self-driving
```

## Recommendations for the plan

1. **`docs/07` §7.4 — apply defects 1–4.** `layoutMode`, sibling-based `insertChild`,
   min/natural from `measure`, and a name audit. The contract as written cannot be implemented
   faithfully on GTK, and GTK is the *easiest* of the native toolkits.
2. **`docs/07` §7.6 — add `Long ⇄ Ptr` to the handle-table section.** The natural spelling is a
   runtime trap; the framework should expose one blessed helper.
3. **`docs/09` — GTK4 is the cheapest renderer to build**, so it is the right place to iterate
   the contract before committing to the Apple shim. Consider re-ordering M3 (desktop) ahead of
   M2 (Android) for the *contract* work, while keeping M1 (iOS) first for toolchain risk.
4. **Accessibility is confirmed free on GTK** — one datum for F-02. Repeat the check on UIKit
   (S3) and Android (M2) before treating it as settled everywhere.
