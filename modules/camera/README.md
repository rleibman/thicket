# thicket-camera

Taking a photo with the platform's own camera screen: the user frames and shoots there, and
the app gets the photo. The plan is in `docs/16-device-services.md`, §16.3 and §16.10 item 5.

| Module | Platform | From |
|---|---|---|
| `thicket-camera` | JVM, Native | the API: `Camera`; `Camera.unavailable(reason)`; `testing.FakeCamera` |
| `thicket-camera-android` | Android 10+ | the camera app's `ACTION_IMAGE_CAPTURE` |
| `thicket-camera-apple` | iOS | `UIImagePickerController` with `sourceType = .camera`, through a Swift library of its own (`libthicketcamera.a`, in the jar) |

GTK and macOS have no camera screen to take a photo with (the portal's camera is a live
stream, not a capture; decision 7 for the Mac): use `Camera.unavailable(reason)`.

```scala
val camera = AndroidCamera(this)          // in createApp; on GTK and macOS, Camera.unavailable("…")
val camera = AppleCamera(storage.dirs.cache)   // iOS, inside AppleApp.run
Button("Take a photo")(camera.capturePhoto(_.foreach(attach)))
```

- **A JPEG in the app's cache**, as a `Path`, the same as a picked file (owner's decision,
  2026-10-30); `None` if the user backed out, the camera is not available, or another request
  is up (one at a time).
- **Android:** the camera app writes to a MediaStore entry Thicket creates; the photo is then
  copied into the cache and the entry deleted, so the user's photo library keeps nothing the
  app did not ask to keep. If Android kills the app while the camera app is in front, the next
  `AndroidCamera` sweeps away the entry it left: only `thicket-*.jpg` entries this app owns.
  - **Android 10+.** Before that, a MediaStore entry needs storage permission: `Unavailable`.
  - **`CAMERA` permission** is not needed. But an app that declares it (for its own preview,
    say) must have it granted, or Android refuses the camera screen: `NeedsPermission(Camera)`.

- **iOS** (#129): the picker's `.originalImage`, written as a JPEG at quality 0.9 to a file of
  the library's own in `NSTemporaryDirectory()`, then copied into the cache through
  `PickedFiles` and deleted. The app's `Info.plist` needs **`NSCameraUsageDescription`**, or
  iOS terminates the app when the camera opens: `availability` throws `IllegalStateException`
  naming it, as Android does for an undeclared permission. Then `Unavailable("This device has
  no camera")` when `isSourceTypeAvailable(.camera)` is false, `NeedsPermission(Camera)` while
  `AVCaptureDevice`'s answer for video is not yet given or denied (`thicket-device-apple`'s
  `Permissions.request(Camera)` asks), `Unavailable` where it is restricted. The iOS host's
  final link takes `ios-sim/libthicketcamera.a` and `thicket-device-apple`'s library.

## How it is checked

- `CameraSpec` (JVM and Native, 2 tests): the fake and `Camera.unavailable`.
- The Android gallery takes a photo in the emulator's camera app: `build.sh` presses the
  shutter and Done (`DRIVE photo`). The photo must come back as a JPEG in the app's cache, and
  the entry it was written to must be gone; backing out must answer no photo and leave no
  entry. With the delete removed, both entry checks fail. The self-test also plants a leftover
  entry and checks that a new `AndroidCamera` sweeps it; with the sweep disabled, that fails.
