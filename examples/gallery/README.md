# The component gallery

Every component the framework has, on one screen, built for **every platform**.

| | |
|---|---|
| GTK4 (Linux) | `sbt galleryGtk/nativeLink`, then `./target/out/native0.5/scala-3.9.0/gallery-gtk/gallery-gtk` |
| Android | `android/build.sh` — sbt for the Scala jars, then gradle under `android/`; installs and launches on an attached device or emulator |
| macOS | `modules/renderer-apple/shim/build-shim.sh macos` then `sbt galleryMacos/nativeLink` — needs Xcode, so on a Mac |
| iOS | `modules/renderer-apple/shim/build-shim.sh ios-sim`, `sbt galleryIos/nativeLink`, then `ios/ios-app/build-app.sh 'iPhone 17'` |

## The self-test

Every host takes the same switch, so one command shape checks any platform:

```bash
THICKET_GALLERY_SELFTEST=1 <the binary>     # GTK, macOS
ios/ios-app/build-app.sh                   # iOS — sets it for the simulator itself
SELFTEST=1 android/build.sh                # Android — exits non-zero unless it passed
```

**Android (2026-10-20).** The Android gallery had no Gradle project until the whole-repo
review found it: `GalleryActivity` compiled and could not be launched. It now builds, and its
self-test (`GallerySelfTest`) reads the views back the way `SelfTest` does for the todo app —
23/23 on the `s2test` emulator. It is not a line-by-line port of the GTK checks; it covers the
sections, the effect, the throwing handler, Grow in a Column, the Spinner's selection and
placeholder, linear spacing (1000 rows 31.9 ms, 2000 rows 56.0 ms) and the LazyColumn's
re-binding. Popping that list found two crashes on the way: `ListView` refuses both
`removeView` and `setOnClickListener(null)`, which a page's disposal called.

It reads the mounted tree back out of the toolkit — `GtkInspect` on GTK, `AppleInspect` on
AppKit and UIKit — and asks the **same questions** on each, because the gallery's claim is
that one screen renders natively everywhere and that is only tested if every platform is
asked the same thing. It prints `ALL CHECKS PASSED` or the failures, and the iOS script
exits non-zero if the app did not pass.

Where a check has to differ it is because the platform genuinely differs, and the reason is
written at the check. The context menu is the clear case: on GTK it is a `GtkPopover` in the
widget tree, on Apple it is `NSView.menu` or a `UIContextMenuInteraction`, so one counts
popovers and the other asks which views carry menu items.

