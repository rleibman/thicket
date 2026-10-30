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

/** The `thk_dev_*` ABI: `thicket-device-apple`'s Swift library, the app's lifecycle and permissions on macOS and iOS
  * (#114, docs/16 §16.3).
  *
  * Its own surface, header, externs and Swift library, like [[HttpAbi]]: an app that does not depend on
  * `thicket-device-apple` links none of it, nor the UserNotifications and AVFoundation frameworks it names.
  *
  * **Push, on the main thread only.** Unlike HTTP, this surface calls into Scala: the lifecycle observers are registered
  * on the main queue, and every permission answer — which the platform delivers on a queue of its own — is moved to the
  * main queue before the callback is called. The main thread is the only non-Scala thread allowed to enter Scala (S1),
  * and the Scala side wraps each callback in `GcState.guarded`, since the run loop holds it Unmanaged (S3).
  *
  * Permissions are numbered as `thicket.device.Permission`'s ordinals: 0 Location, 1 Camera, 2 Microphone, 3
  * Notifications. A status is 0 granted, 1 denied, 2 restricted, 3 not yet asked, -1 not supported by this build.
  * `thicket_device.h` defines the two callback types, `thk_dev_void_cb` and `thk_dev_int_cb`.
  */
object DeviceAbi {

  private val permission = Abi.p("permission", I32)

  val lifecycle: List[Fn] = List(
    Fn(
      "thk_dev_lifecycle_start",
      I32,
      List(Abi.p("changed", IntCb), Abi.p("ctx", I64)),
      doc = "Starts following the app on and off screen and returns where it is now: 1 on screen, 0 not.\n" +
        "   `changed` is called on the main thread with 1 or 0 at each change after that. iOS: a scene\n" +
        "   entering the foreground, and the last one entering the background. macOS: NSApp's occlusion\n" +
        "   state, so a hidden app, a minimised window or a covered one is off screen. Once per process."
    ),
    Fn(
      "thk_dev_on_terminate",
      Void,
      List(Abi.p("terminating", VoidCb), Abi.p("ctx", I64)),
      doc = "Calls `terminating` on the main thread on the platform's will-terminate notification.\n" +
        "   Once per process. iOS rarely sends it; macOS sends it from NSApp.terminate."
    )
  )

  val permissions: List[Fn] = List(
    Fn(
      "thk_dev_permission_missing",
      Str,
      List(permission),
      doc = "What the app must declare before it may ask, or \"\" when nothing is missing: an Info.plist\n" +
        "   usage key (iOS terminates an app that asks without one), or an app bundle (notifications).\n" +
        "   A string that lives as long as the process."
    ),
    Fn(
      "thk_dev_permission_status",
      I32,
      List(permission),
      doc = "The answer as it stands, without asking. Waits for UserNotifications' answer, a few ms,\n" +
        "   so Scala marks the call blocking.",
      blocking = true
    ),
    Fn(
      "thk_dev_permission_request",
      Void,
      List(permission, Abi.p("answered", IntCb), Abi.p("ctx", I64)),
      doc = "Asks the user (the system shows its dialog only while the answer is not yet given) and calls\n" +
        "   `answered` with the resulting status, always later, on the main thread."
    )
  )

  val testing: List[Fn] = List(
    Fn(
      "thk_dev_test_hide",
      I32,
      List(Abi.p("hidden", I32)),
      doc = "macOS: hides (1) or unhides (0) the app, as Cmd-H does. Returns 1, or 0 on iOS, where an\n" +
        "   app cannot leave the screen by itself."
    ),
    Fn(
      "thk_dev_test_miniaturize",
      I32,
      List(Abi.p("miniaturized", I32)),
      doc = "macOS: minimises (1) or restores (0) the app's visible windows. Returns how many, 0 on iOS."
    ),
    Fn(
      "thk_dev_test_screen_locked",
      I32,
      Nil,
      doc = "macOS: 1 while the login session's screen is locked, which occludes every window, so the\n" +
        "   app is off screen whatever it does. 0 otherwise, and always on iOS."
    ),
    Fn(
      "thk_dev_test_terminate",
      Void,
      Nil,
      doc = "macOS: NSApp.terminate, the real path; the process exits after the will-terminate\n" +
        "   observers run. iOS: posts UIApplication.willTerminateNotification, since an app may not\n" +
        "   end itself and simctl's terminate kills it without one."
    )
  )

  val groups: List[Group] = List(
    Group("lifecycle", lifecycle),
    Group("permissions", permissions),
    Group("testing", testing, doc = "For self-tests: what a user would do.")
  )

  val all: List[Fn] = groups.flatMap(_.fns)

}
