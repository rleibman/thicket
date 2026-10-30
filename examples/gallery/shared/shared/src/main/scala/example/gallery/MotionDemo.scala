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

package example.gallery

import thicket.device.Availability
import thicket.motion.{Attitude, Motion, Sensor, Vec3}
import thicket.signals.{Owner, Signal}

/** The Motion section's state: which sensors the device has, and each one's latest reading while the app is in front.
  */
final class MotionDemo(motion: Motion)(using Owner) {

  val sensors: String = MotionDemo.sensorsLabel(Sensor.values.toList.map(s => s -> motion.availability(s)))

  val acceleration: Signal[String] =
    motion.acceleration().map(_.fold(MotionDemo.noAcceleration)(MotionDemo.accelerationLabel))

  val rotationRate: Signal[String] = motion.rotationRate().map(_.fold(MotionDemo.noRotation)(MotionDemo.rotationLabel))

  val attitude: Signal[String] = motion.attitude().map(_.fold(MotionDemo.noAttitude)(MotionDemo.attitudeLabel))

}

object MotionDemo {

  def sensorsLabel(all: List[(Sensor, Availability)]): String =
    all
      .map {
        case (s, Availability.Available) => s"$s available"
        case (s, _)                      => s"no $s"
      }.mkString("Sensors: ", ", ", "")

  val noAcceleration: String = "Acceleration: no reading"
  val noRotation:     String = "Rotation: no reading"
  val noAttitude:     String = "Attitude: no reading"

  def accelerationLabel(v: Vec3): String = f"Acceleration: ${v.x}%.1f, ${v.y}%.1f, ${v.z}%.1f m/s²"

  def rotationLabel(v: Vec3): String = f"Rotation: ${v.x}%.2f, ${v.y}%.2f, ${v.z}%.2f rad/s"

  def attitudeLabel(a: Attitude): String =
    f"Attitude: roll ${math.toDegrees(a.roll)}%.0f°, pitch ${math.toDegrees(a.pitch)}%.0f°, yaw ${math.toDegrees(a.yaw)}%.0f°"

}
