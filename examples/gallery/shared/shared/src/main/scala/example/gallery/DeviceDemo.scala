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

import thicket.device.{AppState, Device, Permission, PermissionStatus}
import thicket.signals.{Owner, Signal, Var}

import scala.util.Try

/** The Device section's state: where the app is in its lifecycle, how often it came back to the front, and the answer
  * to the one permission it asks for, notifications — a runtime permission on Android 13+ that needs no hardware.
  */
final class DeviceDemo(device: Device)(using Owner) {

  /** Times the app came back to the foreground this run. The first time it comes to the front is its start, which every
    * host begins in the background for: no window is shown, no Activity started, while the app is built.
    */
  val returns: Var[Int] = Var(0)

  /** What the notifications permission stands at, or why it cannot be asked. */
  val notifications: Var[String] = Var(DeviceDemo.label(Try(device.permissions.status(Permission.Notifications))))

  locally {
    var started = false
    var away = false
    val _ = Signal.effect {
      device.lifecycle.state() match {
        case AppState.Foreground =>
          if away then returns.update(_ + 1)
          started = true
          away = false
        case AppState.Background => away = started
      }
    }
  }

  val state: Signal[String] = device.lifecycle.state.map(DeviceDemo.stateLabel)

  def askForNotifications(): Unit = {
    notifications.set(DeviceDemo.asking)
    Try(device.permissions.request(Permission.Notifications)(s => notifications.set(DeviceDemo.label(Try(s))))).failed
      .foreach(e => notifications.set(DeviceDemo.label(scala.util.Failure(e))))
  }

}

object DeviceDemo {

  def stateLabel(s: AppState): String = s"The app is in the ${s.toString.toLowerCase}"

  def returnsLabel(n: Int): String = s"Back in front $n times"

  val asking: String = "Notifications: asking…"

  def label(s: Try[PermissionStatus]): String =
    s.fold(e => s"Notifications: ${e.getMessage}", s => s"Notifications: $s")

}
