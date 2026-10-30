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

import scala.util.Try
import thicket.device.{AppState, Permission, PermissionStatus}
import thicket.device.apple.{AppleDevice, AppleLifecycle, ApplePermissions}
import thicket.renderer.apple.AppleApp
import thicket.signals.{Owner, Signal}

/** The Device section on AppKit and UIKit (#114): the questions the GTK host's `deviceChecks` and the Android
  * `deviceSteps` ask, through the real notifications.
  *
  *   - **macOS** leaves the screen by itself: the app is hidden (Cmd-H) and unhidden, then its window minimised and
  *     restored, each through AppKit, and the lifecycle follows `NSApp`'s occlusion state.
  *   - **iOS** cannot, so, as on Android, the build script is the user: a `[gallery] DRIVE <action>` line asks
  *     `examples/ios-host/run-ios-app.sh` to open another app (`home`), bring the gallery back (`front`), or answer the
  *     system's alert (`deny`, through an XCUITest that taps it: `examples/ios-host/tapper`).
  *
  * The terminate hook is the run's last check, in [[terminate]].
  */
object GalleryDeviceChecks {

  type Check = (String, Boolean, String) => Unit

  private def ios: Boolean = AppleApp.renderer.platform == "uikit"

  private def drive(action: String): Unit = {
    println(s"[gallery] DRIVE $action")
    System.out.flush()
  }

  /** Re-checks `cond` every 16 ms until it holds or `timeoutMs` passes, then continues with the time it took. */
  private def eventually(timeoutMs: Long)(cond: => Boolean)(k: Long => Unit): Unit = {
    val start = System.nanoTime()
    val deadline = start + timeoutMs * 1000000L
    def loop(): Unit =
      if cond || System.nanoTime() > deadline then k((System.nanoTime() - start) / 1000000L)
      else AppleApp.postToUiAfter(16)(() => loop())
    loop()
  }

  private def lc: AppState = AppleLifecycle.state.now

  def run(
    check: Check,
    click: String => Boolean,
    texts: () => List[String]
  )(
    next: () => Unit
  ): Unit =
    Gallery.currentDevice match {
      case None =>
        check("the Apple host provides lifecycle and permissions", false, "Gallery.currentDevice is None")
        next()
      case Some(demo) =>
        def onScreen(t: String) = texts().contains(t)
        check("the Apple host provides lifecycle and permissions", true, "")
        val locked = AppleDevice.testing.screenLocked
        if locked then
          println(
            "[gallery] SKIP the Mac's screen is locked: every window is occluded, so the app is in the background " +
              "and hiding or minimising cannot change it. Run unlocked for the lifecycle checks."
          )
        val expected = if locked then AppState.Background else AppState.Foreground
        check(
          if locked then "the screen is locked, so the app is in the background, and the section says so"
          else "the app is in the foreground, and the section says so",
          lc == expected && onScreen(DeviceDemo.stateLabel(expected)) && onScreen(DeviceDemo.returnsLabel(0)),
          s"$lc; " + texts().filter(t => t.startsWith("The app") || t.startsWith("Back"))
        )
        val camera = Try(ApplePermissions.status(Permission.Camera))
        check(
          "a permission whose Info.plist usage string is missing throws, naming the key, instead of answering Denied",
          camera.failed.toOption.exists(e =>
            e.isInstanceOf[IllegalStateException] && e.getMessage.contains("NSCameraUsageDescription")
          ),
          camera.toString
        )
        val location = Try(ApplePermissions.status(Permission.Location))
        check(
          "Location throws UnsupportedOperationException until thicket-location-apple (#118)",
          location.failed.toOption.exists(_.isInstanceOf[UnsupportedOperationException]),
          location.toString
        )
        if ios then iosSteps(check, click, onScreen, demo)(next)
        else if locked then macNotifications(check, click, onScreen, demo)(next)
        else macSteps(check, click, onScreen, demo)(next)
    }

  /** macOS: hide and unhide, minimise and restore, through AppKit. The gallery runs as a plain executable, outside an
    * app bundle, so notifications cannot be asked for: the request throws, naming why, and the section shows it.
    */
  private def macSteps(
    check:    Check,
    click:    String => Boolean,
    onScreen: String => Boolean,
    demo:     DeviceDemo
  )(
    next: () => Unit
  ): Unit = {
    val hid = AppleDevice.testing.hide(true)
    eventually(5000)(lc == AppState.Background) { ms =>
      println(s"[gallery] measured: hidden -> Background in $ms ms")
      check(
        "hiding the app (Cmd-H) puts it in the background",
        hid && lc == AppState.Background && onScreen(DeviceDemo.stateLabel(AppState.Background)),
        s"hid $hid, $lc"
      )
      val _ = AppleDevice.testing.hide(false)
      eventually(5000)(lc == AppState.Foreground) { ms =>
        println(s"[gallery] measured: unhidden -> Foreground in $ms ms")
        check(
          "unhiding it brings it back to the foreground, counted once",
          lc == AppState.Foreground && onScreen(DeviceDemo.returnsLabel(1)),
          s"$lc, returns ${demo.returns.now}"
        )
        val minimised = AppleDevice.testing.miniaturize(true)
        eventually(5000)(lc == AppState.Background) { ms =>
          println(s"[gallery] measured: $minimised window(s) minimised -> Background in $ms ms")
          check(
            "minimising its window puts it in the background",
            minimised > 0 && lc == AppState.Background,
            s"$minimised minimised, $lc"
          )
          val _ = AppleDevice.testing.miniaturize(false)
          eventually(5000)(lc == AppState.Foreground) { ms =>
            println(s"[gallery] measured: restored -> Foreground in $ms ms")
            check(
              "restoring it brings it back to the foreground, counted again",
              lc == AppState.Foreground && onScreen(DeviceDemo.returnsLabel(2)),
              s"$lc, returns ${demo.returns.now}"
            )
            macNotifications(check, click, onScreen, demo)(next)
          }
        }
      }
    }
  }

