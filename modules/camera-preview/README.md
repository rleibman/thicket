# thicket-camera-preview

A live camera preview in the app's own layout, with a still capture from it. `thicket-camera`
hands the user to the camera app; this keeps them in the app (a profile photo framed in place,
a receipt). The design is `docs/17-camera-preview.md`, approved 2026-11-01.

| Module | Platform | From |
|---|---|---|
| `thicket-camera-preview` | JVM, Native | the API: `CameraPreview`, `Facing`; `CameraPreview.unavailable(reason)`; `testing.FakeCameraPreview` |
| `thicket-camera-preview-android` | Android 8+ | Camera2 into a `TextureView`, a JPEG `ImageReader` on the same session; not CameraX, which is AndroidX |
| `thicket-camera-preview-apple` | iOS 17+, macOS 13+ | `AVCaptureSession` shown by an `AVCaptureVideoPreviewLayer` in a `UIView` / `NSView`, an `AVCapturePhotoOutput` on the same session; a Swift library of its own in the jar (`libthicketpreview.a`, `macos/` and `ios-sim/`) |

Not built yet: GTK (the camera portal and GStreamer, after the others: `docs/17` §17.8.2). Until
then, use `CameraPreview.unavailable(reason)`: its widget shows the reason.

```scala
val device  = AndroidDevice(this)                    // in createApp
val preview = AndroidCameraPreview(this, device)     // needs an Owner in scope
Column(spacing = 8)(
  preview.view(),                                    // ContentFit.Cover; Contain and Fill too
  Button("Take")(preview.capture(_.foreach(attach))),
  Button("Flip")(preview.facing.update(f => if f == Facing.Back then Facing.Front else Facing.Back))
)
```

On Apple (#148), the same handle from `AppleCameraPreview(device, cache)`:

```scala
val storage = AppleStorage(bundleId)
val device  = AppleDevice()
val preview = AppleCameraPreview(device, storage.dirs.cache)   // needs an Owner in scope
```

- **The widget** is `core`'s embedding point (`dsl.Platform`, `docs/17` §17.3), so the
  renderers know nothing of cameras and only an app that shows a preview links Camera2. Where the
  layout leaves its height open (in a scrolling column), it takes the frame's aspect.
- **It runs only while the widget is mounted, its surface is ready, the app is in front and the
  permission is granted**; anything else closes the camera. `running` says which.
- **Permission:** the app declares `android.permission.CAMERA` and asks through
  `thicket-device`'s `Permissions.request(Camera)`; until then, `NeedsPermission(Camera)`.
  Declaring it also means `thicket-camera-android`'s camera screen needs it granted.
- **`capture`** answers on the UI thread with a JPEG in `<cache>/thicket-preview/`, the
  preview's size where the camera offers one size for both (at most 1920×1080), or `None` when
  the preview is not running or a capture is under way (5 s at most).
- **Orientation** is composed by hand, since Camera2 does not do it: the view's transform turns
  the frame back by the display's rotation and scales it to the fit; the JPEG carries
  `JPEG_ORIENTATION` as `CaptureRequest` documents it.
- **One preview at a time** in the app: a second is `Unavailable("in use by another preview")`.
  A camera the device does not have is `Unavailable("no front camera")`.

### On Apple

- **`AVCaptureSession`**, configured, started and stopped on a serial queue of the library's own
  (`startRunning` blocks), with an `AVCapturePhotoOutput` for `capture` and an
  `AVCaptureVideoDataOutput` that only counts frames, for the self-tests. Preset `.photo`.
- **Fit:** `Cover` is `.resizeAspectFill`, `Contain` `.resizeAspect`, `Fill` `.resize`. The view
  has no size of its own, so it gives itself an aspect (height = width × aspect, priority 999):
  4:3 upright on iOS and 3:4 on macOS until a session runs, then the frame's as shown.
- **Orientation** on iOS through `AVCaptureDevice.RotationCoordinator`: the preview's connection
  follows `videoRotationAngleForHorizonLevelPreview`, and the photo is taken at
  `videoRotationAngleForHorizonLevelCapture`. A Mac's camera needs none.
- **Facing:** iOS's built-in wide-angle camera at that position. On macOS `Front` is the first
  camera not on the back, built-in or USB; `Back` is one the system reports on the back, so
  usually `Unavailable("no back camera")` (`docs/17` §17.4).
- **Permission:** the app's `Info.plist` declares `NSCameraUsageDescription` (iOS terminates an
  app that opens the camera without it; `availability` throws, naming the key, instead). A macOS
  executable run outside an app bundle embeds one: `ThicketNativeFlags.appleInfoPlist(plist)`,
  as the macOS gallery does. Asked through `thicket-device-apple`'s
  `Permissions.request(Camera)` (`AVCaptureDevice.requestAccess`); granted after the preview was
  made, remount the widget to start it.
- **`release`** (the embedding point's, #145) stops the session; the view itself is the shim's.
- **Link:** `libthicketpreview.a` at the app's final link, from the jar
  (`thicketCameraPreviewApple` in `sbt-thicket`), with `thicket-device-apple`'s library.

## How it is checked

- `CameraPreviewSpec` (JVM and Native, 2 tests): `CameraPreview.unavailable` and the fake.
- The Android gallery, on the `s2test` emulator, whose back camera is a rendered scene and which
  has no front camera: the preview runs with the back camera open; frames keep arriving and the
  picture is not blank; a capture is a JPEG in the cache of the size the session runs at
  (1280×720 there); Home closes the camera, by the preview's own lifecycle first (Android also
  takes the camera from a background app: without the lifecycle's stop, the stops recorded are
  `error 3` and `disconnected`, and the check fails); back in front, it opens again; the front
  camera says it is not there; flipping back opens the back one. With `capture` stubbed, the
  JPEG check fails.
- GTK shows `CameraPreview.unavailable`: its status, its widget's "not available on GTK", and a
  capture's `None`.
