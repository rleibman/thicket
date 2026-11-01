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

package thicket.haptics.apple

import scala.scalanative.unsafe.*
import thicket.device.Availability
import thicket.haptics.{Haptics, Impact}

/** Haptics on Apple platforms (#123).
  *
  *   - **iOS:** `UIImpactFeedbackGenerator` with the light, medium or heavy style, and `UISelectionFeedbackGenerator`.
  *     The phone's own setting (System Haptics) decides whether anything is felt; the simulator plays nothing.
  *   - **macOS:** `NSHapticFeedbackManager.defaultPerformer`, felt on a Force Touch trackpad under the user's finger
  *     and nowhere else. Strengths become patterns: light is Alignment, medium Generic, heavy LevelChange; a selection
  *     is Alignment.
  *
  * `availability` is `Available` where the classes are there to call, which is every supported OS version: neither
  * platform says whether there is hardware to feel it. Call from the main thread.
  */
final class AppleHaptics private () extends Haptics {

  def availability: Availability =
    if Feedback.thk_feedback_ready() != 0 then Availability.Available
    else Availability.Unavailable("no feedback generator on this system")

  def impact(strength: Impact): Unit = Feedback.thk_feedback_impact(strength.ordinal)

  def selection(): Unit = Feedback.thk_feedback_selection()

}

object AppleHaptics {

  def apply(): AppleHaptics = new AppleHaptics()

}

/** The C helper in `resources/scala-native/thicket_feedback.c`. Strength: 0 light, 1 medium, 2 heavy. */
@extern
private[apple] object Feedback {

  def thk_feedback_ready(): CInt = extern

  def thk_feedback_impact(strength: CInt): Unit = extern

  def thk_feedback_selection(): Unit = extern

}
