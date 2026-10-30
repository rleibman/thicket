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

package thicket.device.android

import _root_.android.Manifest
import _root_.android.content.Context
import _root_.android.content.pm.PackageManager
import _root_.android.os.{Build, Handler, Looper}
import thicket.device.*
import thicket.renderer.android.ThicketActivity
import thicket.signals.{Disposable, Signal, Var}

/** The app's lifecycle on Android, from every [[ThicketActivity]]'s `onStart` and `onStop`.
  *
  * Foreground while an Activity is started. A rotation stops one Activity and starts its replacement; the stop says
  * `isChangingConfigurations`, so the app stays in the foreground through it. Terminate is the last Activity finishing
  * — Back at the root, `finish()` — not a process kill, which Android does without telling anyone.
  *
  * Process-wide, like the Activities it listens to. First use on the main thread.
  */
object AndroidLifecycle extends AppLifecycle {

  private val current = Var(if ThicketActivity.anyStarted then AppState.Foreground else AppState.Background)
  private val hooks = Hooks()

  val state: Signal[AppState] = current

  def onTerminate(f: () => Unit): Disposable = hooks.add(f)

  locally {
    val _ = ThicketActivity.listen(new ThicketActivity.Listener {

      override def started(activity: ThicketActivity): Unit = current.set(AppState.Foreground)

      override def stopped(activity: ThicketActivity): Unit =
        if !activity.isChangingConfigurations && !ThicketActivity.anyStarted then current.set(AppState.Background)

      override def destroyed(activity: ThicketActivity): Unit =
        if activity.isFinishing && !activity.isChangingConfigurations && ThicketActivity.current.isEmpty then
          hooks.run()

    })
  }

}

/** Android's runtime permissions: the system dialog, asked from the current [[ThicketActivity]], whose
  * `onRequestPermissionsResult` brings the answer back.
  *
  * Each permission must be in the app's `AndroidManifest.xml` (`<uses-permission android:name="…"/>`); one that is not
  * throws `IllegalStateException` naming it, since Android would refuse it silently. `Notifications` before Android 13
  * needs nothing and is always `Granted`. Android never answers `Restricted`.
  *
  * Pass the Activity or any context; the application context is kept.
  */
final class AndroidPermissions(context: Context) extends Permissions {

  import AndroidPermissions.*

  private val app: Context = Option(context.getApplicationContext).getOrElse(context)

  def status(p: Permission): PermissionStatus =
    systemNames(p) match {
      case Nil   => PermissionStatus.Granted
      case names =>
        if declared(app, p, names).exists(app.checkSelfPermission(_) == PackageManager.PERMISSION_GRANTED) then
          PermissionStatus.Granted
        else PermissionStatus.Denied
    }

  def request(p: Permission)(onResult: PermissionStatus => Unit): Unit =
    if status(p) == PermissionStatus.Granted then main.post(() => onResult(PermissionStatus.Granted))
    else if ThicketActivity.current.isEmpty then
      throw IllegalStateException("asking for a permission needs a ThicketActivity on screen to ask from")
    else queue.request(p, onResult)

}

object AndroidPermissions {

  /** The request code Thicket's dialogs use. One suffices: one dialog is up at a time. */
  val RequestCode: Int = 0x7468

  private val main = Handler(Looper.getMainLooper)

  /** Android's names for `p` on this Android version; none when it needs no permission here. */
  def systemNames(p: Permission): List[String] =
    p match {
      case Permission.Location =>
        List(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
      case Permission.Camera        => List(Manifest.permission.CAMERA)
      case Permission.Microphone    => List(Manifest.permission.RECORD_AUDIO)
      case Permission.Notifications =>
        if Build.VERSION.SDK_INT >= 33 then List(Manifest.permission.POST_NOTIFICATIONS) else Nil
    }

  /** Those of `names` the app's manifest declares; throws if none is. */
  private def declared(
    app:   Context,
    p:     Permission,
    names: List[String]
  ): List[String] = {
    val requested =
      Option(
        app.getPackageManager.getPackageInfo(app.getPackageName, PackageManager.GET_PERMISSIONS).requestedPermissions
      )
        .fold(Set.empty[String])(_.toSet)
    names.filter(requested) match {
      case Nil =>
        throw IllegalStateException(
          s"$p needs <uses-permission android:name=\"${names.head}\"/> in the app's AndroidManifest.xml: " +
            "without it Android refuses at once, with no dialog"
        )
      case some => some
    }
  }

  private val queue: PermissionQueue = PermissionQueue { p =>
    ThicketActivity.current match {
      case Some(activity) => activity.requestPermissions(declared(activity, p, systemNames(p)).toArray, RequestCode)
      // The Activity went between the request and its turn: nobody to ask.
      case None => main.post(() => queue.answer(PermissionStatus.Denied))
    }
  }

  locally {
    val _ = ThicketActivity.listen(new ThicketActivity.Listener {

      override def permissionsResult(
        activity:    ThicketActivity,
        requestCode: Int,
        permissions: Array[String],
        results:     Array[Int]
      ): Boolean =
        requestCode == RequestCode && {
          // Any of them will do: coarse location is still a location. Empty results mean the request was cancelled, as
          // when the Activity is recreated under the dialog.
          queue.answer(
            if results.contains(PackageManager.PERMISSION_GRANTED) then PermissionStatus.Granted
            else PermissionStatus.Denied
          )
          true
        }

    })
  }

}

/** Both, for an app: `AndroidDevice(this)` in `createApp`. */
object AndroidDevice {

  def apply(context: Context): Device = Device(AndroidLifecycle, AndroidPermissions(context))

}
