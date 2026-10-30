# thicket-motion

The device's motion sensors. The plan is in `docs/16-device-services.md`, §16.3 and §16.10
item 4.

| Module | Platform | From |
|---|---|---|
| `thicket-motion` | JVM, Native | the API: `Motion`, `Vec3`, `Attitude`, `Sensor`, `Rate`, `MotionProvider`; `Motion(provider, lifecycle)`, `Motion.unavailable(reason)`; `testing.FakeMotionProvider` |
| `thicket-motion-android` | Android | the framework's `SensorManager` |

GTK and macOS have no motion sensors to offer yet (`docs/16` §16.9, decision 7): use
`Motion.unavailable(reason)`. The iOS half is a Mac issue.

```scala
// In a ThicketActivity's createApp: AndroidMotion(this). On GTK: Motion.unavailable("…").
def level(motion: Motion)(using Owner): Element =
  Label(motion.attitude(Rate.UI).map(_.fold("No reading")(a => f"Tilted ${math.toDegrees(a.roll)}%.0f°")))
```

- **Only while the app is in front.** Each reading is a `Signal`, scoped to the `Owner` it is
  asked for in. The sensor runs while that owner lives and the app is in the foreground
  (`thicket-device`'s `WhileInFront`), and stops in the background. `None` until the first
  sample.
- **The readings.**
  - `acceleration`: m/s², gravity included, about (0, 0, 9.81) flat on a table, screen up.
  - `rotationRate`: rad/s about each axis.
  - `attitude`: `Attitude(roll, pitch, yaw)` in radians, the platform's fusion of the others.
  - Axes: x to the right, y towards the top of the screen, z out of it.
- **Rate.** `Normal` (about 5 a second), `UI` (about 15), `Game` (about 50). The platform may
  deliver less often.
- **Availability.** A sensor the device lacks is `Unavailable`; the others still work. Motion
  needs no permission.
- **Android:** the accelerometer (`TYPE_ACCELEROMETER`), the gyroscope (`TYPE_GYROSCOPE`), and
  the rotation vector (`TYPE_ROTATION_VECTOR`) through `getOrientation`.
- **A new platform** supplies a `MotionProvider` (`availability`, `start`), and
  `Motion(provider, lifecycle)` adds the lifecycle.

## How it is checked

- `MotionSpec` (JVM and Native, 5 tests) drives `FakeMotionProvider` and `FakeLifecycle`.
- The Android gallery's Motion section is driven with `adb emu sensor set` (`DRIVE accel`,
  `DRIVE gyro`). Set values reach the section, the fused attitude arrives, and listeners are
  counted through Home and back. Android stops background sensor events by itself, so the
  count, not the screen, is what fails when the background stop is removed (measured).
- The GTK gallery checks that the section says the desktop has no sensors.
