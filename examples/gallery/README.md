# The component gallery

Every component the framework has, on one screen, built for **every platform**.

| | |
|---|---|
| GTK4 (Linux) | `sbt galleryGtk/nativeLink`, then `./target/out/native0.5/scala-3.9.0/gallery-gtk/gallery-gtk` |
| Android | `sbt galleryAndroid/compile`, then gradle under `android/` |
| macOS | `modules/renderer-apple/shim/build-shim.sh macos` then `sbt galleryMacos/nativeLink` — needs Xcode, so on a Mac |
| iOS | `modules/renderer-apple/shim/build-shim.sh ios-sim`, `sbt galleryIos/nativeLink`, then `ios/ios-app/build-app.sh 'iPhone 17'` |

## The self-test

Every host takes the same switch, so one command shape checks any platform:

```bash
THICKET_GALLERY_SELFTEST=1 <the binary>     # GTK, macOS
ios/ios-app/build-app.sh                   # iOS — sets it for the simulator itself
```

It reads the mounted tree back out of the toolkit — `GtkInspect` on GTK, `AppleInspect` on
AppKit and UIKit — and asks the **same questions** on each, because the gallery's claim is
that one screen renders natively everywhere and that is only tested if every platform is
asked the same thing. It prints `ALL CHECKS PASSED` or the failures, and the iOS script
exits non-zero if the app did not pass.

Where a check has to differ it is because the platform genuinely differs, and the reason is
written at the check. The context menu is the clear case: on GTK it is a `GtkPopover` in the
widget tree, on Apple it is `NSView.menu` or a `UIContextMenuInteraction`, so one counts
popovers and the other asks which views carry menu items.

Entry symbols are checked without a Mac. An iOS example is three files in three languages
that meet only at link time, matched by name alone, so `shimGen`'s `HostEntrySpec` asserts
that Scala's `@exported`, the bridging header's `extern` and the Swift host's call all name
the same symbol. A rename in one place is otherwise not a compile error anywhere — just an
undefined symbol, on the machine least convenient for finding it.

## Why this exists, separately from the todo example

The todo example is an **app**: its shape is driven by what that app needs, so it uses the
widgets it happens to need and no others. This is a **conformance surface**: its shape is
driven by the catalogue. One screen exercises everything, and it is built on each platform
we claim to support.

That matters because of what the unit tests cannot see. `TestRenderer` answers for every
widget, so a widget that renders on GTK and silently does nothing on Android passes the
entire suite. The only thing that catches it is a screen that uses all of them, rendered by
each real renderer.

## If you add a component, add it here in the same change

Not in a follow-up. A catalogue entry that exists in `WidgetKind` but appears on no screen
is a claim nobody has checked — and the framework's central claim is precisely that these
widgets are native *everywhere*.

The `Prop` enum is exhaustive under `-Werror`, so a new prop already breaks every renderer
until each one handles it. There is no equivalent compiler pressure to *demonstrate* a
widget, which is why this is written down in three places: here, in the scaladoc at the top
of `Gallery.scala`, and in `docs/12-component-status.md` §12.10.

A component counts as present in the gallery when:

1. it appears in a labelled `section(...)`, so the screen reads as a catalogue;
2. something **observable** proves it is bound, not merely drawn — a `Label` echoing a
   `TextField`, a `ProgressBar` driven by the `Slider` above it. A widget that renders and
   ignores its signal looks fine in a screenshot;
3. the states worth seeing are all on screen at once: disabled as well as enabled, each
   `TextRole`, each `ContentFit`. Reaching a state through interaction means a reviewer will
   not see it.

## What it covers

Labels in every `TextRole`, buttons by role and disabled, `TextField`, `SecureField`,
`Checkbox`, `Toggle`, `Slider`, determinate `ProgressBar`, `ActivityIndicator`, `Divider`,
`Spacer`, `Grow`, `Image` in two fits, nested and horizontal `Scroll`, keyed `ForEach`,
virtualised `LazyColumn`, a per-row `ContextMenu`, subtree theming with `Provide`, the two
presented widgets (`Alert` and `Sheet`), toolbar `Action`s and a navigation push.
