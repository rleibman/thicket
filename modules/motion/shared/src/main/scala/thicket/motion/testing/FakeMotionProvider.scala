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

package thicket.motion.testing

import thicket.device.Availability
import thicket.motion.{MotionProvider, Rate, Sensor, Vec3}
import thicket.signals.Disposable

import scala.collection.mutable

/** A [[MotionProvider]] a test drives, as `TestRenderer` is for widgets (`docs/16` §16.6): [[report]] sends a sample to
  * `sensor`'s listeners, [[missing]] takes sensors away, and [[listening]] counts what runs.
  */
final class FakeMotionProvider(var missing: Set[Sensor] = Set.empty) extends MotionProvider {

  private val listeners = mutable.ArrayBuffer.empty[(Sensor, Rate, Vec3 => Unit)]

  def availability(sensor: Sensor): Availability =
    if missing(sensor) then Availability.Unavailable(s"no $sensor sensor") else Availability.Available

  def start(
    sensor:   Sensor,
    rate:     Rate,
    onSample: Vec3 => Unit
  ): Disposable = {
    val entry = (sensor, rate, onSample)
    listeners += entry
    () => { val _ = listeners -= entry }
  }

  /** Listeners running for `sensor`. */
  def listening(sensor: Sensor): Int = listeners.count(_._1 == sensor)

  /** The rates `sensor`'s listeners asked for. */
  def rates(sensor: Sensor): List[Rate] = listeners.filter(_._1 == sensor).map(_._2).toList

  /** Sends `v` to every listener of `sensor`, as the hardware would. */
  def report(
    sensor: Sensor,
    v:      Vec3
  ): Unit = listeners.filter(_._1 == sensor).toList.foreach(_._3(v))

}
