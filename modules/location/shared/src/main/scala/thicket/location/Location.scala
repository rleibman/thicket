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

package thicket.location

import thicket.device.{AppLifecycle, Availability, WhileInFront}
import thicket.signals.{Disposable, Owner, Signal, Var}

/** Where the device is: one position report from the platform. */
final case class Fix(
  latitude:  Double,
  longitude: Double,

  /** The radius, in metres, the position is likely within, if the platform says. */
  accuracyMeters: Option[Double],

  /** Height above the WGS 84 ellipsoid, in metres, if the platform says. */
  altitudeMeters: Option[Double],

  /** When the platform took it, in milliseconds since the epoch. */
  timeMillis: Long
)

/** How precise a position the app needs. `Coarse` is enough for a city or a weather forecast, and costs less battery:
  * on Android it uses the network rather than the GPS, and needs only coarse location permission.
  */
enum Accuracy {

  case Fine, Coarse

}

/** The device's position (`docs/16-device-services.md` §16.3, §16.6).
  *
  * {{{
  * location.availability match {
  *   case Availability.Available          => Label(location.updates(Accuracy.Fine).map(_.fold("Locating…")(show)))
  *   case Availability.NeedsPermission(p) => Button("Use my location")(permissions.request(p)(...))
  *   case Availability.Unavailable(why)   => Label(why)
  * }
  * }}}
  *
  * Call from the UI thread; positions arrive there.
  */
trait Location {

  /** Whether positions can be had now. Asked afresh on each call: the user can grant the permission, or turn location
    * off, at any time.
    */
  def availability: Availability

  /** The latest position, as it changes: the platform's last known one to start with, if it has one, and `None` until a
    * position arrives otherwise.
    *
    * The platform is listened to while the owner lives and the app is in the foreground. It stops when the app goes to
    * the background, and starts again when it comes back, so a screen showing a position needs no stop call and no
    * lifecycle code. Each return to the foreground asks [[availability]] again: if it was not `Available` when this was
    * called — the permission not yet granted, say — updates start at the next return to the front once it is. Call it
    * after the permission is granted to start at once.
    */
  def updates(accuracy: Accuracy)(using Owner): Signal[Option[Fix]]

  /** One position: the next one the platform reports, to `onResult`, after which it stops listening. `None` at once if
    * [[availability]] is not `Available`. Dispose the result to stop waiting.
    */
  def current(accuracy: Accuracy)(onResult: Option[Fix] => Unit): Disposable

}

/** What a platform supplies; [[Location.apply]] adds the lifecycle. Calls arrive on the UI thread. */
trait LocationProvider {

  def availability: Availability

  /** Starts reporting positions to `onFix`, on the UI thread, until the result is disposed. Called only while
    * `availability` is `Available`.
    */
  def start(
    accuracy: Accuracy,
    onFix:    Fix => Unit
  ): Disposable

  /** The last position the platform knows, without waiting for a new one; `None` if it has none, or may not say. */
  def last(accuracy: Accuracy): Option[Fix]

}

object Location {

  /** `provider`'s positions, listened to only while the app is in the foreground. */
  def apply(
    provider:  LocationProvider,
    lifecycle: AppLifecycle
  ): Location =
    new Location {

      def availability: Availability = provider.availability

      def updates(accuracy: Accuracy)(using Owner): Signal[Option[Fix]] = {
        val fix = Var(if available then provider.last(accuracy) else None)
        WhileInFront(lifecycle) { () =>
          Option.when(available) {
            // Where the platform last saw the device: it may have moved while the app was away.
            provider.last(accuracy).foreach(f => fix.set(Some(f)))
            provider.start(accuracy, f => fix.set(Some(f)))
          }
        }
        fix
      }

      def current(accuracy: Accuracy)(onResult: Option[Fix] => Unit): Disposable =
        if !available then {
          onResult(None)
          () => ()
        } else {
          var done = false
          var sub:      Option[Disposable] = None
          def finish(): Unit = {
            done = true
            sub.foreach(_.dispose())
            sub = None
          }
          val s = provider.start(
            accuracy,
            f =>
              if !done then {
                finish()
                onResult(Some(f))
              }
          )
          // A provider may report at once, from inside start: then it is already finished, and only `s` is left to stop.
          if done then s.dispose() else sub = Some(s)
          () => finish()
        }

      private def available: Boolean = provider.availability == Availability.Available

    }

  /** A `Location` that is never available, for `reason`: GTK and macOS, which have no location service yet. */
  def unavailable(reason: String): Location =
    new Location {

      def availability: Availability = Availability.Unavailable(reason)

      def updates(accuracy: Accuracy)(using Owner): Signal[Option[Fix]] = Signal.const(None)

      def current(accuracy: Accuracy)(onResult: Option[Fix] => Unit): Disposable = {
        onResult(None)
        () => ()
      }

    }

}
