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

import thicket.device.{AppState, Availability}
import thicket.device.apple.AppleLifecycle
import thicket.motion.{Motion, MotionProvider, Rate, Sensor, Vec3}
import thicket.renderer.apple.AppleApp
import thicket.signals.{Disposable, Owner}

/** The Motion section on AppKit and UIKit (#120).
  *
  *   - **macOS** has no `CMMotionManager`: the host passes `Motion.unavailable(`[[noDesktopMotion]]`)`, as GTK does, and
  *     the section says so.
  *   - **iOS** passes `IosMotion()`, and the host gives this its provider. The simulator has no motion hardware, so
  *     every sensor is `Unavailable`: the section must name each reason, and `Motion`'s lifecycle (`WhileInFront`) must
  *     never start one. That is counted through a provider wrapping the real one, under the real lifecycle, as the
  *     Android self-test counts its listeners. On a device, the sensors it has must deliver a reading.
  *
  * The provider is a parameter rather than imported here because this file is shared with macOS, whose link has no
  * `libthicketmotion.a`.
  */
object GalleryMotionChecks {

  type Check = (String, Boolean, String) => Unit

  /** Why the Mac has no motion sensors (docs/16 §16.9, decision 7), the GTK host's words. */
  val noDesktopMotion: String = "no motion sensors on the desktop yet"

  /** Re-checks `cond` every 16 ms until it holds or `timeoutMs` passes, then continues with the time it took. */
  private def eventually(timeoutMs: Long)(cond: => Boolean)(k: Long => Unit): Unit = {
    val start = System.nanoTime()
    val deadline = start + timeoutMs * 1000000L
    def loop(): Unit =
      if cond || System.nanoTime() > deadline then k((System.nanoTime() - start) / 1000000L)
      else AppleApp.postToUiAfter(16)(() => loop())
    loop()
  }

  /** `provider`: the iOS host's real one, with `active`, what Core Motion itself says it is delivering; `None` on macOS.
    */
  def run(
    check:    Check,
    texts:    () => List[String],
    provider: Option[(MotionProvider, Sensor => Boolean)]
  )(
    next: () => Unit
  ): Unit =
    (Gallery.currentMotion, provider) match {
      case (None, _) =>
        check("the Apple host provides Motion", false, "Gallery.currentMotion is None")
        next()
      case (Some(_), None) =>
        val expected = MotionDemo.sensorsLabel(Sensor.values.toList.map(_ -> Availability.Unavailable(noDesktopMotion)))
        val t = texts()
        check(
          "motion says the desktop has no sensors, and shows no reading",
          t.contains(expected) && t.contains(MotionDemo.noAcceleration) && t.contains(MotionDemo.noRotation) &&
            t.contains(MotionDemo.noAttitude),
          t.filter(l => l.startsWith("Sensors") || l.contains("reading")).toString
        )
        next()
      case (Some(demo), Some((real, active))) =>
        val all = Sensor.values.toList.map(s => s -> real.availability(s))
        val has = all.collect { case (s, Availability.Available) => s }
        val t = texts()
        val label = MotionDemo.sensorsLabel(all)
        println(s"[gallery] motion: $label")
        val missing = all.collect { case (s, Availability.Unavailable(r)) => s -> r }
        check(
          "the section names each sensor the device has, and each one it lacks with Core Motion's reason",
          t.contains(label) && missing.forall((_, r) => r.startsWith("This device has no ") && label.contains(r)) &&
            missing.size + has.size == Sensor.values.length,
          t.filter(_.startsWith("Sensors")).toString
        )
        // Counted through a wrapper, under the real lifecycle: the app is in front, so `WhileInFront` would start a
        // sensor now if `Motion` let it.
        var starts = 0
        var listening = 0
        val counted = new MotionProvider {
          def availability(sensor: Sensor): Availability = real.availability(sensor)
          def start(
            sensor:   Sensor,
            rate:     Rate,
            onSample: Vec3 => Unit
          ): Disposable = {
            starts += 1
            listening += 1
            val d = real.start(sensor, rate, onSample)
            var stopped = false
            () =>
              if !stopped then {
                stopped = true
                listening -= 1
                d.dispose()
              }
          }
        }
        val probe = Owner()
        val motion = Motion(counted, AppleLifecycle)
        val _ = motion.acceleration()(using probe)
        val _ = motion.rotationRate()(using probe)
        val _ = motion.attitude()(using probe)
        val front = AppleLifecycle.state.now
        check(
          "in the foreground, Motion starts exactly the sensors the device has: none on the simulator",
          front == AppState.Foreground && starts == has.size && listening == has.size,
          s"$front; $starts start(s) for ${has.size} available: ${has.mkString(", ")}"
        )
        val activeNow = Sensor.values.toList.filter(active)
        check(
          "Core Motion delivers exactly those sensors, for the section and the probe",
          activeNow.toSet == has.toSet,
          s"active: ${activeNow.mkString(", ")}"
        )
        probe.dispose()
        check("disposing the owner stops what it started", listening == 0, s"$listening listening")
        def readings = List(demo.acceleration.now, demo.rotationRate.now, demo.attitude.now)
        val none = List(MotionDemo.noAcceleration, MotionDemo.noRotation, MotionDemo.noAttitude)
        if has.isEmpty then {
          check("with no sensor, the section shows no reading", readings == none && none.forall(t.contains), "")
          next()
        } else
          eventually(5000)(has.forall(s => readings(s.ordinal) != none(s.ordinal))) { ms =>
            println(s"[gallery] measured: motion readings after $ms ms: ${readings.mkString("; ")}")
            check(
              "each sensor the device has delivers a reading",
              has.forall(s => readings(s.ordinal) != none(s.ordinal)),
              readings.mkString("; ")
            )
            next()
          }
    }

}
