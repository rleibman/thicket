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

package thicket.haptics

import thicket.device.Availability

/** How strong a tap. */
enum Impact {

  case Light, Medium, Heavy

}

/** A short tap the user feels, confirming a touch (`docs/16-device-services.md` §16.3).
  *
  * Fire and forget, from the UI thread. Where there is nothing to feel — a desktop, a phone with touch feedback turned
  * off in its settings — the calls do nothing: haptics confirm what the screen already shows, so none is ever needed.
  */
trait Haptics {

  /** Whether the platform can play haptics at all. */
  def availability: Availability

  /** A tap of the given strength, as for a button press. */
  def impact(strength: Impact): Unit

  /** The lighter tick of a value changing under the finger, as a picker wheel turns. */
  def selection(): Unit

}

object Haptics {

  /** Haptics that do nothing, for `reason`: GTK, which has no haptic hardware to drive. */
  def none(reason: String): Haptics =
    new Haptics {

      def availability: Availability = Availability.Unavailable(reason)

      def impact(strength: Impact): Unit = ()

      def selection(): Unit = ()

    }

}
