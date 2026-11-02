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

package thicket.tools.shim

import thicket.tools.shim.Abi.{Fn, Group}
import thicket.tools.shim.Abi.CType.*

/** The `thk_mot_*` ABI: `thicket-motion-ios`'s Swift library, the motion sensors through `CMMotionManager` (#120,
  * docs/16 §16.10 item 4).
  *
  * Its own surface, header, externs and Swift library, like [[DeviceAbi]]: an app that does not depend on
  * `thicket-motion-ios` links none of it, nor CoreMotion. iOS only: `CMMotionManager` is unavailable on macOS, which
  * uses `Motion.unavailable`.
  *
  * **Push, on the main thread only**, as [[DeviceAbi]]: the updates are delivered to `OperationQueue.main`, and the
  * Scala side wraps each sample in `GcState.guarded`. One manager per process, as Apple asks; a sensor has one handler,
  * so the Scala side shares each sensor among its listeners and asks for the fastest interval any of them wants.
  *
  * A sensor is `thicket.motion.Sensor`'s ordinal: 0 Acceleration, 1 RotationRate, 2 Attitude. The sample is as Core
  * Motion reports it, and Scala converts: acceleration in g (Core Motion's sign), rotation rate in rad/s, attitude as
  * roll, pitch and yaw in radians. `thicket_motion.h` defines the one callback type, `thk_mot_vec3_cb`.
  */
object MotionAbi {

  private val sensor = Abi.p("sensor", I32)

  val sensors: List[Fn] = List(
    Fn(
      "thk_mot_available",
      I32,
      List(sensor),
      doc = "1 if the device has `sensor`: isAccelerometerAvailable, isGyroAvailable,\n" +
        "   isDeviceMotionAvailable. 0 otherwise, and on the simulator, which has no motion hardware."
    ),
    Fn(
      "thk_mot_start",
      Void,
      List(sensor, Abi.p("interval", F64), Abi.p("sample", Vec3Cb), Abi.p("ctx", I64)),
      doc = "Starts `sensor`'s updates to the main queue every `interval` seconds, calling `sample`\n" +
        "   with each one. Already started: only the interval changes, and the first `sample` stays.\n" +
        "   Does nothing for a sensor the device lacks."
    ),
    Fn(
      "thk_mot_stop",
      Void,
      List(sensor),
      doc = "Stops `sensor`'s updates: stopAccelerometerUpdates, stopGyroUpdates, stopDeviceMotionUpdates."
    )
  )

  val testing: List[Fn] = List(
    Fn(
      "thk_mot_test_active",
      I32,
      List(sensor),
      doc = "1 while Core Motion is delivering `sensor` (isAccelerometerActive, isGyroActive,\n" +
        "   isDeviceMotionActive), 0 otherwise."
    )
  )

  val groups: List[Group] = List(
    Group("sensors", sensors),
    Group("testing", testing, doc = "For self-tests: what Core Motion itself says.")
  )

  val all: List[Fn] = groups.flatMap(_.fns)

}
