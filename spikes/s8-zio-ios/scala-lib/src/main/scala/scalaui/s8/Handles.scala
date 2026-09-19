package scalaui.s8

import scala.collection.mutable
import scala.scalanative.unsafe.*

/** S4 found that Scala Native rejects a `CFuncPtr` that closes over local state — it is a compile
  * error, not a runtime surprise. So every C callback must be a *static* function plus an explicit
  * context, and the context has to index something. This is that index: the handle table `docs/07`
  * §7.6 proposes, implemented for UIKit.
  *
  * S7 implemented the same table against GTK and had to launder the id through
  * `Intrinsics.castLongToRawPtr`, because GObject types `user_data` as `gpointer` and
  * `id.asInstanceOf[Ptr[Byte]]` compiles but throws at runtime. Here the shim's own header types
  * the context as `int64_t`, so the id travels as a number and that entire class of bug is designed
  * out rather than worked around. That is the one real advantage of controlling both sides of the
  * ABI, and it is worth spending on every shim we write.
  */
object Handles:
  private val callbacks: mutable.LongMap[() => Unit] = mutable.LongMap.empty
  private var nextId: Long = 1L

  /** The table is written from whichever thread registers a callback and read from the thread the
    * host calls back on — for `sui_run_on_main` those are different threads by construction. An
    * unsynchronised `LongMap` here does not throw; the background thread simply dies mid-resize and
    * its work silently stops, which cost real time to find. Any handle table in the framework has
    * to be concurrent from the start.
    */
  private val lock = new Object

  /** The closure is reachable from this table for as long as C can call back, which is what keeps
    * the GC from collecting it. Dropping the table entry is the only thing that makes a callback
    * collectable, so `release` is the counterpart to `sui_destroy`.
    */
  def register(f: () => Unit): Long = lock.synchronized:
    val id = nextId
    nextId += 1
    callbacks(id) = f
    id

  def release(id: Long): Unit = lock.synchronized(callbacks -= id)

  /** Registers a callback that removes itself once it has run. The ZIO executor posts one of these
    * per task, so without self-removal the table would grow at tick rate forever.
    */
  def registerOneShot(f: () => Unit): Long = lock.synchronized:
    val id = nextId
    nextId += 1
    callbacks(id) = () =>
      release(id)
      f()
    id

  def count: Int = lock.synchronized(callbacks.size)

  // The lookup is synchronised but the call is not: running user code under the lock would
  // deadlock the moment a callback registers another callback.
  private def invoke(id: Long): Unit =
    lock.synchronized(callbacks.get(id)).foreach(_())

  /** The single static trampoline every tap comes through. One C function, N closures,
    * discriminated by the id — as opposed to one C function per callback, which is what you would
    * need without the table.
    */
  val tapTrampoline: CFuncPtr1[Long, Unit] =
    CFuncPtr1.fromScalaFunction((id: Long) => GcState.guarded(invoke(id)))

  /** Same trampoline shape for main-thread posts; kept separate so the two can diverge (e.g.
    * draining a queue) without touching the tap path.
    */
  val mainTrampoline: CFuncPtr1[Long, Unit] =
    CFuncPtr1.fromScalaFunction((id: Long) => GcState.guarded(invoke(id)))
