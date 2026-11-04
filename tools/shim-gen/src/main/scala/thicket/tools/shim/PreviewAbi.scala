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

/** The `thk_prev_*` ABI: `thicket-camera-preview-apple`'s Swift library, a live camera preview over `AVCaptureSession`
  * in a `UIView` or an `NSView`, and a still capture from it (#148, `docs/17` §17.4).
  *
  * Its own surface, header, externs and Swift library, like [[CameraAbi]]: only an app that shows a preview links
  * AVFoundation's capture stack. One source for iOS and macOS (`#if os(iOS)`), built for both.
  *
  * **Push, on the main thread only**, as [[CameraAbi]]: Scala calls in on the main thread, and every answer is
  * delivered there. The session itself runs on a serial queue of the library's own, so starting and stopping it never
  * block the main thread. One session at a time in the process, as one preview at a time in the app (§17.5): a start
  * stops the session running, if any. The photo crosses as a path: the library writes the JPEG into the directory it is
  * given. The permission is `thicket-device-apple`'s (`Permission.Camera`). `thicket_preview.h` defines the two
  * callback types, `thk_prev_int_cb` and `thk_prev_text_cb`, and `sui_handle`, an `NSView *` / `UIView *`, as
  * `thicket_apple.h` does.
  *
  * Facing codes: 0 front, 1 back (`Facing.ordinal`). Fit codes: 0 contain, 1 cover, 2 fill (`ContentFit.ordinal`).
  */
object PreviewAbi {

  val devices: List[Fn] = List(
    Fn(
      "thk_prev_has_camera",
      I32,
      List(Abi.p("facing", I32)),
      doc = "1 if the device has a camera for `facing` (0 front, 1 back), 0 otherwise. iOS: the built-in\n" +
        "   wide-angle camera at that position. macOS: front is the first camera that is not on the back\n" +
        "   (built-in or external), back one the system reports on the back (docs/17 §17.4)."
    )
  )

  val view: List[Fn] = List(
    Fn(
      "thk_prev_view_new",
      Handle,
      List(Abi.p("fit", I32)),
      doc = "A new preview view, +1 retained, in no superview: a UIView or a layer-backed NSView whose layer\n" +
        "   is an AVCaptureVideoPreviewLayer with the gravity for `fit` (0 .resizeAspect, 1\n" +
        "   .resizeAspectFill, 2 .resize). It gives itself an aspect, height over width, from the frame\n" +
        "   as shown once a session runs in it (4:3 upright on iOS and 3:4 on macOS until then), so a\n" +
        "   layout that leaves its height open still gives it one. It shows nothing until\n" +
        "   thk_prev_start."
    )
  )

  val session: List[Fn] = List(
    Fn(
      "thk_prev_start",
      I32,
      List(Abi.p("view", Handle), Abi.p("facing", I32), Abi.p("done", IntCb), Abi.p("ctx", I64)),
      doc = "Stops the session running, if any, then starts one on the camera for `facing`, shown in\n" +
        "   `view` (one thk_prev_view_new made), with an AVCapturePhotoOutput for thk_prev_capture.\n" +
        "   Returns 1, and calls `done` once on the main thread when the session has started: with 1 if\n" +
        "   it is running, 0 if it could not be configured or did not start. Returns 0, calling nothing,\n" +
        "   when there is no such camera. The permission is the caller's to have asked first."
    ),
    Fn(
      "thk_prev_stop",
      I32,
      Nil,
      doc = "Detaches the running session from its view and stops it on the library's queue. Returns 1\n" +
        "   if there was one, 0 if not. A start or a capture under way is never answered after this:\n" +
        "   its `done` is dropped."
    ),
    Fn(
      "thk_prev_capture",
      I32,
      List(Abi.p("dir", Str), Abi.p("done", TextCb), Abi.p("ctx", I64)),
      doc = "Takes a still photo from the running session (AVCapturePhotoOutput, JPEG) and returns 1;\n" +
        "   `done` is called once on the main thread with the path of the JPEG written into `dir`\n" +
        "   (created if missing), upright, or \"\" if the capture or the write failed. Returns 0, calling\n" +
        "   nothing, when no session is running or a capture is already under way."
    )
  )

  val testing: List[Fn] = List(
    Fn(
      "thk_prev_test_running",
      I32,
      Nil,
      doc = "Sessions this library started that AVCaptureSession.isRunning says are running and that\n" +
        "   have not been stopped: 0 or 1."
    ),
    Fn(
      "thk_prev_test_frames",
      I64,
      Nil,
      doc = "Video frames delivered to the sessions' outputs since this library was loaded."
    ),
    Fn(
      "thk_prev_test_size",
      I32,
      List(Abi.p("width", OutF64), Abi.p("height", OutF64)),
      doc = "The running camera's active format, in pixels, into `width` and `height`; 1, or 0 with none."
    ),
    Fn(
      "thk_prev_test_facing",
      I32,
      Nil,
      doc = "The running camera's facing, 0 front or 1 back as thk_prev_has_camera chose it; -1 with none."
    ),
    Fn(
      "thk_prev_test_gravity",
      I32,
      List(Abi.p("view", Handle)),
      doc = "The preview layer's gravity in `view`, as a fit code (0 contain, 1 cover, 2 fill); -1 otherwise."
    ),
    Fn(
      "thk_prev_test_aspect",
      F64,
      List(Abi.p("view", Handle)),
      doc = "The aspect `view` gives itself, height over width."
    )
  )

  val groups: List[Group] = List(
    Group("devices", devices),
    Group("view", view),
    Group("session", session),
    Group("testing", testing, doc = "For self-tests: what AVFoundation itself reports.")
  )

  val all: List[Fn] = groups.flatMap(_.fns)

}
