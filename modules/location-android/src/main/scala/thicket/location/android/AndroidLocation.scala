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

package thicket.location.android

import _root_.android.Manifest
import _root_.android.content.Context
import _root_.android.content.pm.PackageManager
import _root_.android.location.{Location as AndroidFix, LocationListener, LocationManager}
import _root_.android.os.{Build, Looper}
import thicket.device.{Availability, Permission, PermissionStatus, Permissions}
import thicket.device.android.{AndroidLifecycle, AndroidPermissions}
import thicket.location.{Accuracy, Fix, Location, LocationProvider}
import thicket.signals.Disposable

/** Positions from the framework's `LocationManager` (`docs/16` §16.9, decision 3: no Play Services).
  *
  *   - `Fine` uses the GPS provider when fine permission is granted and the GPS is on, else the network provider.
  *     `Coarse` prefers the network provider. The GPS provider needs fine permission; a user who granted only
  *     approximate location gets network positions.
  *   - `NeedsPermission` until either location permission is granted; the app's manifest must declare at least one
  *     (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`), or asking throws, as [[AndroidPermissions]] does.
  *   - `Unavailable` when location is off in the system settings, or no usable provider is on.
  *
  * Positions arrive on the main thread.
  */
final class AndroidLocationProvider(
  context:     Context,
  permissions: Permissions
) extends LocationProvider {

  private val app: Context = Option(context.getApplicationContext).getOrElse(context)
  private val manager = app.getSystemService(Context.LOCATION_SERVICE).asInstanceOf[LocationManager]

  def availability: Availability =
    if permissions.status(Permission.Location) != PermissionStatus.Granted then
      Availability.NeedsPermission(Permission.Location)
    else if Build.VERSION.SDK_INT >= 28 && !manager.isLocationEnabled then
      Availability.Unavailable("Location is turned off in the system settings")
    else if providerFor(Accuracy.Coarse).isEmpty then Availability.Unavailable("No location provider is turned on")
    else Availability.Available

  def start(
    accuracy: Accuracy,
    onFix:    Fix => Unit
  ): Disposable =
    providerFor(accuracy) match {
      case None           => () => ()
      case Some(provider) =>
        val listener = new LocationListener {
          def onLocationChanged(l: AndroidFix): Unit = onFix(AndroidLocationProvider.toFix(l))
          // Abstract before Android 11; a provider turned off simply stops reporting.
          override def onProviderEnabled(provider:  String): Unit = ()
          override def onProviderDisabled(provider: String): Unit = ()
        }
        manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper)
        () => manager.removeUpdates(listener)
    }

  def last(accuracy: Accuracy): Option[Fix] =
    providerFor(accuracy).flatMap(p => Option(manager.getLastKnownLocation(p))).map(AndroidLocationProvider.toFix)

  private def granted(name: String): Boolean = app.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED

  /** The provider to use for `accuracy`, of those turned on and allowed. */
  private def providerFor(accuracy: Accuracy): Option[String] = {
    val gps = LocationManager.GPS_PROVIDER
    val network = LocationManager.NETWORK_PROVIDER
    val order = accuracy match {
      case Accuracy.Fine   => List(gps, network)
      case Accuracy.Coarse => List(network, gps)
    }
    order.find { p =>
      (p != gps || granted(Manifest.permission.ACCESS_FINE_LOCATION)) &&
      manager.getAllProviders.contains(p) && manager.isProviderEnabled(p)
    }
  }

}

object AndroidLocationProvider {

  def toFix(l: AndroidFix): Fix =
    Fix(
      latitude = l.getLatitude,
      longitude = l.getLongitude,
      accuracyMeters = Option.when(l.hasAccuracy)(l.getAccuracy.toDouble),
      altitudeMeters = Option.when(l.hasAltitude)(l.getAltitude),
      timeMillis = l.getTime
    )

}

/** Location for an app: `AndroidLocation(this)` in a `ThicketActivity`'s `createApp`. Stops in the background, through
  * [[AndroidLifecycle]].
  */
object AndroidLocation {

  def apply(context: Context): Location =
    Location(AndroidLocationProvider(context, AndroidPermissions(context)), AndroidLifecycle)

}
