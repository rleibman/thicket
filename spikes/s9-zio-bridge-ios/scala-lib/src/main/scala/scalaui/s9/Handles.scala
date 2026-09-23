package scalaui.s9

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.scalanative.unsafe.*

/** The handle table: `Long` id -> closure, because a C function pointer cannot carry one (S4), and an `int64_t` context
  * avoids the `Long` <-> `Ptr` laundering a `void*` forces (S7).
  *
  * **No `synchronized` here, deliberately.** S3's version guarded a `mutable.LongMap` with a monitor, which is correct
  * on the JVM and fails on Scala Native under this spike's load — registrations arriving from ZIO threads and the
  * driver thread while the main thread invokes from run-loop callbacks produced:
  *
  * {{{
  * java.lang.IllegalMonitorStateException: Thread is not an owner of this object
  *   at scala.scalanative.runtime.monitor.ObjectMonitor.exit
  *   at scalaui.s9.Handles$.registerOneShot
  * }}}
  *
  * thrown on *exit* from a monitor the thread had just entered. `ConcurrentHashMap` plus an `AtomicLong` does the same
  * job without object monitors, and is what any handle table in the framework should use.
  */
object Handles {

  private val callbacks = new ConcurrentHashMap[java.lang.Long, () => Unit]()
  private val nextId = new AtomicLong(1L)

  /** The closure stays reachable from this table for as long as C can call back, which is what keeps the GC from
    * collecting it. Dropping the entry is the only thing that makes a callback collectable, so `release` is the
    * counterpart to `sui_destroy`.
    */
  def register(f: () => Unit): Long = {
    val id = nextId.getAndIncrement()
    callbacks.put(id, f)
    id
  }

  /** Registers a callback that removes itself once it has run. Every UI post uses one of these: at 60 Hz a table that
    * never sheds entries is a leak with a clock on it (S8).
    */
  def registerOneShot(f: () => Unit): Long = {
    val id = nextId.getAndIncrement()
    callbacks.put(
      id,
      () => {
        release(id)
        f()
      }
    )
    id
  }

  def release(id: Long): Unit = { val _ = callbacks.remove(id) }

  def count: Int = callbacks.size

  private def invoke(id: Long): Unit = {
    val f = callbacks.get(id)
    if f != null then f()
  }

  /** The single static trampoline every tap comes through: one C function, N closures, discriminated by the id.
    */
  val tapTrampoline: CFuncPtr1[Long, Unit] =
    CFuncPtr1.fromScalaFunction((id: Long) => GcState.guarded(invoke(id)))

  /** Same shape for main-thread posts, kept separate so the two can diverge without touching the tap path.
    */
  val mainTrampoline: CFuncPtr1[Long, Unit] =
    CFuncPtr1.fromScalaFunction((id: Long) => GcState.guarded(invoke(id)))

  /** Diagnostic only: identical to `mainTrampoline` without the Managed/Unmanaged guard, used to show that `GcState` is
    * not implicated in a failure.
    */
  val unguardedTrampoline: CFuncPtr1[Long, Unit] =
    CFuncPtr1.fromScalaFunction((id: Long) => invoke(id))

}
