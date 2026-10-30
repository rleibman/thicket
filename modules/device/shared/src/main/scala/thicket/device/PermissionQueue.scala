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

import scala.collection.mutable

/** One permission request at a time, in order: each platform shows one dialog and answers it before the next.
  *
  * `ask` puts up the platform's dialog for the request at the front; whoever hears the user's answer calls [[answer]],
  * which delivers it and asks the next. Single-threaded: the UI thread's.
  */
final private[device] class PermissionQueue(ask: Permission => Unit) {

  private val waiting = mutable.Queue.empty[(Permission, PermissionStatus => Unit)]
  private var current: Option[(Permission, PermissionStatus => Unit)] = None

  /** The permission whose dialog is up, if one is. */
  def pending: Option[Permission] = current.map(_._1)

  def request(
    p:        Permission,
    onResult: PermissionStatus => Unit
  ): Unit = {
    waiting.enqueue(p -> onResult)
    next()
  }

  /** The user answered the request at the front. An answer with nothing pending — a dialog from before a restart — is
    * ignored.
    */
  def answer(status: PermissionStatus): Unit =
    current.foreach {
      (
        _,
        onResult
      ) =>
        current = None
        try onResult(status)
        finally next()
    }

  private def next(): Unit =
    if current.isEmpty && waiting.nonEmpty then {
      val head = waiting.dequeue()
      current = Some(head)
      try ask(head._1)
      catch {
        case e: Throwable =>
          current = None
          throw e
      }
    }

}

/** Functions to run once, when something ends; each can be removed first. */
final private[device] class Hooks {

  private val hooks = mutable.LinkedHashMap.empty[Long, () => Unit]
  private var nextId = 0L

  def add(f: () => Unit): thicket.signals.Disposable = {
    nextId += 1
    val id = nextId
    hooks(id) = f
    () => { val _ = hooks.remove(id) }
  }

  /** Runs every hook, in the order added, and forgets them. One that throws does not stop the rest. */
  def run(): Unit = {
    val all = hooks.values.toList
    hooks.clear()
    all.foreach(f =>
      try f()
      catch { case e: Exception => thicket.signals.ErrorSink.report(e) }
    )
  }

}
