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

package thicket.haptics.android

import _root_.android.view.HapticFeedbackConstants
import thicket.device.Availability
import thicket.haptics.{Haptics, Impact}
import thicket.renderer.android.ThicketActivity

/** Haptics through `View.performHapticFeedback` on the current [[ThicketActivity]]'s window: the system's own feedback,
  * which follows the user's touch-feedback setting and needs no `VIBRATE` permission.
  *
  *   - `Light` is `CLOCK_TICK`, `Medium` `VIRTUAL_KEY`, `Heavy` `LONG_PRESS`;
  *   - `selection` is `KEYBOARD_TAP`.
  *
  * With no Activity on screen, nothing is played.
  */
object AndroidHaptics extends Haptics {

  def availability: Availability = Availability.Available

  def impact(strength: Impact): Unit = {
    val _ = perform(AndroidHaptics.constant(strength))
  }

  def selection(): Unit = {
    val _ = perform(HapticFeedbackConstants.KEYBOARD_TAP)
  }

  /** Plays `constant`; whether the system did, as `performHapticFeedback` answers. False with touch feedback turned
    * off, or with no Activity to play it from.
    */
  def perform(constant: Int): Boolean =
    ThicketActivity.current.exists(_.getWindow.getDecorView.performHapticFeedback(constant))

  def constant(strength: Impact): Int =
    strength match {
      case Impact.Light  => HapticFeedbackConstants.CLOCK_TICK
      case Impact.Medium => HapticFeedbackConstants.VIRTUAL_KEY
      case Impact.Heavy  => HapticFeedbackConstants.LONG_PRESS
    }

}
