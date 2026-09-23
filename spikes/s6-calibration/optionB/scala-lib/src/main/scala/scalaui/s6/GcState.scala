package scalaui.s6

import scala.scalanative.unsafe.*

/** The fix for S3's stop-the-world deadlock.
  *
  * Scala Native's GC stops the world by waiting for every *Managed* thread to reach a safepoint. A thread that called
  * `ScalaNativeInit` stays Managed until told otherwise. The UIKit main thread does exactly that, then returns into
  * CFRunLoop and parks in `mach_msg` — inside native code, never polling a safepoint again until an event arrives. So
  * the first GC triggered by any *other* Scala thread hangs the app:
  *
  * [ScalaNative GC|Warning] Waiting for 1 thread(s) to reach safepoint (10.0s elapsed) Thread id=..., state=Managed,
  * alive=yes
  *
  * `scalanative_GC_set_mutator_thread_state` is the sanctioned escape: a thread marked Unmanaged is "executing foreign
  * code", is skipped by stop-the-world, and blocks on its way back in if a collection is running. So the rule for any
  * callback-driven renderer is that **Scala must mark itself Unmanaged on every return into the host's event loop**,
  * and Managed again on every re-entry.
  */
@extern
private object gc {

  def scalanative_GC_set_mutator_thread_state(state: CInt): Unit = extern

}

object GcState {

  inline private val Managed = 0
  inline private val Unmanaged = 1

  /** Call on entry to any Scala code invoked from the host (tap callbacks, main-thread posts, the initial
    * scalaui_main).
    */
  def enterScala(): Unit = gc.scalanative_GC_set_mutator_thread_state(Managed)

  /** Call immediately before returning control to the host's run loop. */
  def leaveScala(): Unit = gc.scalanative_GC_set_mutator_thread_state(Unmanaged)

  /** Wraps a host -> Scala entry point. */
  inline def guarded[A](inline body: A): A = {
    enterScala()
    try body
    finally leaveScala()
  }

}
