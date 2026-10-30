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

package thicket.device.testing

import thicket.device.*
import thicket.signals.{Disposable, Signal, Var}

import scala.collection.mutable

/** An [[AppLifecycle]] a test drives: [[set]] moves the app between foreground and background, [[terminate]] runs the
  * hooks. As `TestRenderer` is for widgets (`docs/16` §16.6).
  */
final class FakeLifecycle(initial: AppState = AppState.Foreground) extends AppLifecycle {

  private val current = Var(initial)
  private val hooks = thicket.device.Hooks()

  val state: Signal[AppState] = current

  def set(s: AppState): Unit = current.set(s)

  def onTerminate(f: () => Unit): Disposable = hooks.add(f)

  def terminate(): Unit = hooks.run()

}

/** [[Permissions]] whose user answers with `answers`, at once. [[asked]] records each request that reached the "user":
  * one already granted does not.
  */
final class FakePermissions(answers: Permission => PermissionStatus) extends Permissions {

  private val statuses = mutable.Map.empty[Permission, PermissionStatus]
  val asked: mutable.ArrayBuffer[Permission] = mutable.ArrayBuffer.empty

  def status(p: Permission): PermissionStatus = statuses.getOrElse(p, PermissionStatus.Denied)

  def request(p: Permission)(onResult: PermissionStatus => Unit): Unit =
    if status(p) == PermissionStatus.Granted then onResult(PermissionStatus.Granted)
    else {
      asked += p
      val s = answers(p)
      statuses(p) = s
      onResult(s)
    }

}
