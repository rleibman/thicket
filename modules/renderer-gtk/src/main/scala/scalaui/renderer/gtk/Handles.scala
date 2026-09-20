package scalaui.renderer.gtk

import scala.scalanative.unsafe.*
import scala.scalanative.runtime.{Intrinsics, fromRawPtr, toRawPtr}
import scala.collection.mutable
import sn.gnome.glib.internal.{gpointer, gboolean, gint}

/** Callbacks cannot close over local state in Scala Native (compile error), so every C
  * callback is a static trampoline plus an explicit context. The context travels as the
  * `user_data` pointer, keyed into this table.
  *
  * `Long` ⇄ `Ptr` must go through the intrinsics: `id.asInstanceOf[Ptr[Byte]]` compiles and
  * then throws `ClassCastException` at runtime (S7).
  */
private[gtk] object Handles:
  private val callbacks: mutable.LongMap[() => Unit] = mutable.LongMap.empty
  private var nextId: Long                           = 1L

  def register(f: () => Unit): Long = synchronized:
    val id = nextId
    nextId += 1
    callbacks(id) = f
    id

  def replace(id: Long, f: () => Unit): Unit = synchronized:
    callbacks(id) = f

  def release(id: Long): Unit = synchronized:
    val _ = callbacks.remove(id)

  private def invoke(id: Long): Unit =
    val f = synchronized(callbacks.get(id))
    f.foreach(_())

  def idToPointer(id: Long): gpointer =
    fromRawPtr[Byte](Intrinsics.castLongToRawPtr(id)).asInstanceOf[gpointer]

  private def pointerToId(p: gpointer): Long =
    Intrinsics.castRawPtrToLong(toRawPtr(p.asInstanceOf[Ptr[Byte]]))

  /** GTK "clicked": (GtkWidget*, gpointer) -> void */
  private val clicked: CFuncPtr2[Ptr[Byte], gpointer, Unit] =
    CFuncPtr2.fromScalaFunction { (_: Ptr[Byte], data: gpointer) =>
      GcState.guarded(invoke(pointerToId(data)))
    }

  def clickedPtr: CVoidPtr = CFuncPtr.toPtr(clicked)

  /** GSourceFunc: returning 0 (G_SOURCE_REMOVE) makes it one-shot. A table that does not
    * shed entries at UI rates is a leak with a clock on it (S8).
    */
  val idle: CFuncPtr1[gpointer, gboolean] =
    CFuncPtr1.fromScalaFunction { (data: gpointer) =>
      val id = pointerToId(data)
      GcState.guarded:
        invoke(id)
        release(id)
      0.asInstanceOf[gint]
    }