  /** The macOS gallery is a plain executable, outside an app bundle, so `UNUserNotificationCenter` cannot be asked: the
    * request throws, naming why, and the section shows it.
    */
  private def macNotifications(
    check:    Check,
    click:    String => Boolean,
    onScreen: String => Boolean,
    demo:     DeviceDemo
  )(
    next: () => Unit
  ): Unit = {
    val clicked = click("Ask for notifications")
    val answer = demo.notifications.now
    check(
      "outside an app bundle, asking for notifications throws at once, naming why, and the section shows it",
      clicked && answer.contains("an app bundle") && onScreen(answer),
      s"clicked $clicked; $answer"
    )
    next()
  }

  /** iOS: the build script opens Settings over the gallery and launches it again, and answers the notifications alert
    * "Don't Allow" through XCUITest. The microphone it granted with `simctl privacy` before the launch.
    */
  private def iosSteps(
    check:    Check,
    click:    String => Boolean,
    onScreen: String => Boolean,
    demo:     DeviceDemo
  )(
    next: () => Unit
  ): Unit = {
    def answer = demo.notifications.now
    val notAsked = AppleDevice.testing.notYetAsked(Permission.Notifications)
    check(
      "notifications start Denied, not yet asked: the build script installed the app afresh",
      answer == "Notifications: Denied" && onScreen(answer) && notAsked,
      s"$answer; not yet asked: $notAsked (a refusal left by an earlier run would answer with no alert)"
    )
    var microphone = Option.empty[PermissionStatus]
    val before = Try(ApplePermissions.status(Permission.Microphone))
    ApplePermissions.request(Permission.Microphone)(s => microphone = Some(s))
    val synchronous = microphone
    eventually(5000)(microphone.isDefined) { _ =>
      check(
        "the microphone, granted by simctl privacy, is Granted, and asking answers Granted on a later turn, no dialog",
        before.toOption.contains(PermissionStatus.Granted) && synchronous.isEmpty &&
          microphone.contains(PermissionStatus.Granted),
        s"status $before; right after the request $synchronous; then $microphone"
      )
      drive("home")
      eventually(20000)(lc == AppState.Background) { ms =>
        println(s"[gallery] measured: Settings launched -> Background in $ms ms after the DRIVE line")
        check(
          "another app in front puts the app in the background",
          lc == AppState.Background && onScreen(DeviceDemo.stateLabel(AppState.Background)),
          lc.toString
        )
        drive("front")
        eventually(20000)(lc == AppState.Foreground) { ms =>
          println(s"[gallery] measured: relaunched -> Foreground in $ms ms after the DRIVE line")
          check(
            "launching it again brings the same process to the foreground, counted once",
            lc == AppState.Foreground && onScreen(DeviceDemo.returnsLabel(1)),
            s"$lc, returns ${demo.returns.now}"
          )
          // The alert is SpringBoard's, over the app: Android pauses the Activity under its dialog, and on iOS the scene
          // is not even deactivated (measured, #114). Either way the app stays in the foreground.
          val watch = Owner()
          var sawBackground = false
          Signal.effect { if AppleLifecycle.state() == AppState.Background then sawBackground = true }(using watch)
          val clicked = click("Ask for notifications")
          val asked = answer
          drive("deny")
          eventually(90000)(answer != DeviceDemo.asking) { ms =>
            println(s"[gallery] measured: \"Don't Allow\" answered in $ms ms after the DRIVE line")
            check(
              "\"Don't Allow\" in the system's alert answers Denied, on screen",
              clicked && asked == DeviceDemo.asking && answer == "Notifications: Denied" && onScreen(answer),
              s"clicked $clicked; while asking: $asked; then: $answer"
            )
            check("the app stayed in the foreground under the alert", !sawBackground, "")
            watch.dispose()
            val _ = click("Ask for notifications")
            eventually(5000)(answer != DeviceDemo.asking) { ms =>
              check(
                "asking again answers Denied at once, with no alert: iOS asks once, then only Settings can change it",
                answer == "Notifications: Denied",
                s"$answer after $ms ms"
              )
              next()
            }
          }
        }
      }
    }
  }

  /** The run's last check: the terminate hook runs, and prints the verdict. macOS terminates for real
    * (`NSApp.terminate`, the process ends after the hooks); iOS posts `UIApplication.willTerminateNotification`, since
    * an app may not end itself and `simctl terminate` kills it without one (measured, #114). A hook that does not run
    * leaves the macOS run without a verdict, which fails it, and fails the iOS run after 5 s.
    */
  def terminate(
    check:   Check,
    verdict: () => Unit
  ): Unit = {
    var ran = false
    val _ = AppleLifecycle.onTerminate { () =>
      ran = true
      check(
        if ios then "the terminate hook runs on UIApplication.willTerminateNotification"
        else "the terminate hook runs from NSApp.terminate",
        true,
        ""
      )
      verdict()
      System.out.flush()
    }
    println("[gallery] terminating: the verdict comes from the terminate hook")
    System.out.flush()
    AppleApp.postToUiAfter(5000) { () =>
      if !ran then {
        check("the terminate hook runs", false, "no will-terminate within 5 s")
        verdict()
        System.out.flush()
      }
    }
    AppleDevice.testing.terminate()
  }

}
