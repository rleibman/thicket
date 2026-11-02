# thicket-location

The device's position. The plan is in `docs/16-device-services.md`, §16.3 and §16.10 item 4.

| Module | Platform | From |
|---|---|---|
| `thicket-location` | JVM, Native | the API: `Location`, `Fix`, `Accuracy`, `LocationProvider`; `Location(provider, lifecycle)`, `Location.unavailable(reason)`; `testing.FakeLocationProvider` |
| `thicket-location-android` | Android | the framework's `LocationManager` (no Play Services, `docs/16` §16.9) |
| `thicket-location-apple` | iOS | `CLLocationManager` (#118): `AppleLocation()`; the manager and its delegate are in `thicket-device-apple`'s Swift library, beside the location permission |

GTK and macOS have no location service yet (`docs/16` §16.9, decision 7): use
`Location.unavailable(reason)`.

```scala
// In a ThicketActivity's createApp: AndroidLocation(this). On iOS: AppleLocation().
// On GTK and macOS: Location.unavailable("…").
def screen(location: Location, permissions: Permissions)(using Owner): Element =
  location.availability match {
    case Availability.Available =>
      Label(location.updates(Accuracy.Fine).map(_.fold("Locating…")(f => s"${f.latitude}, ${f.longitude}")))
    case Availability.NeedsPermission(p) =>
      Button("Use my location")(permissions.request(p)(_ => ()))  // then call updates
    case Availability.Unavailable(why) => Label(why)
  }
```

- **Only while the app is in front.** `updates(accuracy)` is a `Signal[Option[Fix]]` scoped to
  the `Owner` it is called in. The platform is listened to while that owner lives and the app is
  in the foreground (`thicket-device`'s `AppLifecycle`). Each return to the front starts again
  with the platform's last known position, since the device may have moved meanwhile.
- **Availability.** `NeedsPermission(Permission.Location)` until the user grants either
  location permission; `Unavailable(reason)` when location is off in the system settings or
  the platform has none. `updates` called before it is `Available` starts at the next return
  to the front once it is; call it after the permission is granted to start at once.
- **One position.** `current(accuracy)(onResult)` takes the next report and stops; `None` at once
  if location is not available. Dispose the result to stop waiting.
- **Android:** `Fine` uses the GPS provider when fine permission is granted, else the network
  provider; `Coarse` prefers the network. The manifest must declare `ACCESS_FINE_LOCATION`
  and/or `ACCESS_COARSE_LOCATION`, or `availability` throws, as `thicket-device`'s
  permissions do. Positions arrive on the main thread, at most one a second.
- **iOS:** ask with `ApplePermissions.request(Permission.Location)`, which is
  `requestWhenInUseAuthorization` (when in use only; no background location). The app's
  `Info.plist` must have `NSLocationWhenInUseUsageDescription`, or `availability` throws naming
  it: iOS ignores the request without it, and terminates an app that then uses location.
  `NeedsPermission` while not yet asked or denied; `Unavailable` when restricted or with
  location services off. `Fine` is `kCLLocationAccuracyBest`, `Coarse`
  `kCLLocationAccuracyKilometer`; `Fix.altitudeMeters` is `ellipsoidalAltitude`.
- **A new platform** supplies a `LocationProvider` (`availability`, `start`, `last`), and
  `Location(provider, lifecycle)` adds the lifecycle.

## How it is checked

- `LocationSpec` (JVM and Native, 7 tests) drives `FakeLocationProvider` and `FakeLifecycle`:
  following reports, stopping in the background and with the owner, restarting in front from
  the last known position, waiting for availability, and `current`.
- The Android gallery's Location section is moved with `adb emu geo fix` (`DRIVE geo`): a
  position in front reaches the section, one sent in the background does not, and back in
  front it shows where the device went. Android throttles background location by itself, so
  the self-test also counts Thicket's listeners on the real provider: removing the background
  stop fails that count, while the on-screen check still passes.
- The iOS gallery does the same on the simulator (#118): `run-ios-app.sh` grants location with
  `simctl privacy`, and `DRIVE geo <lat>,<lon>` is `simctl location set`. iOS suspends an app in
  the background, so the counts are what check the stop: through the wrapping provider, and
  `libthicketdevice.a`'s own listener table (`AppleDevice.testing.locationListeners`). Removing
  the background stop fails the count; the on-screen check still passes.
- The GTK and macOS galleries check that the section says location is unavailable.
