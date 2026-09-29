# thicket-renderer-apple

The Apple renderer: **one Scala renderer, two Swift shims** — macOS and iOS (issue #3).
`examples/todo-apple` runs the same `TodoApp` as the GTK and Android hosts, from the same
shared element tree, and passes the same 23-check self-test on both.

```
$ modules/renderer-apple/shim/build-shim.sh macos
$ sbt todoMacos/nativeLink
$ THICKET_SELFTEST=1 ./target/out/native0.5/scala-3.9.0/todo-macos/todo-macos
[selftest] driving navigation and reading back out of the platform
... 23 checks ...
[selftest]   root measured 480 x 460
[selftest] ALL CHECKS PASSED
```

```
$ modules/renderer-apple/shim/build-shim.sh ios-sim
$ sbt todoIos/nativeLink
$ examples/todo-apple/ios/ios-app/build-app.sh 'iPhone 17'
[selftest] driving navigation and reading back out of the platform
... 23 checks ...
[selftest]   root measured 365 x 724
[selftest] ALL CHECKS PASSED
```

`shim/build-shim.sh <macos|ios-sim>` builds the Swift static library first; sbt links it on
macOS, and `ios-app/build-app.sh` links it on iOS. Neither project is aggregated into the
root build, because they link Apple frameworks and can only build on a Mac — the same
arrangement `rendererGtk` has for Linux.

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

Non-comment, non-blank lines, counted the same way for every row:

| | lines |
|---|---|
| `Shim+AppKit.swift` (35 `@_cdecl` functions) | 385 |
| `Shim+UIKit.swift` (the same 35) | 388 |
| Scala: renderer, host, handle table, inspect, bindings | 476 |
| *(GTK renderer, for comparison)* | *530* |

**11.0 non-comment lines of Swift per exported function**, against S3's estimate of 5.8 from
its 21-function spike. The spike's functions were mostly one-liners over UIKit; a real
renderer's carry type switches and suppression logic. Scaling S3's ~240-function v1
catalogue at the measured rate gives ~2 600 lines rather than ~1 400 — **and then doubles
it**, because there are two shims. That strengthens its recommendation to generate the shim,
the C header and the bindings from one widget description rather than hand-writing the
second one, which this module has now done by hand once to find out what it costs.

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

## iOS: what the second shim actually cost

S3 recommended per-*file* separation rather than `#if` inside function bodies, and predicted
that the Scala side would not move. Both held.

**Nothing in Scala changed.** `AppleRenderer`, `AppleApp`, `AppleInspect`, `Handles`,
`GcState` and `Shim` are byte-for-byte the macOS versions; `thicket_apple.h` did not gain a
declaration. The example is shared literally rather than by copy: `todoMacos` and `todoIos`
compile the same `examples/todo-apple/shared`, and the two entry points are 1 and 4 lines of
actual code.

`Shim+UIKit.swift` is **388 non-comment lines against AppKit's 385**, and it compiled and
passed all 23 checks on the first run. The extra lines are where UIKit genuinely differs,
and each difference is structural rather than a renamed call — which is exactly why `#if`
inside function bodies would have been the wrong shape:

| | AppKit | UIKit |
|---|---|---|
| boolean control | `NSButton(checkboxWithTitle:)` | `UISwitch` — UIKit has no checkbox |
| label vs. field | both `NSTextField` | `UILabel` and `UITextField`, separate types |
| control events | one target/action pair | `addTarget(_:action:for:)` + a `UIControl.Event` mask |
| container tap | `NSView` target/action | a `UITapGestureRecognizer`, as GTK needs `GtkGestureClick` |
| font sizing | a point size | `UIFont.preferredFont(forTextStyle:)`, so it follows Dynamic Type |
| `ContentFit.Cover` | no equivalent; approximated | `.scaleAspectFill` does exactly this |
| measurement | `fittingSize` | `systemLayoutSizeFitting`, same zero-for-a-sized-container trap |

**The one asymmetry that is not cosmetic: who owns `main`.** On macOS `sui_app_start` calls
`NSApp.run()` and never returns. On iOS it cannot: iOS 27 refuses to launch an app that does
not adopt the UIScene lifecycle (S3), and a scene delegate cannot live inside the static
archive Scala Native produces. So the **host** owns `@main`, creates the window, hands its
root view over with `sui_set_root_view`, calls `ScalaNativeInit`, and only then enters Scala.
`sui_app_start` on iOS records the title and schedules `ready` one run-loop turn later, then
returns.

`AppleApp` cannot tell the difference, and that is the point: either way the main thread is
Unmanaged from the call onwards and `ready` fires with a root view that exists. The macOS
comment "`sui_app_start` does not return" is true of macOS and simply irrelevant on iOS.

The three iOS build constraints from S1 all still apply and are encoded in
`iosNativeSettings` in `build.sbt`: `BuildTarget.libraryStatic`, `GC.immix`, and the
linktime property `target.os -> "darwin"` without which javalib's `PosixThread` reaches for
`pthread_condattr_setclock` and the link fails.

## Next

- `Divider` is implemented but unexercised — `TodoApp` does not use it.
- `setFrame` is a no-op while every container is `ToolkitManaged`. It becomes real when a
  Yoga-driven `FrameBased` container lands.
- **The iOS side is simulator-only.** A device build needs `arm64-apple-ios` rather than the
  simulator triple and a signing identity; nothing in the code is simulator-specific, but it
  is untested and should not be claimed until M1 runs it.
- **`Shim+AppKit.swift` and `Shim+UIKit.swift` will drift.** Two hand-written files against
  one header have no mechanism keeping them in step beyond the header and the self-test. At
  240 functions that is untenable, which is the second argument for S3's generate-from-one-
  description recommendation.
