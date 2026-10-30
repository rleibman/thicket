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

package thicket.location.testing

import thicket.device.Availability
import thicket.location.{Accuracy, Fix, LocationProvider}
import thicket.signals.Disposable

import scala.collection.mutable

/** A [[LocationProvider]] a test drives, as `TestRenderer` is for widgets (`docs/16` §16.6): [[report]] sends a
  * position to every listener, [[availability]] can be changed, and [[listening]] says how many are started.
  *
  * Pair it with `FakeLifecycle` in `Location(provider, lifecycle)` to script a track through the app's comings and
  * goings.
  */
final class FakeLocationProvider(
  var availability: Availability = Availability.Available,
  var lastKnown:    Option[Fix] = None
) extends LocationProvider {

  private val listeners = mutable.LinkedHashSet.empty[Fix => Unit]

  /** How many times a listener was started, over the provider's life. */
  var starts: Int = 0

  def start(
    accuracy: Accuracy,
    onFix:    Fix => Unit
  ): Disposable = {
    // A fresh function per start, so one listener's stop never removes another's.
    val l: Fix => Unit = onFix(_)
    listeners += l
    starts += 1
    () => { val _ = listeners -= l }
  }

  def last(accuracy: Accuracy): Option[Fix] = lastKnown

  /** Listeners started and not stopped. */
  def listening: Int = listeners.size

  /** Sends `f` to every listener, as the platform would, and makes it the last known position. */
  def report(f: Fix): Unit = {
    lastKnown = Some(f)
    listeners.toList.foreach(_(f))
  }

}

object FakeLocationProvider {

  /** A position at `lat`, `lon`, with nothing else known, for tests that only follow the coordinates. */
  def fix(
    lat: Double,
    lon: Double
  ): Fix = Fix(lat, lon, None, None, 0L)

}
