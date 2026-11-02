# thicket-device

The app's lifecycle and its permissions. Every later device service (location, motion, the
camera) is built on these, because only the host hears either: the Activity, the GTK window,
the Apple scene or window. The plan is in `docs/16-device-services.md`, §16.3 and §16.10 item 3.

| Module | Platform | From |
|---|---|---|
| `thicket-device` | JVM, Native | the API: `AppLifecycle`, `AppState`, `Permissions`, `Permission`, `PermissionStatus`, `Availability`, `Device`; fakes in `testing` (`FakeLifecycle`, `FakePermissions`) |
| `thicket-device-gtk` | Scala Native | `GtkApp`'s window and application |
| `thicket-device-android` | Android | `ThicketActivity`'s callbacks and the system's permission dialog |
| `thicket-device-apple` | Scala Native, macOS and iOS | the platform's notifications and each service's authorisation API, through its own Swift library (`libthicketdevice.a`, in the jar) |


```scala
// In the host: GtkApp.run(...) { ... GtkDevice() ... }; in a ThicketActivity's createApp, AndroidDevice(this);
// inside AppleApp.run, AppleDevice().
def screen(device: Device)(using Owner): Element = {
  Signal.effect {
    device.lifecycle.state() match {
      case AppState.Foreground => // start listening
      case AppState.Background => // stop, and save
    }
  }
  Button("Turn on reminders") {
    device.permissions.request(Permission.Notifications) {
      case PermissionStatus.Granted => // ...
      case _                        => // say what is missing, and how to change it
    }
  }
}
```

## Availability

Each device service answers `availability`: `Available`, `NeedsPermission(permission)` or
`Unavailable(reason)` (`docs/16` §16.6). "No GPS on this desktop" is a branch in the app, not
an exception. Location is the first service to report it (`modules/location`).

## Only while in front

`WhileInFront(lifecycle)(start)` runs a sensor while an owner lives and the app is in the
foreground: `start` is called on each return to the front, and what it returned is disposed in
the background and with the owner. Location and motion are built on it.

## Lifecycle

`state` is a `Signal[AppState]`: `Foreground` while the app is on screen, otherwise
`Background`. Every change happens on the UI thread. Every host starts in `Background`: while
the app is being built, no window is shown and no Activity has started.

- **Android:** `Foreground` from any `ThicketActivity`'s `onStart` to the last one's `onStop`,
  so a dialog over the app (a permission request, say) does not count as background. A
  rotation stops one Activity and starts its replacement. That stop reports
  `isChangingConfigurations`, so the app stays in the foreground through it.
- **GTK:** `Foreground` while the window is mapped, and its surface is neither minimised nor
  suspended. Suspended is GTK 4.12+'s "not visible to the user", and a compositor reports it
  for a window that is minimised, on another workspace, or fully covered. It does not follow
  focus: a window beside the one you are typing in is still on screen.
  - Measured on GNOME 50 (Wayland): minimising reaches `Background` after 3.3 s. Wayland does
    not tell a client it is minimised, and mutter reports suspended after its own delay.
  - Under Xvfb, with no window manager, only hiding and showing the window change it.
