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

package thicket.device

import thicket.signals.{Disposable, Signal}

/** Whether the app is in front of the user (`docs/16-device-services.md` §16.3). */
enum AppState {

  /** On screen: an Android Activity between `onStart` and `onStop`, a GTK window shown and not minimised. */
  case Foreground

  /** Not on screen. Sensors stop here, and an app should save what it would hate to lose: a background app may be
    * killed with no further warning, on Android and iOS alike.
    */
  case Background

}

/** The app's comings and goings, as the host is told them. Every change happens on the UI thread.
  *
  * Only the host hears these — the Activity, the GTK window, the Apple scene — which is why a service that must stop in
  * the background asks this rather than the platform.
  */
trait AppLifecycle {

  /** Foreground or Background, now and as it changes. Read inside an effect to follow it. */
  def state: Signal[AppState]

  /** Runs `f` on the UI thread when the app is closing for good: the last Activity finishing (not one rotating), the
    * GTK application shutting down. Not a place to save state: Android and iOS kill a background app without calling
    * it, so save on [[AppState.Background]]. The returned `Disposable` removes `f`.
    */
  def onTerminate(f: () => Unit): Disposable

}

/** What a device service may need the user's consent for. Each platform maps it to its own: on Android, `Location` is
  * `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`, `Notifications` is `POST_NOTIFICATIONS` (Android 13+).
  */
enum Permission {

  case Location, Camera, Microphone, Notifications

}

/** The user's answer. */
enum PermissionStatus {

  /** The app may use it. */
  case Granted

  /** Not granted: not yet asked, or refused. Asking again shows the dialog, unless the user refused for good, in which
    * case the answer is `Denied` at once and only the system settings can change it.
    */
  case Denied

  /** Not the user's to give: a parental control or a device policy forbids it. Asking does nothing. */
  case Restricted

}

/** Asking for a [[Permission]].
  *
  * Call from the UI thread; the answer arrives there, later — a dialog is up meanwhile. One request is shown at a time:
  * a second waits for the first to be answered, as every platform needs.
  *
  * A permission the app never declared is a mistake in the app, not a refusal: on Android, asking for one is refused at
  * once with no dialog, which reads like a user's "no". So [[status]] and [[request]] throw `IllegalStateException`
  * naming what to declare, rather than answer `Denied`.
  */
trait Permissions {

  /** The answer as it stands, without asking. */
  def status(p: Permission): PermissionStatus

  /** Asks the user, if the answer is not already `Granted`, and calls `onResult` on the UI thread with it. */
  def request(p: Permission)(onResult: PermissionStatus => Unit): Unit

}

/** Whether a device service can be used here and now (`docs/16` §16.6: capabilities are values). "No GPS on a desktop"
  * is a branch in the app, not an exception.
  */
enum Availability {

  /** It can be used. */
  case Available

  /** It can, once the user grants the permission: ask with [[Permissions.request]], then use it. */
  case NeedsPermission(permission: Permission)

  /** It cannot, for `reason`: the platform has no such service, or the user turned it off in the system settings. */
  case Unavailable(reason: String)

}

/** What a host gives an app: its lifecycle and its permissions. */
final case class Device(
  lifecycle:   AppLifecycle,
  permissions: Permissions
)
