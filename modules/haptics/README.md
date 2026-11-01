# thicket-haptics

A short tap the user feels, confirming a touch. The plan is in `docs/16-device-services.md`,
§16.3 and §16.10 item 5.

| Module | Platform | From |
|---|---|---|
| `thicket-haptics` | JVM, Native | the API: `Haptics`, `Impact`; `Haptics.none(reason)`; `testing.RecordingHaptics` |
| `thicket-haptics-android` | Android | `View.performHapticFeedback` |

GTK has no haptic hardware to drive: use `Haptics.none(reason)`. Apple's half is a Mac issue.

```scala
Button("Delete") {
  haptics.impact(Impact.Heavy)   // AndroidHaptics; on GTK, Haptics.none("…")
  delete()
}
```

- **Fire and forget**, from the UI thread. Where there is nothing to feel (a desktop, or a
  phone with touch feedback turned off), the calls do nothing. Haptics confirm what the screen
  already shows, so none is ever needed.
- **Android:** `performHapticFeedback` on the current `ThicketActivity`'s window, so the
  system's own feedback, following the user's setting, with no `VIBRATE` permission. `Light`
  is `CLOCK_TICK`, `Medium` `VIRTUAL_KEY`, `Heavy` `LONG_PRESS`, `selection` `KEYBOARD_TAP`.

## How it is checked

The Android gallery plays a tap and checks `performHapticFeedback` answered true. After the
run, `build.sh` looks for the gallery's taps in the system's vibrator history
(`dumpsys vibrator_manager`, since the launch). With the call removed, both fail.
