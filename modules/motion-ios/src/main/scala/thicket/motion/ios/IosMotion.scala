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

package thicket.motion.ios

import scala.collection.mutable
import scala.scalanative.unsafe.*
import thicket.core.GcState
import thicket.device.Availability
import thicket.device.apple.AppleLifecycle
import thicket.motion.{Motion, MotionProvider, Rate, Sensor, Vec3}
import thicket.signals.Disposable

/** The motion sensors on iOS, through Core Motion's `CMMotionManager` (`libthicketmotion.a`):
  *
  *   - `Acceleration` is the accelerometer, converted from Core Motion's g to m/s², gravity included, **and negated**
  *     (see [[IosMotionProvider.acceleration]]), so it reads about (0, 0, 9.81) flat, screen up, as on Android;
  *   - `RotationRate` is the gyroscope, rad/s, as Core Motion reports it;
  *   - `Attitude` is device motion's fused `attitude`: (roll, pitch, yaw), radians, in Core Motion's own reference frame
  *     (`xArbitraryZVertical`: yaw is zero where updates started, not north).
  *
  * A sensor the device lacks is `Unavailable` (the simulator has none). None needs a permission. Samples arrive on the
  * main thread.
  *
  * Process-wide, like the one `CMMotionManager` behind it: each sensor has one Core Motion handler, shared by every
  * listener, and runs at the fastest interval any of them asked for.
  */
object IosMotionProvider extends MotionProvider {

  /** Standard gravity, m/s² per g. */
  val g: Double = 9.80665

  /** Core Motion's accelerometer, in g, as Thicket's: m/s², the same axes, **the opposite sign**. Core Motion reads
    * about (0, 0, -1) g flat on a table, screen up (Apple's Core Motion documentation: gravity is reported as
    * an acceleration towards the ground); Android reads the support force, about (0, 0, +9.81) m/s². Thicket's
    * convention is Android's (`modules/motion/README.md`), so the sample is multiplied by -g. Unmeasured: the simulator
    * has no accelerometer, and no device was available (#120).
    */
  def acceleration(coreMotion: Vec3): Vec3 = Vec3(-g * coreMotion.x, -g * coreMotion.y, -g * coreMotion.z)

  /** `*UpdateInterval`, in seconds: about 5, 15 and 50 a second. */
  def interval(rate: Rate): Double =
    rate match {
      case Rate.Normal => 0.2
      case Rate.UI     => 1.0 / 15
      case Rate.Game   => 1.0 / 50
    }

  private final class Listener(
    val rate:     Rate,
    val onSample: Vec3 => Unit
  )

  /** Each sensor's listeners, in the order they started. */
  private val listeners: mutable.Map[Sensor, Vector[Listener]] = mutable.Map.empty

  // A CFuncPtr cannot close over state: the context is the sensor's ordinal, and the listeners are found through it.
  private val sampled: MotionShim.Vec3Cb =
    CFuncPtr4.fromScalaFunction(
      (
        ctx: Long,
        x:   CDouble,
        y:   CDouble,
        z:   CDouble
      ) =>
        GcState.guarded {
          val sensor = Sensor.fromOrdinal(ctx.toInt)
          val v = if sensor == Sensor.Acceleration then acceleration(Vec3(x, y, z)) else Vec3(x, y, z)
          listeners.getOrElse(sensor, Vector.empty).foreach(_.onSample(v))
        }
    )

  def availability(sensor: Sensor): Availability =
    if MotionShim.thk_mot_available(sensor.ordinal) != 0 then Availability.Available
    else Availability.Unavailable(s"This device has no ${describe(sensor)}")

  def start(
    sensor:   Sensor,
    rate:     Rate,
    onSample: Vec3 => Unit
  ): Disposable =
    if availability(sensor) != Availability.Available then () => ()
    else {
      val listener = Listener(rate, onSample)
      listeners(sensor) = listeners.getOrElse(sensor, Vector.empty) :+ listener
      retune(sensor)
      var stopped = false
      () =>
        if !stopped then {
          stopped = true
          listeners(sensor) = listeners.getOrElse(sensor, Vector.empty).filterNot(_ eq listener)
          retune(sensor)
        }
    }

  /** Starts the sensor at its listeners' fastest interval, or stops it when none is left. */
  private def retune(sensor: Sensor): Unit = {
    val ls = listeners.getOrElse(sensor, Vector.empty)
    if ls.isEmpty then MotionShim.thk_mot_stop(sensor.ordinal)
    else MotionShim.thk_mot_start(sensor.ordinal, ls.map(l => interval(l.rate)).min, sampled, sensor.ordinal.toLong)
  }

  private def describe(sensor: Sensor): String =
    sensor match {
      case Sensor.Acceleration => "accelerometer"
      case Sensor.RotationRate => "gyroscope"
      case Sensor.Attitude     => "device motion (attitude)"
    }

  /** For self-tests: whether Core Motion itself is delivering `sensor` now (`isAccelerometerActive` and the others). */
  object testing {

    def active(sensor: Sensor): Boolean = MotionShim.thk_mot_test_active(sensor.ordinal) != 0

  }

}

/** Motion for an iOS app: `IosMotion()` inside `AppleApp.run`. Stops in the background, through [[AppleLifecycle]].
  * Needs `libthicketmotion.a` at the host's final link (`ios-sim/libthicketmotion.a` in this jar). On macOS use
  * `Motion.unavailable`: `CMMotionManager` is iOS-only.
  */
object IosMotion {

  def apply(): Motion = Motion(IosMotionProvider, AppleLifecycle)

}
