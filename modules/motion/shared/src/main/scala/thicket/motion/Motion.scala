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

package thicket.motion

import thicket.device.{AppLifecycle, Availability, WhileInFront}
import thicket.signals.{Disposable, Owner, Signal, Var}

/** Three numbers along the device's axes. With the device flat and its screen facing up: x to the right, y towards the
  * top of the screen, z out of the screen (Android's and Core Motion's convention).
  */
final case class Vec3(
  x: Double,
  y: Double,
  z: Double
)

/** Which way the device faces, in radians: `roll` about its y axis, `pitch` about its x axis, `yaw` about z (the
  * heading; Android's azimuth). All three are zero with the device flat, screen up, its top pointing north.
  */
final case class Attitude(
  roll:  Double,
  pitch: Double,
  yaw:   Double
)

/** What the motion hardware measures. */
enum Sensor {

  /** Acceleration in m/s², gravity included: about (0, 0, 9.81) at rest, flat, screen up. */
  case Acceleration

  /** How fast the device turns about each axis, in rad/s. */
  case RotationRate

  /** Which way it faces, as the platform fuses it from the other sensors. */
  case Attitude

}

/** How often to sample. Faster costs battery; the platform may deliver less often than asked. */
enum Rate {

  /** About 5 a second: enough to show a reading. */
  case Normal

  /** About 15 a second: smooth on screen. */
  case UI

  /** About 50 a second: for a game, or a gesture. */
  case Game

}

/** The device's motion sensors (`docs/16-device-services.md` §16.3, §16.6).
  *
  * Each reading is a `Signal`, scoped to the `Owner` it is asked for in: the sensor runs while that owner lives and the
  * app is in the foreground, and stops in the background. `None` until the first sample, and for a sensor the device
  * does not have (see [[availability]]). Call from the UI thread; samples arrive there.
  */
trait Motion {

  /** Whether the device has `sensor`. Motion sensors need no permission. */
  def availability(sensor: Sensor): Availability

  def acceleration(rate: Rate = Rate.UI)(using Owner): Signal[Option[Vec3]]

  def rotationRate(rate: Rate = Rate.UI)(using Owner): Signal[Option[Vec3]]

  def attitude(rate: Rate = Rate.UI)(using Owner): Signal[Option[Attitude]]

}

/** What a platform supplies; [[Motion.apply]] adds the lifecycle. Calls arrive on the UI thread. */
trait MotionProvider {

  def availability(sensor: Sensor): Availability

  /** Starts sending `sensor`'s samples to `onSample`, on the UI thread, until the result is disposed. For
    * [[Sensor.Attitude]] the sample is (roll, pitch, yaw). Called only while `availability(sensor)` is `Available`.
    */
  def start(
    sensor:   Sensor,
    rate:     Rate,
    onSample: Vec3 => Unit
  ): Disposable

}

object Motion {

  /** `provider`'s sensors, running only while the app is in the foreground. */
  def apply(
    provider:  MotionProvider,
    lifecycle: AppLifecycle
  ): Motion =
    new Motion {

      def availability(sensor: Sensor): Availability = provider.availability(sensor)

      def acceleration(rate: Rate)(using Owner): Signal[Option[Vec3]] = follow(Sensor.Acceleration, rate)

      def rotationRate(rate: Rate)(using Owner): Signal[Option[Vec3]] = follow(Sensor.RotationRate, rate)

      def attitude(rate: Rate)(using Owner): Signal[Option[Attitude]] =
        follow(Sensor.Attitude, rate).map(_.map(v => Attitude(roll = v.x, pitch = v.y, yaw = v.z)))

      private def follow(
        sensor: Sensor,
        rate:   Rate
      )(using Owner
      ): Signal[Option[Vec3]] = {
        val sample = Var(Option.empty[Vec3])
        WhileInFront(lifecycle) { () =>
          Option.when(provider.availability(sensor) == Availability.Available) {
            provider.start(sensor, rate, v => sample.set(Some(v)))
          }
        }
        sample
      }

    }

  /** A `Motion` with no sensors, for `reason`: GTK and macOS, which have none to offer yet. */
  def unavailable(reason: String): Motion =
    new Motion {

      def availability(sensor: Sensor): Availability = Availability.Unavailable(reason)

      def acceleration(rate: Rate)(using Owner): Signal[Option[Vec3]] = Signal.const(None)

      def rotationRate(rate: Rate)(using Owner): Signal[Option[Vec3]] = Signal.const(None)

      def attitude(rate: Rate)(using Owner): Signal[Option[Attitude]] = Signal.const(None)

    }

}
