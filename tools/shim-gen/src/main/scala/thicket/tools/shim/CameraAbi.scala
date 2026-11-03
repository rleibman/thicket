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

package thicket.tools.shim

import thicket.tools.shim.Abi.{Fn, Group}
import thicket.tools.shim.Abi.CType.*

/** The `thk_cam_*` ABI: `thicket-camera-apple`'s Swift library, taking a photo with `UIImagePickerController` (#129,
  * docs/16 §16.10 item 5).
  *
  * Its own surface, header, externs and Swift library, like [[MotionAbi]]: an app that does not depend on
  * `thicket-camera-apple` links none of it. iOS only: `UIImagePickerController` is UIKit, and macOS uses
  * `Camera.unavailable` (decision 7: no desktop capture).
  *
  * **Push, on the main thread only**, as [[DeviceAbi]]: the picker's delegate answers on the main thread, and the Scala
  * side wraps the answer in `GcState.guarded`. The answer is a path: the Swift side writes the photo as a JPEG to a
  * file of its own in `NSTemporaryDirectory()`, and Scala copies it into the app's cache through `PickedFiles` and
  * deletes it. No bytes cross the boundary. The permission is not here: `AVCaptureDevice`'s authorisation is
  * `thicket-device-apple`'s (`thk_dev_permission_*`, `Permission.Camera`). `thicket_camera.h` defines the one callback
  * type, `thk_cam_text_cb`.
  */
object CameraAbi {

  val capture: List[Fn] = List(
    Fn(
      "thk_cam_available",
      I32,
      Nil,
      doc = "1 if UIImagePickerController.isSourceTypeAvailable(.camera): the device has a camera\n" +
        "   the picker can use. 0 otherwise (including on devices/simulators without camera support)."
    ),
    Fn(
      "thk_cam_capture",
      I32,
      List(Abi.p("done", TextCb), Abi.p("ctx", I64)),
      doc = "Presents UIImagePickerController with sourceType .camera over the key window's topmost\n" +
        "   controller and returns 1; returns 0, presenting nothing and never calling `done`, when\n" +
        "   the camera is not available, a picker is already up, or there is no window to present\n" +
        "   from. `done` is called once, on the main thread, after the picker is dismissed: with the\n" +
        "   path of a JPEG (quality 0.9) of the original image in NSTemporaryDirectory(), which the\n" +
        "   caller owns and deletes, or with \"\" if the user cancelled or the photo could not be written."
    )
  )

  val testing: List[Fn] = List(
    Fn(
      "thk_cam_test_presented",
      I32,
      Nil,
      doc = "1 while this library's picker is up (presented and not yet answered), 0 otherwise."
    )
  )

  val groups: List[Group] = List(
    Group("capture", capture),
    Group("testing", testing, doc = "For self-tests: what UIKit itself shows.")
  )

  val all: List[Fn] = groups.flatMap(_.fns)

}
