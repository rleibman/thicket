package scalaui.renderer.gtk

import scala.scalanative.unsafe.*

/** S3 found that Scala Native's GC stops the world by waiting for every *Managed* thread to
  * reach a safepoint, and that a thread which called `ScalaNativeInit` stays Managed for
  * life. When such a thread parks inside a foreign event loop it polls no safepoints, so the
  * first collection triggered by any other thread hangs and then aborts.
  *
  * S3 hit this on UIKit and noted it applies to any host-owned event loop — GTK's included.
  * S7 escaped it only because its app never allocated off the main thread; the moment a
  * background thread does, it would abort. So the rule is applied here from the start.
  */
@extern
private[gtk] object GcExtern:
  def scalanative_GC_set_mutator_thread_state(state: CInt): Unit = extern

private[gtk] object GcState:
  private inline val Managed   = 0
  private inline val Unmanaged = 1

  /** Wrap every host → Scala entry point. Scala is Managed inside `body` and Unmanaged
    * once control returns to the event loop.
    */
  inline def guarded[A](inline body: A): A =
    GcExtern.scalanative_GC_set_mutator_thread_state(Managed)
    try body
    finally GcExtern.scalanative_GC_set_mutator_thread_state(Unmanaged)

  /** Called once, just before handing the main thread to the GTK main loop. */
  def releaseMainThread(): Unit =
    GcExtern.scalanative_GC_set_mutator_thread_state(Unmanaged)