**Measured on Apple (#31):** 20/20 on macOS and on the iOS simulator. The image check — added
then, because the Image section's heading being on screen said nothing about the pictures —
found the iOS app showing neither: its working directory is `/`, so the repo-relative
`docs/assets/thicket-logo.png` resolved nowhere. The Apple shims now look a relative path up
in the app bundle when it does not exist from the working directory, and the iOS
`build-app.sh` copies the logo into the bundle under the same path.

**#85:** the macOS host shows the SVG logo when `AppleApp.decodesImage` says NSImage takes
it (macOS 14+; measured on 27.0.1), the PNG otherwise. The image check now reads each
picture's size back (`sui_image_size`): the SVG's own 472.658x599.168 on macOS, 560x710 for
the PNG on iOS; on macOS the bytes-backed Fill picture paints 28.9% of its view (the SVG's
transparent background; the opaque PNG paints 100%). **171/171** on macOS, **163/163** on iOS.

**#45 / #46** added Picker, DatePicker, SegmentedControl, ZStack and Link checks that ask the
platform what GTK asks — options, selection, date, a choice through the control's own action,
links with the opener recording, ZStack placement by alignment rect: **48/48** on macOS and
**47/47** on the iOS simulator, where a menu-backed Picker cannot be chosen from code and the
test says so.

**#62** added the Grid's: each cell's row and column, read from `NSGridView` on AppKit and
from the construction on UIKit, then a row inserted in the middle by clicking the real button
and removed again; and, on both, the frames — every column starts at its predecessor's widest
cell plus the spacing, every row at its tallest. **58/58** on macOS and **57/57** on iOS.
Falsified on both: ignoring the column count fails five checks, inserting at the end instead
of in place fails two.

**#77** added the selection checks GTK has, asked of what the Apple control draws
(`sui_shown_option`). After **Select last** and **Swap the options**, the Picker shows "Pea" at
index 2. **Clear** shows a blank (D13), and so does a Swap after it. The report counter does
not move. The SegmentedControl keeps its index across new options, which the self-test hands
the renderer directly, because the screen has no button for it. Its **Clear** leaves no segment
chosen. Results: **78/78** on macOS, and 76 of 77 on the iOS simulator with #73's Image
failing, both with `THICKET_ALLOW_UNBUILT=1` until TabView (#69) is built.

**#73** added the Apple checks for the sections the whole-repo review added. They cover the
Errors section: the throw reaches `ErrorSink`, and the next click works. They cover the
Effects section's result, a picker option swap, and the layout labels and Spacers, read as
frames. They cover the indeterminate `ProgressBar` and the padded horizontal `Scroll`. They
read each `Image`'s fit and picture from the image view. A GC stress step drives the
nested Scala → Swift → Scala path while a worker collects. A sheet is opened and closed
against the renderer's tables and `Handles.count`. The 10 000-row list's rows are read by
position from the platform's table after a delete and a rename. The self-test now waits for
the effect, as GTK's does, and ends asynchronously.
Measured with `THICKET_ALLOW_UNBUILT=1` (TabView is #69): **102 of 107** on macOS and
**103 of 106** on iOS. Every failure names the issue that owns it: AppKit's picker swap
(#77), the Apple layout cross-axis (#87), and `Scroll(padding)` on both (#76). Each new
check was falsified, and the controls are listed in `docs/decisions.md`.

**#87** added the Layout section's checks, from frames: a Row spans its Column and stays
inside the screen Column's padding, a Spacer pushes "right" to the far edge, a grown label
takes the room its sibling does not, a Spacer in a Column lines "bottom" up with the taller
column beside it, and the aligned labels span the section; on AppKit also that a push button
in a Column and the buttons of a Row keep their own width; and a ZStack in a Row is as wide
as its widest child. **75/75** on macOS and **71/72** on the iOS simulator (the miss is the
bytes-backed Image, #73). `THICKET_FRAME_DUMP=1` prints every widget's kind, text and frame,
for comparing a layout change across the whole screen.

**#96** made "a Row with nothing grown leaves its slack after its children" a check on both
toolkits (it was AppKit's only): the "Accent" button is under half its section and the Row's
last button ends at least 50 pt before the section does. On the iOS simulator "Accent" went
from 232 pt to its own 48 pt.

**#104** checks the Image section's Row on both toolkits, from frames and pixels. The three
pictures sit side by side without overlapping. Each is at least 1 pt and in its picture's
shape, all three are the same width, and each paints more than 5 % of its view. Before this
"holds an image" was true of a view squeezed to 0 pt. On the iOS simulator the Row went from
342/0/0 × 710 pt to 114×145 pt each.

**#78** added the Android gallery's linear-mount check to both Apple hosts, for a Column, a
Row and a Grid: 2 000 children must mount in under three times 1 000 (off screen, fastest of
three; unmounting is timed and printed, not checked). Grid on iOS went from 3.9 s / 16.6 s
(ratio 4.2) to 42 ms / 93 ms (2.2), on macOS from 472 ms / 2.1 s (4.5) to 44 ms / 91 ms
(2.1). The stack checks passed before the fix too (ratio 2.6–2.8, now 2.1–2.5), so for stacks
the check guards a regression to quadratic, not the small search the fix removed.

**#150** added the Apple checks for RadioGroup and Stepper, 13 on each toolkit. Each radio
control is clicked through its own action: a radio `NSButton`, or a checkmark-list row's
`.touchUpInside`. The steppers are pressed with `sui_step`, and the label beside each is read
for `2` / `1.50`, then `3` / `1.75`, then a stop at 5. The app's own `Selected` and `Value`
writes must not come back as events. A last click with a handler that only records shows
the platform's own grouping. The app's write-back would otherwise hide an ungrouped set.
**246** on the iOS simulator, **231** on macOS with the screen locked. Falsified: with the
radios ungrouped, with the range opened, and with an echoed write, the matching check
fails each time.

**#145** gave the Apple hosts the Platform section's checks: the host's view is an
`NSTextField` / a `UILabel` made in C through the Objective-C runtime
(`apple-shared/src/main/resources/scala-native/thicket_gallery_native_label.c`), handed to the
renderer +1 retained. The checks find that very view in the window, filling its host; read
the "not available on AppKit/UIKit" label; replace the view; and find the old one was
released while alive and in its host (asked through a weak reference first), then freed —
the weak reference goes nil, so the shim held the only retain — with the renderer's tables
unchanged. **228** on macOS, **239** on the iOS simulator. Falsified on macOS: `release`
skipped, `release` after the shim dropped the view, an empty label, and the shim keeping the
caller's retain each fail exactly their check.

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

Labels in every `TextRole` and alignment, buttons by role and disabled, `TextField`,
`SecureField`, `Checkbox`, `Toggle`, a `Picker` whose options can be swapped and its
selection cleared, every curated `Icon` as an `IconButton`, `SegmentedControl`, a
`DatePicker` on a leap day, `RadioGroup` and two `Stepper`s (one of step 0.25) built as §12.2a
exceptions — on Android a − / value / + row, on iOS a checkmark list, `NSStepper` and `UIStepper` beside a label — two `Link`s (one also tappable), a tappable `Label`, `Slider`, determinate and
indeterminate `ProgressBar`, `ActivityIndicator`, `Divider`, `Spacer` in a Row and in a
Column, `Grow`, a two-column `Grid` with a row inserted in the middle, `ZStack` as a corner
badge and as a centred overlay, `Image` in all three fits from a file and from bytes, a
tappable `Row`, the screen's `Scroll` with both padding and `.safeArea()`, a padded horizontal
`Scroll` inside it whose child can be replaced by a taller one, keyed `ForEach`, virtualised `LazyColumn` whose first row
can be deleted and renamed, a per-row `ContextMenu`, subtree theming with `Provide`, a
handler that throws, a ZIO effect, the two presented widgets (`Alert` and `Sheet`), toolbar
`Action`s, a navigation push, a `TabView` with a tab inserted in the middle, a `Platform` view
(`docs/17` §17.3) that the host makes itself — a `GtkLabel`, a `TextView`, an `NSTextField`,
a `UILabel` — and replaces, the self-test checking the host's own widget is the one on screen
and was released while still alive, beside one that no platform has and says so, and storage: a
launch counter in preferences and notes in SQLite, written on a worker thread, with the JDBC
conformance checks run against the platform's driver (GTK, Android, macOS and iOS),
Magnum's checks on GTK, macOS and iOS, and a secret in the platform's secure store, kept from
one run to the next and held to the secrets conformance checks (GTK, Android, macOS and iOS). On Apple the
self-test also holds the storage directories to what Foundation reports. On Android the
self-test also runs `examples/http`'s HTTP checks over `thicket-http-android`, after the
others: `build.sh` starts `examples/http/server.py` on this machine and `adb reverse`s its
port, and the app's network security config allows cleartext to loopback only.

The Device section (every host) shows the app's lifecycle, the number of times it came
back to the front, and the notifications permission, with a button to ask for it. On GTK the
self-test hides and shows the window, and checks the terminate hook once the main loop has
ended. On Android it can't press Home or answer the system's dialog itself, so it asks
`build.sh` to, with `[gallery] DRIVE <action>` lines in the log:
- `home`: press Home;
- `front`: bring the same Activity back;
- `deny` and `allow`: tap the dialog's buttons, found with `uiautomator`;
- `geo <lon> <lat>`: `adb emu geo fix`, which the emulator's GPS reports;
- `accel x:y:z` and `gyro x:y:z`: `adb emu sensor set`, for the accelerometer and gyroscope.

`build.sh` revokes `POST_NOTIFICATIONS` before the run, so the dialog appears each time.
After the verdict it presses Back and looks for the terminate hook's line.

On macOS the self-test hides and unhides the app and minimises and restores its window through
AppKit, and ends with `NSApp.terminate`: the terminate hook prints the verdict. With the Mac's
screen locked every window is occluded, so it skips those, saying so. The gallery is a plain
executable, so asking for notifications throws (no app bundle). On iOS `ios-app/build-app.sh`
is the user, through `examples/ios-host/run-ios-app.sh`'s `drive`: `home` launches Settings
over the gallery, `front` launches the gallery again (the same process comes back), and `deny`
answers the alert through an XCUITest (`examples/ios-host/tapper`). It installs afresh and uninstalls at the end (a
just-booted simulator kept the last answer through an uninstall), so the alert is shown, and grants the microphone with `simctl privacy`. The terminate hook runs on a
posted `willTerminateNotification`.

The Location section shows whether positions can be had and the latest one, with "Use my
location" to ask for the permission. On GTK it says location is unavailable on the desktop.
On Android, `build.sh` installs with location granted, and the self-test sends one position in
front, then one in the background, and checks that only the first reaches the section until
the app is back. Android throttles background location by itself, so the self-test also
counts Thicket's listeners on the real provider: one in front, none in the background.
On iOS (#118) the same, on the simulator: `build-app.sh` grants location with `simctl privacy`
and moves it with `simctl location set` (`DRIVE geo`), and the count is both the wrapping
provider's and `libthicketdevice.a`'s, since iOS suspends a background app. macOS says, as
GTK does, that location is unavailable on the desktop.

The Motion section shows which sensors the device has, and each one's latest reading. On GTK
it says the desktop has none. On Android the self-test sets the emulator's acceleration and
rotation rate and checks they reach the section, checks the fused attitude arrives, and counts
listeners through Home and back, as for location. On macOS it says the desktop has none, as
on GTK. The iOS simulator has no motion hardware: the self-test checks the section names each
sensor with Core Motion's reason, and counts, through a provider wrapping the real one, that
none is started while the app is in front (#120).

The Clipboard and haptics section copies a numbered text and pastes it back, and plays a tap.
Both self-tests copy and paste twice: each paste must show that round's copy, so a clipboard
that never took the text, or kept an older one, fails. On GTK the section says there are no
haptics. On Android the self-test checks `performHapticFeedback` answered true, and after the
run `build.sh` looks for the gallery's taps in the system's vibrator history
(`dumpsys vibrator_manager`), counting only those since the launch. The Apple self-test (#123)
also requires each copy to raise the pasteboard's change count before it pastes — on iOS a
paste of text this process did not write waits on the "Allow Paste" prompt — and checks the
haptics label says available and "Tap" returns; the simulator plays nothing. After it,
`bin/verify-apple.sh` reads the last copy back from outside the app (`pbpaste`,
`xcrun simctl pbpaste`).

The Pickers section picks a photo and a document and saves one, through the system's own
pickers. On Android `build.sh` puts the gallery's logo in the media store and a text file in
Downloads, and taps them in the photo picker and the documents UI (`DRIVE pick-photo`,
`open-doc`, `save-doc`, `back`); after the run it reads the saved file back from Downloads. On
GTK the self-test turns the portal off (`GDK_DEBUG=no-portals`) so the file chooser is
in-process, finds it among the app's windows, sets the file and accepts or cancels. Each pick
must come back as a copy in the app's cache, byte for byte.

On iOS (#125) `build-app.sh` puts the logo in the simulator's photo library (`simctl addmedia`)
and a text file in Files' "On My iPhone", and the XCUITest tapper taps them in
`PHPickerViewController` and `UIDocumentPickerViewController`, taps Cancel, and Save in "On My
iPhone" (`DRIVE pick-photo`, `cancel-photo`, `pick-document`, `save-document`); after the save
it reads the file back from the simulator's Files storage. On macOS 27 the open and save panels
run in another process (`openAndSavePanelService`), whose OK cannot be pressed from the app: the
self-test checks the real sheet, its types and suggested name, and its own `cancel(_:)`, and
answers an accept with `ApplePickers.testing.choose`, a known file through the panel's own
completion. The panel's OK button is the one part not exercised.

The same section takes a photo. On Android `build.sh` presses the shutter and Done in the
emulator's camera app (`DRIVE photo`); the photo must come back as a JPEG in the app's cache,
and the MediaStore entry the camera wrote to must be gone. Backing out answers no photo. On GTK
and macOS the section says the desktop has no camera screen. On iOS (#129) the build script grants
the camera with `simctl privacy`; "Take a photo" must put `UIImagePickerController`'s camera
screen up, a second request must answer no photo at once, and its X, tapped through the
XCUITest tapper (`DRIVE cancel`), must answer no photo. The iOS 27 simulator has a camera
screen whose shutter takes nothing: after `DRIVE photo` the camera must answer nothing and stay
up, and the run says the copy into the cache was not measured. A device's shutter would bring a
JPEG back, checked for `FF D8 FF` in the cache; no device has run it. The section shows the
camera alone where the host has no pickers yet (Apple, until #125).

The Share section shares a text and a small file. On Android the gallery is also a share
target ("Thicket gallery inbox", `ShareTargetActivity`): `build.sh` taps it in the system's
chooser (`DRIVE share-pick`), and it reads what it was given, the file through the URI
Thicket's provider served. The inbox is the gallery itself, which needs no grant, so the
self-test also requires the share sheet, another app, to have read the file through it. On
GTK the section says there is no share sheet. On macOS the self-test finds the picker's popover,
reads the services it was offered and closes it; on iOS it finds the `UIActivityViewController`,
asks its item for the text and subject, and has an XCUITest tap "Copy" in it (`DRIVE share-copy`),
after which `simctl pbpaste` must read the shared text (#134).

The Camera preview section (`docs/17`) shows a live preview with "Take from the preview" and
"Flip the camera". On Android it is Camera2 on the emulator's back camera (the gallery declares
`CAMERA`, which `install -g` grants): the self-test brings it on screen, waits for frames, checks
the picture is not blank, takes a JPEG of the session's size, sends the app Home and back
(`DRIVE home`, `DRIVE front`) to see the camera closed by the preview's own lifecycle and opened
again, then flips to the front camera `s2test` does not have, which must say so. On GTK the
section shows `CameraPreview.unavailable`. On Apple (#148) it is `AppleCameraPreview`; the self-test
keeps the widget unmounted until the section's own checks, so the camera screen's checks (#129)
have the camera to themselves, then mounts it and checks the layer's gravity for each fit and
that the view is laid out at its own aspect. For each facing it takes the path the camera allows:
running (frames, the frame's aspect, a JPEG under `<cache>/thicket-preview/`, checked for its
format and size and deleted, the background and back), not permitted (nothing runs; the macOS
run asks through the system's dialog with `THICKET_PREVIEW_ASK=1`), or no such camera; then, if a
session ran, "Hide or show the preview" unmounts it, which must stop the session. The iOS 27
simulator lists no capture device, and the Mac's back is none, so those say so. The macOS
executable declares `NSCameraUsageDescription` in an embedded `Info.plist` (`macos/Info.plist`).

When the desktop is locked, GNOME suspends every window and the Device section's foreground
check fails. A private headless shell avoids that:
`dbus-run-session -- gnome-shell --headless --wayland --no-x11 --virtual-monitor 1024x768 --wayland-display thicket-headless &`,
then `WAYLAND_DISPLAY=thicket-headless bin/with-keyring.sh bin/gtk-selftests.sh`.
