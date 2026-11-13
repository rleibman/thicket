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

import java.nio.file.Path
import thicket.camera.preview.{CameraPreview, Facing}
import thicket.device.Availability
import thicket.signals.{Signal, Var}

/** The Camera preview section's state (`docs/17`): the host's preview, what it says about itself, and the last photo
  * taken from it.
  */
final class CameraPreviewDemo(val preview: CameraPreview) {

  /** Bumped when the facing changes, so the status line reads the availability again. */
  private val flips = Var(0)

  /** Running or stopped, and the availability, on one line. */
  val status: Signal[String] =
    preview.running
      .zip(flips).map(
        (
          running,
          _
        ) => CameraPreviewDemo.statusLabel(running, preview.availability)
      )

  /** The last capture's answer: `None` before the first. */
  val lastPhoto: Var[Option[Option[Path]]] = Var(None)

  def take(): Unit = preview.capture(p => lastPhoto.set(Some(p)))

  def flip(): Unit = {
    preview.facing.update(f => if f == Facing.Back then Facing.Front else Facing.Back)
    flips.update(_ + 1)
  }

  /** Whether the preview's widget is in the layout: hiding it unmounts it, which must stop the camera (#148). */
  val shown: Var[Boolean] = Var(true)

  def toggleShown(): Unit = shown.update(!_)

  /** Reads the availability again, after a permission was answered. */
  def refresh(): Unit = flips.update(_ + 1)

}

object CameraPreviewDemo {

  def statusLabel(
    running:      Boolean,
    availability: Availability
  ): String = {
    val a = availability match {
      case Availability.Available        => "available"
      case Availability.Unavailable(why) => s"unavailable ($why)"
      case other                         => other.toString
    }
    s"Preview: ${if running then "running" else "stopped"}; $a"
  }

  def photoLabel(photo: Option[Option[Path]]): String =
    photo match {
      case None          => "No photo from the preview yet"
      case Some(None)    => "The preview gave no photo"
      case Some(Some(p)) => s"Photo from the preview: ${p.getFileName}"
    }

}
