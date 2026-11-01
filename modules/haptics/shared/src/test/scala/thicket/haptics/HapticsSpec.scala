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
import thicket.haptics.testing.RecordingHaptics
import zio.test.*

object HapticsSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Nothing] =
    suite("Haptics")(
      test("RecordingHaptics records each tap in order") {
        val h = RecordingHaptics()
        h.impact(Impact.Heavy)
        h.selection()
        h.impact(Impact.Light)
        assertTrue(h.played.toList == List(Some(Impact.Heavy), None, Some(Impact.Light)))
      },
      test("Haptics.none says why, and its taps do nothing") {
        val h = Haptics.none("no haptics on the desktop")
        h.impact(Impact.Medium)
        h.selection()
        assertTrue(h.availability == Availability.Unavailable("no haptics on the desktop"))
      }
    )

}
