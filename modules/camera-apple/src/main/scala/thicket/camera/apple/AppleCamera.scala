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

package thicket.camera.apple

import java.nio.file.{Files, Path, Paths}
import scala.scalanative.unsafe.*
import thicket.camera.Camera
import thicket.core.{GcState, UiThread}
import thicket.device.{Availability, Permission, PermissionStatus}
import thicket.device.apple.ApplePermissions
import thicket.pickers.PickedFiles

/** The system camera screen on iOS, `UIImagePickerController` with `sourceType = .camera` (`libthicketcamera.a`).
  *
  * The user frames and shoots there; the picker's `.originalImage` is written as a JPEG at quality 0.9, copied into
  * `cache` through [[PickedFiles]] on a thread of its own, and handed back as a `Path` on the UI thread. Cancelling
  * answers `None`. One request at a time: a second, while the picker is up, answers `None` without presenting.
  *
  * `availability`, in this order:
  *   - **`NSCameraUsageDescription`** must be in the app's `Info.plist`, or iOS terminates the app when the camera
  *     opens. Without it this throws `IllegalStateException` naming the key (through [[ApplePermissions]]), as Android
  *     does for an undeclared permission: on the simulator too, where no camera would ever open.
  *   - `Unavailable` when `UIImagePickerController.isSourceTypeAvailable(.camera)` is false: the device reports no
  *     camera the picker can use (some simulators report a camera but do not produce captures).
  *   - `NeedsPermission(Camera)` while `AVCaptureDevice`'s authorisation for video is not yet asked or denied
  *     (`ApplePermissions.request(Camera)` asks); `Unavailable` where it is restricted.
  *
  * On macOS use `Camera.unavailable("no camera screen on the desktop")`: decision 7, no desktop capture. Needs
  * `libthicketcamera.a` at the iOS host's final link (`ios-sim/libthicketcamera.a` in this jar) and
  * `thicket-device-apple`'s `libthicketdevice.a`.
  *
  * @param cache
  *   the app's cache directory, e.g. `AppleStorage(bundleId).dirs.cache`: photos are copied under `<cache>/picked`.
  */
final class AppleCamera(cache: Path) extends Camera {

  private val picked = PickedFiles(cache)

  def availability: Availability = {
    // Throws, naming NSCameraUsageDescription, when the app does not declare it.
    val status = ApplePermissions.status(Permission.Camera)
    if !AppleCamera.sourceAvailable then Availability.Unavailable(AppleCamera.noCamera)
    else
      status match {
        case PermissionStatus.Granted    => Availability.Available
        case PermissionStatus.Denied     => Availability.NeedsPermission(Permission.Camera)
        case PermissionStatus.Restricted => Availability.Unavailable(AppleCamera.restricted)
      }
  }

  def capturePhoto(onResult: Option[Path] => Unit): Unit =
    if availability != Availability.Available || AppleCamera.waiting.nonEmpty then UiThread.run(() => onResult(None))
    else {
      AppleCamera.waiting = Some(answer(onResult))
      if CameraShim.thk_cam_capture(AppleCamera.done, 0L) == 0 then {
        AppleCamera.waiting = None
        UiThread.run(() => onResult(None))
      }
    }

  /** What to do with the picker's answer: copy the JPEG it wrote, if any, into the cache and delete it. */
  private def answer(onResult: Option[Path] => Unit): String => Unit =
    written => {
      val t = Thread(() => {
        val photo =
          if written.isEmpty then None
          else {
            val tmp = Paths.get(written)
            val copied = scala.util
              .Try(picked.copy(s"photo-${System.currentTimeMillis()}.jpg", Files.newInputStream(tmp)))
              .toOption
              .filter(p => Files.size(p) > 0)
            val _ = scala.util.Try(Files.deleteIfExists(tmp))
            copied
          }
        UiThread.run(() => onResult(photo))
      })
      t.setName("thicket-camera")
      t.start()
    }

}

object AppleCamera {

  /** Why the camera is `Unavailable` when `isSourceTypeAvailable(.camera)` is false, as on the simulator. */
  val noCamera: String = "This device has no camera"

  /** Why it is `Unavailable` where a parental control or a device policy forbids the camera. */
  val restricted: String = "The camera is restricted on this device"

  /** `UIImagePickerController.isSourceTypeAvailable(.camera)`. */
  def sourceAvailable: Boolean = CameraShim.thk_cam_available() != 0

  /** The request the picker is up for: one at a time, process-wide, like the picker. */
  private var waiting: Option[String => Unit] = None

  // A CFuncPtr cannot close over state: the request is found through `waiting`.
  private val done: CameraShim.TextCb =
    CFuncPtr2.fromScalaFunction(
      (
        _:    Long,
        path: CString
      ) =>
        GcState.guarded {
          val written = fromCString(path)
          val k = waiting
          waiting = None
          k.foreach(_(written))
        }
    )

  /** For self-tests: whether this library's picker is up now. */
  object testing {

    def presented: Boolean = CameraShim.thk_cam_test_presented() != 0

  }

}
