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

package thicket.renderer.apple

import scala.scalanative.unsafe.*

/** Managed/Unmanaged bookkeeping for the GC across the host's event loop (S3).
  *
  * Scala Native stops the world by waiting for every *Managed* thread to reach a safepoint. The AppKit main thread
  * calls into Scala, returns into the run loop and parks there — in native code, polling nothing — so the first
  * collection triggered by any other Scala thread would hang and then abort. A thread marked Unmanaged is treated as
  * executing foreign code and skipped.
  *
  * The rule: **mark Unmanaged on every return into the host's run loop, Managed on every re-entry.** Every host ->
  * Scala entry point here goes through `guarded`.
  */
@extern
private object gc {

  def scalanative_GC_set_mutator_thread_state(state: CInt): Unit = extern

}

object GcState {

  inline private val Managed = 0
  inline private val Unmanaged = 1

  def enterScala(): Unit = gc.scalanative_GC_set_mutator_thread_state(Managed)
  def leaveScala(): Unit = gc.scalanative_GC_set_mutator_thread_state(Unmanaged)

  /** Called once, when the host's run loop takes ownership of the main thread. From that point Scala must not look
    * Managed to the GC while the thread is parked in the loop.
    */
  def releaseMainThread(): Unit = leaveScala()

  inline def guarded[A](inline body: A): A = {
    enterScala()
    try body
    finally leaveScala()
  }

}
