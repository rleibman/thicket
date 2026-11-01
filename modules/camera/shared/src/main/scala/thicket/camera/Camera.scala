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
import thicket.device.Availability

/** Taking a photo with the platform's own camera screen (`docs/16-device-services.md` §16.3, §16.10 item 5): the user
  * frames and shoots there, and the app gets the photo.
  *
  * The photo arrives as a JPEG copied into the app's cache, a `Path` the app may read, move or delete, as a picked file
  * does (owner's decision, 2026-10-30).
  */
trait Camera {

  def availability: Availability

  /** Opens the camera screen and calls `onResult` on the UI thread with the photo: `None` if the user cancelled, the
    * camera is not available, or another request is already up (one at a time).
    */
  def capturePhoto(onResult: Option[Path] => Unit): Unit

}

object Camera {

  /** A `Camera` that is never available, for `reason`: GTK and macOS, which have no system camera screen to take a
    * photo with (the portal's camera is a live stream, not a capture).
    */
  def unavailable(reason: String): Camera =
    new Camera {

      def availability: Availability = Availability.Unavailable(reason)

      def capturePhoto(onResult: Option[Path] => Unit): Unit = onResult(None)

    }

}
