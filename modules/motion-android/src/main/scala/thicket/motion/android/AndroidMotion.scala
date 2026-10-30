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

package thicket.motion.android

import _root_.android.content.Context
import _root_.android.hardware.{Sensor as AndroidSensor, SensorEvent, SensorEventListener, SensorManager}
import _root_.android.os.{Handler, Looper}
import thicket.device.Availability
import thicket.device.android.AndroidLifecycle
import thicket.motion.{Motion, MotionProvider, Rate, Sensor, Vec3}
import thicket.signals.Disposable

/** The motion sensors through the framework's `SensorManager`:
  *
  *   - `Acceleration` is the accelerometer (`TYPE_ACCELEROMETER`, gravity included, m/s²);
  *   - `RotationRate` is the gyroscope (`TYPE_GYROSCOPE`, rad/s);
  *   - `Attitude` is the fused rotation vector (`TYPE_ROTATION_VECTOR`), turned into roll, pitch and yaw with
  *     `getRotationMatrixFromVector` and `getOrientation`.
  *
  * A sensor the device lacks is `Unavailable`. None needs a permission. Samples arrive on the main thread.
  */
final class AndroidMotionProvider(context: Context) extends MotionProvider {

  private val manager =
    Option(context.getApplicationContext)
      .getOrElse(context).getSystemService(Context.SENSOR_SERVICE)
      .asInstanceOf[SensorManager]

  private val main = Handler(Looper.getMainLooper)

  private def hardware(sensor: Sensor): Option[AndroidSensor] =
    Option(manager.getDefaultSensor(AndroidMotionProvider.androidType(sensor)))

  def availability(sensor: Sensor): Availability =
    hardware(sensor) match {
      case Some(_) => Availability.Available
      case None    => Availability.Unavailable(s"This device has no ${AndroidMotionProvider.describe(sensor)}")
    }

  def start(
    sensor:   Sensor,
    rate:     Rate,
    onSample: Vec3 => Unit
  ): Disposable =
    hardware(sensor) match {
      case None     => () => ()
      case Some(hw) =>
        val rotation = new Array[Float](9)
        val angles = new Array[Float](3)
        val listener = new SensorEventListener {
          def onSensorChanged(e: SensorEvent): Unit = {
            val v = e.values
            sensor match {
              case Sensor.Attitude =>
                SensorManager.getRotationMatrixFromVector(rotation, v)
                val _ = SensorManager.getOrientation(rotation, angles)
                // getOrientation answers azimuth, pitch, roll.
                onSample(Vec3(angles(2).toDouble, angles(1).toDouble, angles(0).toDouble))
              case _ => onSample(Vec3(v(0).toDouble, v(1).toDouble, v(2).toDouble))
            }
          }
          def onAccuracyChanged(
            s:        AndroidSensor,
            accuracy: Int
          ): Unit = ()
        }
        val _ = manager.registerListener(listener, hw, AndroidMotionProvider.delay(rate), main)
        () => manager.unregisterListener(listener)
    }

}

object AndroidMotionProvider {

  def androidType(sensor: Sensor): Int =
    sensor match {
      case Sensor.Acceleration => AndroidSensor.TYPE_ACCELEROMETER
      case Sensor.RotationRate => AndroidSensor.TYPE_GYROSCOPE
      case Sensor.Attitude     => AndroidSensor.TYPE_ROTATION_VECTOR
    }

  /** `SENSOR_DELAY_NORMAL` (200 ms), `_UI` (66 ms), `_GAME` (20 ms). */
  def delay(rate: Rate): Int =
    rate match {
      case Rate.Normal => SensorManager.SENSOR_DELAY_NORMAL
      case Rate.UI     => SensorManager.SENSOR_DELAY_UI
      case Rate.Game   => SensorManager.SENSOR_DELAY_GAME
    }

  private def describe(sensor: Sensor): String =
    sensor match {
      case Sensor.Acceleration => "accelerometer"
      case Sensor.RotationRate => "gyroscope"
      case Sensor.Attitude     => "rotation vector sensor"
    }

}

/** Motion for an app: `AndroidMotion(this)` in a `ThicketActivity`'s `createApp`. Stops in the background, through
  * [[AndroidLifecycle]].
  */
object AndroidMotion {

  def apply(context: Context): Motion = Motion(AndroidMotionProvider(context), AndroidLifecycle)

}
