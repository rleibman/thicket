# thicket-haptics

A short tap the user feels, confirming a touch. The plan is in `docs/16-device-services.md`,
§16.3 and §16.10 item 5.

| Module | Platform | From |
|---|---|---|
| `thicket-haptics` | JVM, Native | the API: `Haptics`, `Impact`; `Haptics.none(reason)`; `testing.RecordingHaptics` |
| `thicket-haptics-android` | Android | `View.performHapticFeedback` |
| `thicket-haptics-apple` | Scala Native, macOS and iOS | `UIImpactFeedbackGenerator`, `UISelectionFeedbackGenerator`; `NSHapticFeedbackManager` (#123) |

GTK has no haptic hardware to drive: use `Haptics.none(reason)`.

```scala
Button("Delete") {
  haptics.impact(Impact.Heavy)   // AndroidHaptics, AppleHaptics(); on GTK, Haptics.none("…")
  delete()
}
```

- **Fire and forget**, from the UI thread. Where there is nothing to feel (a desktop, or a
  phone with touch feedback turned off), the calls do nothing. Haptics confirm what the screen
  already shows, so none is ever needed.
- **Android:** `performHapticFeedback` on the current `ThicketActivity`'s window, so the
  system's own feedback, following the user's setting, with no `VIBRATE` permission. `Light`
  is `CLOCK_TICK`, `Medium` `VIRTUAL_KEY`, `Heavy` `LONG_PRESS`, `selection` `KEYBOARD_TAP`.
- **iOS:** `UIImpactFeedbackGenerator(style: .light / .medium / .heavy).impactOccurred()`, one
  generator per strength kept for the app's life, and
  `UISelectionFeedbackGenerator().selectionChanged()`. The phone's System Haptics setting
  decides whether anything is felt. `availability` is `Available`.
- **macOS: mapped, not `Haptics.none`.** `NSHapticFeedbackManager.defaultPerformer`, with
  `performanceTime: .now`: `Light` is `.alignment`, `Medium` `.generic`, `Heavy` `.levelChange`,
  `selection` `.alignment`. Unlike GTK there is an API and, on current MacBooks and the Magic
  Trackpad 2, hardware: a Force Touch trackpad, felt when the user's finger is on it —
  which it is when they have just clicked a button with it. With a mouse nothing is felt and
  nothing fails, which is the contract above. `availability` is `Available` because the
  performer exists on every macOS the shim supports; AppKit does not say whether a Force Touch
  trackpad is attached.
- **Apple's helper** is C through the Objective-C runtime
  (`resources/scala-native/thicket_feedback.c`), as `thicket-clipboard-apple`'s is, with UIKit
  or AppKit named in its object file.

## How it is checked

The Android gallery plays a tap and checks `performHapticFeedback` answered true. After the
run, `build.sh` looks for the gallery's taps in the system's vibrator history
(`dumpsys vibrator_manager`, since the launch). With the call removed, both fail.

The Apple galleries check the label says `Available` and press "Tap", which must return. That is
all a self-test can show there: the iOS simulator plays nothing and has no history to read, and
a Mac's trackpad has none either. Not measured on a device or by hand on a trackpad yet.
