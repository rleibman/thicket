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

package thicket.camera.preview

import java.nio.file.Path
import thicket.camera.preview.testing.FakeCameraPreview
import thicket.device.Availability
import zio.test.*

object CameraPreviewSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Nothing] =
    suite("CameraPreview")(
      test("unavailable says why, never runs, answers None, and its widget shows the reason") {
        val p = CameraPreview.unavailable("no camera preview on the desktop yet")
        var got: Option[Option[Path]] = None
        p.capture(r => got = Some(r))
        assertTrue(
          p.availability == Availability.Unavailable("no camera preview on the desktop yet"),
          !p.running.now,
          got.contains(None),
          p.platformView(thicket.renderer.ContentFit.Cover).description.contains("no camera preview on the desktop yet")
        )
      },
      test("a capture answers a photo only while the preview runs") {
        val photo = Path.of("/cache/preview.jpg")
        val p = FakeCameraPreview(Some(photo))
        var before: Option[Option[Path]] = None
        p.capture(r => before = Some(r))
        p.runningNow.set(true)
        var after: Option[Option[Path]] = None
        p.capture(r => after = Some(r))
        assertTrue(before.contains(None), after.contains(Some(photo)), p.captures == 2)
      }
    )

}
