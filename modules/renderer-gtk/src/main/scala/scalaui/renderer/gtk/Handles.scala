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
private[gtk] object Handles {
  private val callbacks: mutable.LongMap[() => Unit] = mutable.LongMap.empty
  private var nextId: Long                           = 1L

  def register(f: () => Unit): Long = synchronized {
    val id = nextId
    nextId += 1
    callbacks(id) = f
    id
  }

  def replace(id: Long, f: () => Unit): Unit = synchronized {
    callbacks(id) = f
  }

  def release(id: Long): Unit = synchronized {
    val _ = callbacks.remove(id)
    val _ = valued.remove(id)
    val _ = readers.remove(id)
    val _ = muted.remove(id)
  }

  private def invoke(id: Long): Unit = {
    val f = synchronized(callbacks.get(id))
    f.foreach(_())
  }

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

  // -- value-carrying callbacks (text fields, checkboxes) -------------------
  //
  // GTK's "changed"/"toggled" signals carry no value: the handler is expected to read the
  // widget. So each registration stores three things — what to call, how to read the
  // current value, and whether this change came from the app rather than the user. The
  // last one is what stops a signal-bound field from looping: renderer writes value ->
  // GTK emits "changed" -> handler writes the signal -> renderer writes value...

  private val valued: mutable.LongMap[String => Unit]  = mutable.LongMap.empty
  private val readers: mutable.LongMap[() => Any]      = mutable.LongMap.empty
  private val muted: mutable.LongMap[() => Boolean]    = mutable.LongMap.empty

  def registerValued(f: String => Unit): Long = synchronized {
    val id = nextId
    nextId += 1
    valued(id) = f
    id
  }

  def replaceValued(id: Long, f: String => Unit): Unit = synchronized { valued(id) = f }

  def bindTextSource(id: Long, read: () => Any, isMuted: () => Boolean): Unit = synchronized {
    readers(id) = read
    muted(id) = isMuted
  }

  private def invokeValued(id: Long): Unit = {
    val (f, read, isMuted) = synchronized((valued.get(id), readers.get(id), muted.get(id)))
    if !isMuted.exists(_()) then
      for { fn <- f; r <- read } fn(r().toString)
  }

  /** GTK "changed"/"toggled": (GtkWidget*, gpointer) -> void */
  private val changed: CFuncPtr2[Ptr[Byte], gpointer, Unit] =
    CFuncPtr2.fromScalaFunction { (_: Ptr[Byte], data: gpointer) =>
      GcState.guarded(invokeValued(pointerToId(data)))
    }

  def changedPtr: CVoidPtr = CFuncPtr.toPtr(changed)

  /** GSourceFunc: returning 0 (G_SOURCE_REMOVE) makes it one-shot. A table that does not
    * shed entries at UI rates is a leak with a clock on it (S8).
    */
  val idle: CFuncPtr1[gpointer, gboolean] =
    CFuncPtr1.fromScalaFunction { (data: gpointer) =>
      val id = pointerToId(data)
      GcState.guarded {
        invoke(id)
        release(id)
      }
      0.asInstanceOf[gint]
    }
}
