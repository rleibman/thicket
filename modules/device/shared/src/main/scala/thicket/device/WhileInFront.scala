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

import thicket.signals.{Disposable, Owner, Signal}

/** Keeps a sensor running only while the app is in front, for as long as an owner lives: what every device service that
  * listens to hardware needs (`docs/16` §16.6). Location and motion use it.
  */
object WhileInFront {

  /** Calls `start` now if the app is in the foreground, and again each time it comes back; disposes what `start`
    * returned when the app goes to the background, and when the owner is disposed. `start` returns `None` when it
    * cannot start now (the sensor not available, say), and is then tried again at the next return to the front.
    */
  def apply(lifecycle: AppLifecycle)(start: () => Option[Disposable])(using owner: Owner): Unit = {
    var running: Option[Disposable] = None
    def stop():  Unit = {
      running.foreach(_.dispose())
      running = None
    }
    val _ = Signal.effect {
      lifecycle.state() match {
        case AppState.Foreground => if running.isEmpty then running = Signal.untracked(start())
        case AppState.Background => stop()
      }
    }
    owner.own(() => stop())
  }

}
