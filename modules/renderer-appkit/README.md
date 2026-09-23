# scala-ui-renderer-appkit

The Apple renderer, macOS first (issue #3). `examples/todo-macos` runs the same
`TodoApp` as the GTK and Android hosts, from the same shared element tree, and passes the
same self-test.

```
$ sbt todoMacos/nativeLink
$ SCALAUI_SELFTEST=1 ./target/out/native0.5/scala-3.9.0/todo-macos/todo-macos
[selftest] driving navigation and reading back out of AppKit
... 23 checks ...
[selftest]   root measured 480 x 460
[selftest] ALL CHECKS PASSED
```

`modules/renderer-appkit/shim/build-shim.sh` builds the Swift static library first; sbt
links it. It is not aggregated into the root build, because it links AppKit and can only
build on a Mac — the same arrangement `rendererGtk` has for Linux.

## The headline result: `renderer-api` needed no changes

Issue #3 said that if the contract had to change, that would be the finding. It did not.
`Renderer`, `LayoutMode`, `Measurement`, `WidgetKind` and `Prop` were written against GTK,
checked against `android.view`, and absorbed a third structurally different toolkit without
a line moving. Specifically:

- **`insertAfter` by preceding sibling** maps straight onto `NSStackView`'s
  `insertArrangedSubview(_:at:)` — the index is recovered by looking the sibling up. A
  by-index contract would have been the awkward one here, which is the opposite of the
  intuition and the same way round as GTK.
- **`destroy` detaches as well as releases**, which is what AppKit wants too: the shim calls
  `removeFromSuperview()` before dropping its retain.
- **`layoutMode`** is `ToolkitManaged` for everything, as on GTK. `NSStackView` and
  `NSScrollView` lay out their own children, and forcing frames would mean discarding
  AppKit's sizing the way it would mean `GtkFixed` everywhere.
- **`update` must not disturb a widget showing the value it is written.** The shim compares
  `stringValue` before assigning, which is what stops the caret jumping to the end when the
  app writes back what the user just typed.
- **`moveAfter`** is *not* overridden: `NSStackView` has no reorder primitive, so the
  contract's remove+insert default is the right answer. GTK overrides it because
  `gtk_box_reorder_child_after` exists. The default earned its place.

## Size, against S3's extrapolation

| | non-comment lines |
|---|---|
| Swift shim (29 `@_cdecl` functions) | 336 |
| Scala renderer, host, handle table, inspect | 413 |
| *(GTK renderer, for comparison)* | *467* |

**11.6 non-comment lines of Swift per exported function**, against S3's estimate of 5.8 from
its 21-function spike. The spike's functions were mostly one-liners over UIKit; a real
renderer's carry type switches and suppression logic. Scaling S3's ~240-function v1
catalogue at the measured rate gives ~2 800 lines rather than ~1 400 — which strengthens its
recommendation to generate the shim, the C header and the bindings from one widget
description rather than hand-writing the second one.

## Constraints from the spikes, all honoured

1. **No structs by value across a Scala callback.** `sui_set_frame` takes four doubles and
   `sui_measure` writes four out-parameters; nothing returns an `NSRect` or `NSSize`. S4 and
   S3 found this broken on x86-64 and arm64 with *different* wrong answers and no crash.
2. **Callback context is `int64_t`, never `void*`**, which removes the `Long`⇄`Ptr`
   laundering S7 had to do for GObject's `gpointer`.
3. **Scala runs on the main thread or Scala-created threads only.** `sui_run_on_main` targets
   the main queue specifically; a GCD worker segfaults in the GC allocator.
4. **Managed/Unmanaged around the run loop.** `GcState.releaseMainThread()` once AppKit owns
   the thread, and `GcState.guarded` on every host→Scala entry.
5. **The handle table is lock-free** — `ConcurrentHashMap` + `AtomicLong`. S9 found that the
   monitor-guarded version S3 introduced throws `IllegalMonitorStateException` on Scala
   Native under load and leaks main-thread stack until the process dies.

## Two things this renderer found

**`fittingSize` is the wrong question for a mounted container.** It answers "how big must
this be to satisfy its constraints", which is *zero* for a stack that is already sized by
its parent. The first version of `measure` returned `Measurement(0,0,0,0)` for the whole
mounted tree while every leaf measured correctly — the tree was on screen and laid out, and
the renderer reported it as having no size. `measure` now falls back to the view's actual
frame. Caught only because the self-test asks for geometry; reading the tree back proves the
renderer made the right calls, not that AppKit laid anything out.

**A view added to a plain `NSView` needs explicit constraints.** A programmatically created
view keeps `translatesAutoresizingMaskIntoConstraints = true`, so Auto Layout never sizes
it. The mounted root is pinned to the window's root view with four anchors. Without it the
whole tree measures zero, which is how this was found at the same time as the above.

## Next

- **iOS** is a second `Shim+UIKit.swift` against the same header, per S3's recommendation of
  per-*file* separation rather than `#if` inside function bodies. The Scala side should not
  need to change at all; if it does, that is worth recording.
- `Divider` is implemented but unexercised — `TodoApp` does not use it.
- `setFrame` is a no-op while every container is `ToolkitManaged`. It becomes real when a
  Yoga-driven `FrameBased` container lands.
