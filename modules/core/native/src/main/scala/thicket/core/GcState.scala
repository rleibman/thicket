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

package thicket.core

import scala.scalanative.unsafe.*
import thicket.signals.ErrorSink

@extern
private object GcExtern {

  def scalanative_GC_set_mutator_thread_state(state: CInt): Unit = extern

}

/** Managed/Unmanaged bookkeeping for the GC across a host's event loop, shared by every Scala Native renderer (S3).
  *
  * Scala Native stops the world by waiting for every *Managed* thread to reach a safepoint, and a thread that called
  * `ScalaNativeInit` stays Managed for life. The main thread hands itself to the toolkit's loop (GTK's, AppKit's,
  * UIKit's) and parks there in native code, polling nothing, so the first collection triggered by any other Scala
  * thread would hang and then abort. A thread marked Unmanaged is treated as executing foreign code and skipped.
  *
  * The rule: **Unmanaged while the loop holds the thread, Managed whenever Scala is running on it.** Every host → Scala
  * entry point goes through [[guarded]].
  *
  * **Re-entrant, which it was not.** A setter Scala calls — `gtk_editable_set_text`, `gtk_switch_set_active` — emits
  * its signal synchronously, so a trampoline runs *inside* a Scala frame. Each entry used to mark the thread Managed
  * and each exit Unmanaged, so the inner exit left the thread Unmanaged with the outer Scala frame still running; a
  * collection started by a worker at that moment would skip the main thread's roots. The depth below makes only the
  * outermost transition count (review 2026-10-20, P0-2). It is per thread because the state is: a thread Scala created
  * never leaves Scala, so it starts — and stays — at depth 1, and only [[releaseMainThread]] takes a thread to 0.
  *
  * **And an error barrier.** An exception thrown out of a trampoline would unwind through the toolkit's C frames, so
  * [[guarded]] reports it to [[ErrorSink]] and returns normally (P0-3).
  */
object GcState {

  inline private val Managed = 0
  inline private val Unmanaged = 1

  private val depth: ThreadLocal[Array[Int]] = ThreadLocal.withInitial(() => Array(1))

  /** How deep the current thread is in Scala: 0 while the host's loop holds it. For the self-tests. */
  def scalaDepth: Int = depth.get()(0)

  private def enter(): Array[Int] = {
    val d = depth.get()
    if d(0) == 0 then GcExtern.scalanative_GC_set_mutator_thread_state(Managed)
    d(0) += 1
    d
  }

  private def leave(d: Array[Int]): Unit = {
    d(0) -= 1
    if d(0) == 0 then GcExtern.scalanative_GC_set_mutator_thread_state(Unmanaged)
  }

  /** Wrap a host → Scala entry point that returns nothing. */
  inline def guarded(inline body: Unit): Unit = guardedOr(())(body)

  /** Wrap a host → Scala entry point that returns a value: `fallback` is what the host gets if `body` throws. */
  inline def guardedOr[A](inline fallback: A)(inline body: A): A = {
    val d = enter()
    try ErrorSink.catching(fallback)(body)
    finally leave(d)
  }

  /** Called once, on the main thread, just before handing it to the host's loop. From here Scala must not look Managed
    * to the GC while the thread is parked there.
    */
  def releaseMainThread(): Unit = {
    depth.get()(0) = 0
    GcExtern.scalanative_GC_set_mutator_thread_state(Unmanaged)
  }

  /** The reverse, for a host whose loop returns control (GTK's `g_application_run` does, at quit): Scala is running on
    * the thread again.
    */
  def reacquireMainThread(): Unit = {
    GcExtern.scalanative_GC_set_mutator_thread_state(Managed)
    depth.get()(0) = 1
  }

}
