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

import thicket.device.{AppState, Availability}
import thicket.device.testing.FakeLifecycle
import thicket.motion.testing.FakeMotionProvider
import thicket.signals.Owner
import zio.test.*

/** `sequential` is mandatory: these tests track signals, and the tracking context is process-global. See `SignalSpec`.
  */
object MotionSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Nothing] =
    suite("Motion")(
      test("each reading follows its own sensor, None until the first sample; attitude is (roll, pitch, yaw)") {
        given Owner = Owner()
        val hw = FakeMotionProvider()
        val motion = Motion(hw, FakeLifecycle())
        val acc = motion.acceleration()
        val rot = motion.rotationRate()
        val att = motion.attitude()
        val before = (acc.now, rot.now, att.now)
        hw.report(Sensor.Acceleration, Vec3(1, 2, 9.8))
        hw.report(Sensor.RotationRate, Vec3(0.1, 0.2, 0.3))
        hw.report(Sensor.Attitude, Vec3(0.5, -0.25, 3.0))
        assertTrue(
          before == (None, None, None),
          acc.now.contains(Vec3(1, 2, 9.8)),
          rot.now.contains(Vec3(0.1, 0.2, 0.3)),
          att.now.contains(Attitude(roll = 0.5, pitch = -0.25, yaw = 3.0))
        )
      },
      test("sensors stop in the background, ignore samples there, and run again in front") {
        given Owner = Owner()
        val hw = FakeMotionProvider()
        val life = FakeLifecycle()
        val acc = Motion(hw, life).acceleration()
        hw.report(Sensor.Acceleration, Vec3(1, 1, 1))
        life.set(AppState.Background)
        val away = hw.listening(Sensor.Acceleration)
        hw.report(Sensor.Acceleration, Vec3(2, 2, 2))
        val keptAway = acc.now
        life.set(AppState.Foreground)
        hw.report(Sensor.Acceleration, Vec3(3, 3, 3))
        assertTrue(
          away == 0,
          keptAway.contains(Vec3(1, 1, 1)),
          hw.listening(Sensor.Acceleration) == 1,
          acc.now.contains(Vec3(3, 3, 3))
        )
      },
      test("they stop when their owner is disposed, and run at the rate asked for") {
        val owner = Owner()
        given Owner = owner
        val hw = FakeMotionProvider()
        val motion = Motion(hw, FakeLifecycle())
        val _ = motion.rotationRate(Rate.Game)
        val _ = motion.attitude(Rate.Normal)
        val rates = (hw.rates(Sensor.RotationRate), hw.rates(Sensor.Attitude))
        owner.dispose()
        assertTrue(
          rates == (List(Rate.Game), List(Rate.Normal)),
          hw.listening(Sensor.RotationRate) == 0,
          hw.listening(Sensor.Attitude) == 0
        )
      },
      test("a sensor the device lacks is Unavailable and never started; the others still run") {
        given Owner = Owner()
        val hw = FakeMotionProvider(missing = Set(Sensor.RotationRate))
        val motion = Motion(hw, FakeLifecycle())
        val rot = motion.rotationRate()
        val acc = motion.acceleration()
        hw.report(Sensor.Acceleration, Vec3(0, 0, 9.8))
        assertTrue(
          motion.availability(Sensor.RotationRate).isInstanceOf[Availability.Unavailable],
          motion.availability(Sensor.Acceleration) == Availability.Available,
          hw.listening(Sensor.RotationRate) == 0,
          rot.now.isEmpty,
          acc.now.contains(Vec3(0, 0, 9.8))
        )
      },
      test("Motion.unavailable says why for every sensor, and never has a reading") {
        given Owner = Owner()
        val motion = Motion.unavailable("no motion sensors")
        assertTrue(
          Sensor.values.forall(s => motion.availability(s) == Availability.Unavailable("no motion sensors")),
          motion.acceleration().now.isEmpty,
          motion.rotationRate().now.isEmpty,
          motion.attitude().now.isEmpty
        )
      }
    ) @@ TestAspect.sequential

}
