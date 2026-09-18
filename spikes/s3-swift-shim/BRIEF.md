# S3 — Scala Native ⇄ Swift C-ABI shim — BRIEF

**Machine:** macOS. **Budget:** 1 week. **Depends on:** S1 PASS. **Gate:** YES (with S1, S2).

## Question
Is the "Scala core drives real UIKit widgets through a Swift shim with `@_cdecl`
functions" design (`07` §7.6) ergonomic, leak-free and fast enough (N-05)?

## Deliverables
1. `shim/` — Swift package `ScalaUIShim` with a C header `scalaui_shim.h` declaring:
   `sui_view_new`, `sui_label_new`, `sui_label_set_text`, `sui_button_new`,
   `sui_button_set_title`, `sui_button_on_tap(handle, cb, ctx)`, `sui_view_add_child`,
   `sui_view_set_frame`, `sui_view_remove`, `sui_destroy`, `sui_run_on_main(cb, ctx)`,
   `sui_root_view()` (returns the app's root view handle). Handles are
   `Unmanaged<UIView>.toOpaque()` retained by the shim until `sui_destroy`.
2. `scala-lib/` — from S1's build, plus **sn-bindgen** generating Scala bindings
   from `scalaui_shim.h` (verify it handles the function-pointer typedefs and
   opaque handle types; if not, hand-write and report the gap). Scala side:
   - `@exported scalaui_main(root: Ptr[Byte])` — builds a label + two buttons,
     positions them with hard-coded frames, wires taps to Scala closures that
     update a Scala `var` and set the label text.
   - Callback context: closures cannot be passed to C directly; implement a
     **handle table** (`Long` id → closure) and pass the id as `ctx`. Verify no GC
     issue: closure must stay reachable via the table.
   - Also test the *reverse*: Scala calls `sui_run_on_main` from a background Scala
     thread to update the label.
3. `ios-app/` — SwiftUI or UIKit host that calls `scalaui_main` after launch with
   the root view.
4. Micro-benchmark: 100 000 `sui_label_set_text` calls from Scala on the main
   thread; report ns per call. Then 100 000 simulated taps (call the stored
   callback from Swift) — report ns per round-trip and memory growth (Instruments
   Allocations / Leaks).

## Pass criteria
- Tap → Scala → label update visible; background thread → main-thread update works.
- Round-trip ≤ 5 µs (N-05); no leak after 100k taps (RSS stable within noise).
- sn-bindgen consumed the header, or the gaps are small and listed.

## Report also
- How painful were: Swift `@_cdecl` with `UnsafePointer<CChar>` strings, UTF-8
  handling, error propagation (an `NSError` back to Scala), Swift concurrency warnings.
- Lines of Swift needed for these ~12 functions → extrapolate to the v1 catalogue (§7.10).
- Whether AppKit variants look like `#if canImport(UIKit)` branches or a second package.
