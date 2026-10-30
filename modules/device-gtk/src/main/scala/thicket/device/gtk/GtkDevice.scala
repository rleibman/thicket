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

package thicket.device.gtk

import thicket.device.*
import thicket.renderer.gtk.GtkApp
import thicket.signals.{Disposable, Signal, Var}

/** The app's lifecycle on GTK: Foreground while `GtkApp`'s window is shown — mapped, neither minimised nor suspended
  * (GTK 4.12+: on another workspace, or covered) — and terminate when the application shuts down.
  *
  * Not focus: an unfocused window is still on screen, and a map beside a document should keep following you. First use
  * on the UI thread.
  */
object GtkLifecycle extends AppLifecycle {

  private val current = Var(if GtkApp.shown then AppState.Foreground else AppState.Background)

  val state: Signal[AppState] = current

  def onTerminate(f: () => Unit): Disposable = GtkApp.onShutdown(f)

  locally {
    val _ = GtkApp.onShownChange(s => current.set(if s then AppState.Foreground else AppState.Background))
  }

}

/** Permissions on GTK: nothing to ask in advance. A desktop app outside a sandbox may use what the system has; inside
  * one (Flatpak), the portal asks the user when a device is first used, and the service that uses it reports
  * `Unavailable` if the answer is no. So every permission is `Granted`, and a request answers that, on the UI thread.
  */
object GtkPermissions extends Permissions {

  def status(p: Permission): PermissionStatus = PermissionStatus.Granted

  def request(p: Permission)(onResult: PermissionStatus => Unit): Unit =
    GtkApp.postToUi(() => onResult(PermissionStatus.Granted))

}

/** Both, for an app: `GtkDevice()` inside `GtkApp.run`. */
object GtkDevice {

  def apply(): Device = Device(GtkLifecycle, GtkPermissions)

}
