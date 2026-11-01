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

package thicket.camera

import java.nio.file.Path
import thicket.camera.testing.FakeCamera
import thicket.device.Availability
import zio.test.*

object CameraSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Nothing] =
    suite("Camera")(
      test("FakeCamera answers its photo, or None for a cancel, and counts the requests") {
        val photo = Path.of("/cache/photo.jpg")
        val c = FakeCamera(Some(photo))
        var got: Option[Option[Path]] = None
        c.capturePhoto(p => got = Some(p))
        var cancelled: Option[Option[Path]] = None
        FakeCamera(None).capturePhoto(p => cancelled = Some(p))
        assertTrue(got.contains(Some(photo)), cancelled.contains(None), c.taken == 1)
      },
      test("Camera.unavailable says why, and answers None at once") {
        val c = Camera.unavailable("no camera screen on the desktop")
        var got: Option[Option[Path]] = None
        c.capturePhoto(p => got = Some(p))
        assertTrue(c.availability == Availability.Unavailable("no camera screen on the desktop"), got.contains(None))
      }
    )

}
