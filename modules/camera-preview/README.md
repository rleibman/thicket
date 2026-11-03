# thicket-camera-preview

A live camera preview in the app's own layout, with a still capture from it. `thicket-camera`
hands the user to the camera app; this keeps them in the app (a profile photo framed in place,
a receipt). The design is `docs/17-camera-preview.md`, approved 2026-11-01.

| Module | Platform | From |
|---|---|---|
| `thicket-camera-preview` | JVM, Native | the API: `CameraPreview`, `Facing`; `CameraPreview.unavailable(reason)`; `testing.FakeCameraPreview` |
| `thicket-camera-preview-android` | Android 8+ | Camera2 into a `TextureView`, a JPEG `ImageReader` on the same session; not CameraX, which is AndroidX |

Not built yet: Apple (iOS and macOS, `AVCaptureSession`; the Mac's, #148) and GTK (the camera portal
and GStreamer, after the others: `docs/17` §17.8.2). Until then, use
`CameraPreview.unavailable(reason)`: its widget shows the reason.

```scala
val device  = AndroidDevice(this)                    // in createApp
val preview = AndroidCameraPreview(this, device)     // needs an Owner in scope
Column(spacing = 8)(
  preview.view(),                                    // ContentFit.Cover; Contain and Fill too
  Button("Take")(preview.capture(_.foreach(attach))),
  Button("Flip")(preview.facing.update(f => if f == Facing.Back then Facing.Front else Facing.Back))
)
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
