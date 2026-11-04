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

package thicket.camera.preview.testing

import java.nio.file.Path
import thicket.camera.preview.{CameraPreview, Facing}
import thicket.device.Availability
import thicket.renderer.{ContentFit, PlatformView}
import thicket.signals.{Signal, Var}

/** A [[CameraPreview]] for tests: [[running]] is a `Var` the test sets, and a capture answers `photo` while it is true.
  * [[captures]] counts the requests.
  */
final class FakeCameraPreview(photo: Option[Path]) extends CameraPreview {

  var captures: Int = 0

  val runningNow: Var[Boolean] = Var(false)

  def availability: Availability = Availability.Available

  val facing: Var[Facing] = Var(Facing.Back)

  def running: Signal[Boolean] = runningNow

  def platformView(fit: ContentFit): PlatformView =
    new PlatformView {
      val description = s"Fake camera preview ($fit)"
    }

  def capture(onResult: Option[Path] => Unit): Unit = {
    captures += 1
    onResult(if runningNow.now then photo else None)
  }

}
