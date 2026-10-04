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

package thicket.signals

/** Enforces the single-threaded contract (docs/05 A-06).
  *
  * The default is [[off]]: a library cannot know which thread is "the" UI thread. The framework installs
  * [[owningThread]] (or its own UI-thread guard) during start-up, once, on the thread that owns the graph.
  */
trait ThreadGuard {

  /** Throws `IllegalStateException` if `op` is being performed on the wrong thread. */
  def check(op: String): Unit

}

object ThreadGuard {

  /** Allows everything. The default, and the only sensible guard on Scala.js. */
  val off: ThreadGuard = _ => ()

  /** A fresh guard that binds to the first thread which touches it and rejects every other one. On Scala.js this is
    * [[off]], since there are no other threads.
    */
  def owningThread: ThreadGuard = ThreadGuardPlatform.owningThread

  private var current: ThreadGuard = off

  def install(g: ThreadGuard): Unit = current = g
  def installed:               ThreadGuard = current
  def check(op:  String):      Unit = current.check(op)

}
