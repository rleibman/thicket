/*
 * Copyright 2026 Roberto Leibman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package thicket.location.apple

import scala.collection.mutable
import scala.scalanative.unsafe.*
import thicket.core.GcState
import thicket.device.{AppLifecycle, Availability, Permission}
import thicket.device.apple.{AppleLifecycle, ApplePermissions, DeviceShim}
import thicket.location.{Accuracy, Fix, Location, LocationProvider}
import thicket.signals.Disposable

/** Location on iOS, from `CLLocationManager` (#118). `AppleLocation()` is `Location(AppleLocationProvider,
  * AppleLifecycle)`: listened to while the owner lives and the app is in front. macOS uses `Location.unavailable` for
  * now (`docs/16` §16.9, decision 7).
  *
  * The manager and its delegate are in `thicket-device-apple`'s Swift library, `libthicketdevice.a`, beside the
  * permission they share: ask with `ApplePermissions.request(Permission.Location)`, which is
  * `requestWhenInUseAuthorization`. The app's `Info.plist` must have `NSLocationWhenInUseUsageDescription`; without it
  * [[AppleLocationProvider.availability]] throws `IllegalStateException` naming the key, as the permission does: iOS
  * ignores the request, and terminates an app that then uses location.
  */
object AppleLocation {

  def apply(lifecycle: AppLifecycle = AppleLifecycle): Location = Location(AppleLocationProvider, lifecycle)

}

/** `CLLocationManager` as a [[LocationProvider]]. Call from the main thread; positions arrive there.
  *
  *   - **availability:** `NeedsPermission(Location)` while the authorisation is not yet asked or denied; `Unavailable`
  *     when it is restricted or location services are off; `Available` otherwise.
  *   - **start:** `desiredAccuracy` is `kCLLocationAccuracyBest` for `Fine` and `kCLLocationAccuracyKilometer` for
  *     `Coarse` (the finest any listener asks for), and the manager updates while any listener is left.
  *   - **last:** the manager's `location`.
  */
object AppleLocationProvider extends LocationProvider {

  def availability: Availability = {
    // Throws IllegalStateException, naming the Info.plist key, when it is missing.
    val _ = ApplePermissions.status(Permission.Location)
    DeviceShim.thk_dev_location_availability() match {
      case 0 => Availability.Available
      case 1 => Availability.NeedsPermission(Permission.Location)
      case 2 => Availability.Unavailable("location services are off in Settings")
      case _ => Availability.Unavailable("location is restricted on this device")
    }
  }

  // A CFuncPtr cannot close over state: each listener is a number here, passed through as the context.
  private val listeners = mutable.Map.empty[Long, Fix => Unit]
  private var nextId = 1L

  private val fixed: DeviceShim.VoidCb =
    CFuncPtr1.fromScalaFunction((ctx: Long) => GcState.guarded(listeners.get(ctx).foreach(f => read(0).foreach(f))))

  /** 0: the position being delivered; 1: the manager's last known one. */
  private def read(which: Int): Option[Fix] = {
    val out = stackalloc[CDouble](5)
    if DeviceShim.thk_dev_location_fix(which, out, out + 1, out + 2, out + 3, out + 4) == 0 then None
    else {
      def known(d: Double) = Option.when(!d.isNaN)(d)
      Some(Fix(out(0), out(1), known(out(2)), known(out(3)), out(4).toLong))
    }
  }

  def start(
    accuracy: Accuracy,
    onFix:    Fix => Unit
  ): Disposable = {
    val ctx = nextId
    nextId += 1
    listeners(ctx) = onFix
    val id = DeviceShim.thk_dev_location_start(if accuracy == Accuracy.Fine then 1 else 0, fixed, ctx)
    var stopped = false
    () =>
      if !stopped then {
        stopped = true
        listeners -= ctx
        DeviceShim.thk_dev_location_stop(id)
      }
  }

  def last(accuracy: Accuracy): Option[Fix] = read(1)

}
