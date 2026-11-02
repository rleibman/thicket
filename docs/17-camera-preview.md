# 17. Live camera preview — a design for approval

Status: **approved, 2026-11-01; not built yet.** `docs/16` §16.3 puts the live camera
preview first in tier 3 ("each its own design"), and the owner said yes to tier 3 on
2026-11-01. This is that design. The owner answered §17.8's questions the same day, taking
every recommendation (decision log, 2026-11-01).

## 17.1 What it is

A **widget**: a rectangle in the layout showing what the camera sees, live, with a shutter.
`Camera.capturePhoto` (`docs/16` §16.10 item 5, #130/#133) hands the user to the camera
app; this keeps them in the app — a barcode scanner, a profile photo framed in place, a
receipt scanner.

```scala
val preview = CameraPreview(facing = Facing.Back)   // a handle: state and actions
Column(
  preview.view(fit = ContentFit.Cover),             // the widget
  Button("Take")(preview.capture(photo => ...)),    // Option[Path], as Camera.capturePhoto
  Button("Flip")(preview.facing.set(Facing.Front))
)
preview.availability                                // Available | NeedsPermission(Camera) | Unavailable
```

## 17.2 v1 scope (agreed 2026-11-01)

- **In:** the preview, front or back camera, capture a still photo from it (a JPEG `Path` in
  the cache, as every capture so far), the lifecycle (§17.5), the permission.
- **Out of v1:** video recording, frame access (barcode decoding — an app would want ML Kit or
  Vision, which are platform libraries of their own), zoom, focus points, torch, exposure. Each
  is an addition to the handle later, not a change to it.

## 17.3 The main choice: how a widget reaches platform code (decided: the embedding point)

Every widget so far is a `WidgetKind` (`renderer-api`), and every renderer handles every kind
(`docs/12` §12.10's gallery rule). A camera preview as one more kind would put Camera2 in
`renderer-android`, AVFoundation in `renderer-apple` and a video pipeline in `renderer-gtk`:
every app would link them, whether it showed a camera or not, and the renderers would carry
the heaviest code in the project.

**Proposed: a general embedding point, and the preview as modules of its own.**

- **`Element.Platform(view: PlatformView)`** in `core`, and a `WidgetKind.Platform` the
  renderers handle once, generically: they ask a factory the *platform module* registered for
  a native view (an Android `View`, a `GtkWidget*`, an `NSView`/`UIView`), size it by the
  layout like any widget, and dispose it with its owner. The renderer knows nothing about
  cameras.
- **`thicket-camera-preview`** (API, `CameraPreview`, a fake for tests) and
  **`-android`, `-gtk`, `-apple`** (the views), one per platform as every service is (`docs/16`
  §16.6), so only an app that shows a preview links a camera stack.
- The same point later serves maps, a web view, a video player — everything `docs/05` §5.7
  keeps out of v1 but an app may want, without a renderer change each time.

The alternative, a built-in kind, is less code now (no extension point) and keeps
`TestRenderer` answering for it like any widget; the cost is above. **Recommended: the
embedding point.** It is a `core` and three-renderer change, reviewed on its own before the
preview, with the gallery showing a trivial platform view (a native label) on every platform.

## 17.4 Per platform

| | Preview | Capture from the preview | Permission |
|---|---|---|---|
| Android | Camera2 into a `TextureView` (not CameraX: AndroidX, as `docs/16` §16.9 already avoids). The sensor's orientation and the display's rotation are composed by hand; Camera2 does not. | `ImageReader` (JPEG) on the same session | `CAMERA`, runtime |
| iOS | `AVCaptureSession` + `AVCaptureVideoPreviewLayer` in a `UIView` | `AVCapturePhotoOutput` | `NSCameraUsageDescription`, `AVCaptureDevice.requestAccess` |
| macOS | the same, in an `NSView`; built-in and USB cameras | the same | the same (TCC) |
| GTK | the camera portal (`org.freedesktop.portal.Camera`) hands a PipeWire node; GStreamer's `pipewiresrc` into `gtk4paintablesink`, shown in a `GtkPicture` | a `jpegenc` tee from the same pipeline | the portal asks; `Unavailable` when no camera or no portal |

Facing on desktop: `Front` means the first camera, `Back` means none (`Unavailable`), unless
the device says otherwise.

## 17.5 Lifecycle and threads

- Runs only while the view is mounted **and** the app is in front (`thicket-device`'s
  `WhileInFront`, as location and motion): the camera is released in the background, as every
  platform requires, and on unmount.
- `capture` answers on the UI thread; frames never cross into Scala in v1.
- One preview at a time per app; a second answers `Unavailable("in use")`.

## 17.6 How it would be checked (measure, don't assert)

- **Android:** the emulator's back camera is a rendered 3D scene (`hw.camera.back =
  emulated`). Check frames arrive (the `TextureView` updates; a pixel sample changes between
  two frames), capture gives a JPEG of the preview's size, Home stops the session
  (`dumpsys media.camera` shows no client), back in front restarts it.
- **iOS simulator:** no camera: `Unavailable`, checked. Capture measured on a device, or stated
  as unmeasured.
- **macOS:** the Mac's own camera, if the runner has one; otherwise `Unavailable`, stated.
- **GTK:** CI has no camera. A virtual one (`v4l2loopback` fed by `gst-launch videotestsrc`)
  would give real frames through PipeWire, but needs a kernel module and packages on the
  machine (an install, owner's call, §17.8). Without it: `Unavailable` checked, and one manual
  run on this desktop's webcam recorded.
- Each check falsified as usual: with the background stop removed, the session-count check
  fails; with capture stubbed, the JPEG check fails.

## 17.7 Size and order

The largest piece in the plan (`docs/16` §16.3 says so). Proposed order, each its own PR:

1. The embedding point (§17.3) on GTK and Android, with the gallery's trivial platform view;
   Apple as a Mac issue.
2. `thicket-camera-preview` and `-android`, measured on the emulator.
3. `-apple`, the Mac's (iOS and macOS).
4. `-gtk`, after Android and Apple (§17.8.2).

## 17.8 Questions for the owner — answered 2026-11-01

The owner took every recommendation: (1) the embedding point; (2) `Unavailable` on GTK first;
(3) build macOS; (4) so no virtual camera yet; (5) v1 scope as proposed. The questions as asked:

1. **The embedding point (§17.3), or a built-in `WidgetKind`?** Recommended: the embedding point.
2. **GTK: build the GStreamer/PipeWire preview, or `Unavailable` on the desktop for now?** It
   adds GStreamer as an optional dependency (loaded with `dlopen`, as libsecret is, so an app
   without it still runs). Recommended: `Unavailable` first, the GTK preview after Android
   and Apple, so the desktop does not hold the rest back.
3. **macOS: preview with the Mac's camera, or `Unavailable` as decision 7 (§16.9) has it for
   desktop hardware?** Recommended: build it — AVFoundation is the same code as iOS, so it
   costs little once iOS exists.
4. **A virtual camera for GTK's CI (`v4l2loopback`)?** Only if (2) says build it; it is a
   system install on this box and the CI image.
5. **v1 scope (§17.2):** preview, facing and still capture only — agreed?
