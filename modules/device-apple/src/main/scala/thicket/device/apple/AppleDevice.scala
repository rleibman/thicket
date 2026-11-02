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

package thicket.device.apple

import scala.scalanative.unsafe.*
import thicket.core.GcState
import thicket.device.*
import thicket.signals.{Disposable, Signal, Var}

/** The app's lifecycle on macOS and iOS, from the platform's own notifications, which `libthicketdevice.a` observes on
  * the main queue.
  *
  *   - **iOS:** Foreground from a scene's `willEnterForeground` to the last scene's `didEnterBackground`. Not
  *     activation: a permission alert over the app does not even deactivate the scene (measured, #114), and a headless
  *     simulator never activated one at all, so "active" would mean Background for an app that is on screen.
  *   - **macOS:** Foreground while any part of any of the app's windows is visible (`NSApp.occlusionState`): hidden
  *     (Cmd-H), minimised, ordered out or covered by other windows is Background. Not "active": an unfocused window is
  *     still on screen, as on GTK.
  *
  * Terminate is the platform's will-terminate notification: macOS sends it from `NSApp.terminate` (Quit, the last
  * window closed); iOS sends it rarely, and never to a suspended app, so save on [[AppState.Background]].
  *
  * Process-wide. First use on the main thread, after the host is up (`AppleApp.run` on macOS, the scene connected on
  * iOS): that is when where the app stands is read.
  */
object AppleLifecycle extends AppLifecycle {

  private val hooks = Hooks()

  // A CFuncPtr cannot close over state, so the two callbacks reach these through the object.
  private val changed: DeviceShim.IntCb =
    CFuncPtr2.fromScalaFunction(
      (
        _:        Long,
        onScreen: CInt
      ) => GcState.guarded(current.set(toState(onScreen)))
    )

  private val terminating: DeviceShim.VoidCb =
    CFuncPtr1.fromScalaFunction((_: Long) => GcState.guarded(hooks.run()))

  private def toState(onScreen: Int): AppState = if onScreen != 0 then AppState.Foreground else AppState.Background

  lazy private val current: Var[AppState] = Var(toState(DeviceShim.thk_dev_lifecycle_start(changed, 0L)))

  def state: Signal[AppState] = current

  def onTerminate(f: () => Unit): Disposable = hooks.add(f)

  locally {
    val _ = current
    DeviceShim.thk_dev_on_terminate(terminating, 0L)
  }

}

/** Permissions on macOS and iOS, each through its own service's API: `CLLocationManager` for location (when in use,
  * `requestWhenInUseAuthorization`; #118), `AVCaptureDevice` for the camera and the microphone,
  * `UNUserNotificationCenter` for notifications.
  *
  * Apple answers `Restricted` where a parental control or a device policy forbids it, and asks only once: after "Don't
  * Allow", a request answers `Denied` at once with no dialog, and only the Settings app can change it. Not yet asked
  * reads as `Denied`.
  *
  * What the app must declare is checked first, and a missing declaration throws `IllegalStateException` naming it,
  * rather than answering `Denied`: iOS **terminates** an app that asks for the camera or the microphone with no
  * `NSCameraUsageDescription` / `NSMicrophoneUsageDescription` in its `Info.plist`, ignores a location request with no
  * `NSLocationWhenInUseUsageDescription` (macOS: `NSLocationUsageDescription`) and terminates the app when it then uses
  * location, and `UNUserNotificationCenter` raises for a process that is not in an app bundle (an executable run on its
  * own, as the macOS gallery is).
  */
object ApplePermissions extends Permissions {

  private def checked(p: Permission): Unit = {
    val missing = fromCString(DeviceShim.thk_dev_permission_missing(p.ordinal))
    if missing.nonEmpty then
      throw IllegalStateException(
        s"$p needs $missing" + (if missing.startsWith("NS") then " in the app's Info.plist" else "")
      )
  }

  private def status(
    code: Int,
    p:    Permission
  ): PermissionStatus =
    code match {
      case 0     => PermissionStatus.Granted
      case 2     => PermissionStatus.Restricted
      case 1 | 3 => PermissionStatus.Denied
      case _     =>
        throw UnsupportedOperationException(s"$p is not supported by thicket-device-apple")
    }

  def status(p: Permission): PermissionStatus = {
    checked(p)
    status(DeviceShim.thk_dev_permission_status(p.ordinal), p)
  }

  def request(p: Permission)(onResult: PermissionStatus => Unit): Unit = {
    checked(p)
    // Throws now, not from inside the callback, for a permission this library cannot ask for.
    val _ = status(p)
    queue.request(p, onResult)
  }

  private val queue: PermissionQueue =
    PermissionQueue(p => DeviceShim.thk_dev_permission_request(p.ordinal, answered, 0L))

  private val answered: DeviceShim.IntCb =
    CFuncPtr2.fromScalaFunction(
      (
        _:    Long,
        code: CInt
      ) =>
        GcState.guarded {
          val p = queue.pending
          queue.answer(
            scala.util.Try(status(code, p.getOrElse(Permission.Notifications))).getOrElse(PermissionStatus.Denied)
          )
        }
    )

}

/** Both, for an app: `AppleDevice()` inside `AppleApp.run`. Needs `libthicketdevice.a` at the final link: the macOS
  * build adds it with `ThicketNativeFlags.appleDevice`, an iOS host's link takes `ios-sim/libthicketdevice.a`.
  */
object AppleDevice {

  def apply(): Device = Device(AppleLifecycle, ApplePermissions)

  /** For self-tests, what a user would do; see `thicket_device.h`. macOS hides the app or minimises its windows and
    * returns whether it could; iOS cannot leave the screen by itself and returns false. `terminate` on macOS is
    * `NSApp.terminate`, which ends the process after the hooks run; on iOS it posts the will-terminate notification.
    */
  object testing {

    def hide(hidden: Boolean): Boolean = DeviceShim.thk_dev_test_hide(if hidden then 1 else 0) != 0

    def miniaturize(miniaturized: Boolean): Int = DeviceShim.thk_dev_test_miniaturize(if miniaturized then 1 else 0)

    def terminate(): Unit = DeviceShim.thk_dev_test_terminate()

    /** macOS: whether the screen is locked, which occludes every window: the app is in the background then, whatever it
      * does. Always false on iOS.
      */
    def screenLocked: Boolean = DeviceShim.thk_dev_test_screen_locked() != 0

    /** Whether the user has not yet been asked for `p`, which [[ApplePermissions.status]] reports as `Denied` like a
      * refusal. A self-test needs the difference: a refusal left over from an earlier run answers at once, with no
      * alert, and would pass a check of "Don't Allow" without one.
      */
    def notYetAsked(p: Permission): Boolean = DeviceShim.thk_dev_permission_status(p.ordinal) == 3

    /** How many location listeners `libthicketdevice.a` has (`thicket-location-apple`, #118): `CLLocationManager` is
      * updating while this is not 0.
      */
    def locationListeners: Int = DeviceShim.thk_dev_test_location_listeners()

  }

}