- **iOS:** `Foreground` from a scene's `willEnterForeground` to the last scene's
  `didEnterBackground`. Not activation: the notifications alert over the app did not even
  deactivate the scene, and a headless simulator never activated one at all (measured, #114).
  Settings opened over the gallery reached `Background` 4.0–4.6 s after the request, and launching
  the gallery again brought the same process back in 0.6–1.3 s (both through `simctl launch`).
- **macOS:** `Foreground` while any part of any of the app's windows is visible
  (`NSApp.occlusionState`). Hidden (Cmd-H), minimised, ordered out or fully covered is
  `Background`, and so is everything while the screen is locked. Not "active", which is focus,
  as on GTK. One notification covers all of them: hiding reached `Background` in 35 ms,
  minimising after its animation (~0.6 s), in a probe app.

`onTerminate(f)` runs `f` when the app closes for good:

- on Android, when the last Activity finishes (Back at the root, `finish()`), not when one
  rotates;
- on GTK, at the application's shutdown;
- on macOS, on `NSApplication.willTerminateNotification` (Quit, `NSApp.terminate`);
- on iOS, on `UIApplication.willTerminateNotification`, which iOS rarely sends: never to a
  suspended app, and `simctl terminate` kills the app without it (measured).

Don't save state there. Android and iOS kill a background app without calling anything, so
save when the app goes to the background.

## Permissions

`status(p)` is the answer as it stands, and `request(p)(onResult)` asks the user, if needed,
and calls `onResult` on the UI thread. Call both from the UI thread.

| Permission | Android | GTK | macOS and iOS |
|---|---|---|---|
| `Location` | `ACCESS_FINE_LOCATION` and/or `ACCESS_COARSE_LOCATION`; either granted is `Granted` | `Granted` | `CLLocationManager`, when in use (`requestWhenInUseAuthorization`, answered by its delegate; #118); needs `NSLocationWhenInUseUsageDescription` on iOS, `NSLocationUsageDescription` on macOS |
| `Camera` | `CAMERA` | `Granted` | `AVCaptureDevice`, `.video`; needs `NSCameraUsageDescription` |
| `Microphone` | `RECORD_AUDIO` | `Granted` | `AVCaptureDevice`, `.audio`; needs `NSMicrophoneUsageDescription` |
| `Notifications` | `POST_NOTIFICATIONS` from Android 13; before that, always `Granted` | `Granted` | `UNUserNotificationCenter` (alert, sound, badge); provisional counts as `Granted`; needs an app bundle |

- **One request at a time.** A request made while a dialog is up waits until that one is
  answered. Every platform needs this, and on Android a second `requestPermissions` would
  cancel the first.
- **Declare it, or it throws.** On Android, a permission missing from `AndroidManifest.xml`
  is refused at once, with no dialog. That looks just like the user saying no. So `status`
  and `request` throw `IllegalStateException`, naming the `<uses-permission>` to add.
- **`Denied`** means not granted: never asked, or refused. Asking again shows the dialog
  again, until the user refuses for good. After that the answer is `Denied` at once, and only
  the system settings can change it. Android never answers `Restricted`.
- **GTK has nothing to ask in advance.** Outside a sandbox an app may use what the system
  has. Inside one (Flatpak), the portal asks the user when a device is first used, and the
  service using it reports that it is unavailable if the user says no. So every permission is
  `Granted`, and a request answers on the next turn of the main loop.
- **Apple asks once.** After "Don't Allow" a request answers `Denied` at once, with no alert;
  only Settings can change it. `Restricted` is real there (`.restricted`: parental controls, a
  device policy). Not yet asked reads as `Denied`.
- **Apple: declare it, or it throws.** iOS *terminates* an app that asks for the camera or the
  microphone without the usage string in its `Info.plist`, so `status` and `request` throw
  `IllegalStateException` naming the key first. `UNUserNotificationCenter` raises for a process
  outside an app bundle (the macOS gallery is a plain executable), so notifications throw there,
  saying so. An iOS host's `build-app.sh` adds usage strings with `USAGE_STRINGS`
  (`examples/ios-host/run-ios-app.sh`).
- **Apple's answers arrive on a queue of the platform's own;** the Swift library moves each to
  the main thread before calling Scala, and reads the status back rather than trusting the
  completion's `Bool`, so a refusal given earlier reads as such.

## How it is checked

- `DeviceSpec` (JVM and Native, 7 tests) covers:
  - the request queue: one dialog at a time, answers in order, an answer given while asking,
    a request made from inside an answer, and an asking that throws;
  - the terminate hooks;
  - the fakes.
- The gallery's Device section shows the state, the number of returns to the front, and the
  notifications answer, with a button to ask. Its self-tests drive each one through the real
  host:
  - **GTK:** hiding and showing the window, and the terminate hook at shutdown.
  - **Android:** Home and back, both answers in the system's dialog (`build.sh` taps them),
    the undeclared `CAMERA`, and the terminate hook on Back.
  - **iOS simulator** (12 checks, gallery 191 on "iPhone 17e"): Settings opened over the
    gallery and the gallery launched again (`[gallery] DRIVE home` / `front`, acted on by
    `examples/ios-host/run-ios-app.sh`); the notifications alert answered "Don't Allow" by an
    XCUITest (`examples/ios-host/tapper`, ~8 s a tap; nothing else can tap a simulator, and
    `simctl privacy` has no notifications service), then asked again and answered `Denied` at
    once; the microphone granted by `simctl privacy` and answered `Granted`; the camera's
    missing usage string; and the terminate hook, on a posted `willTerminateNotification`.
  - **macOS** (10 checks, gallery 195; hidden → `Background` 49 ms, minimised 595 ms):
    hiding and unhiding the app, minimising and restoring its window, all through AppKit; notifications throwing outside an
    app bundle; the camera's missing usage string; and the terminate hook from a real
    `NSApp.terminate`, which prints the run's verdict, so a hook that does not run leaves no
    verdict. A locked screen occludes every window, so there the lifecycle checks are skipped,
    saying so, and the app checked to be in the background.
  - **Controls** (each fix removed, then restored), iOS: no `didEnterBackground` observer, the
    app is suspended unseen and the run ends with no verdict; no `willEnterForeground`, 2 fail;
    answers not delivered, 3 fail; no will-terminate observer, 1 fails (5 s); no usage-string
    check, 1 fails (`Success(Denied)`). macOS: no will-terminate observer, no verdict; no
    occlusion observer, 4 fail.
