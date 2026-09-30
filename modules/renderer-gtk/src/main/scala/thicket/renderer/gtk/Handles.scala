package thicket.renderer.gtk

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.scalanative.unsafe.*
import scala.scalanative.runtime.{Intrinsics, fromRawPtr, toRawPtr}
import sn.gnome.glib.internal.{gpointer, gboolean, gint}

/** Callbacks cannot close over local state in Scala Native (compile error), so every C
  * callback is a static trampoline plus an explicit context. The context travels as the
  * `user_data` pointer, keyed into this table.
  *
  * `Long` ⇄ `Ptr` must go through the intrinsics: `id.asInstanceOf[Ptr[Byte]]` compiles and
  * then throws `ClassCastException` at runtime (S7).
  *
  * **Lock-free, deliberately.** This table was a `mutable.LongMap` behind a monitor — the
  * obvious design, and correct on the JVM. S9 measured that shape on iOS and found it
  * throws `IllegalMonitorStateException` on Scala Native under real load, and leaks
  * ~400 bytes of main-thread stack per callback that reposts from inside itself, until the
  * process dies. GTK has the same shape of traffic — `g_idle_add` callbacks that register
  * further callbacks — and Linux's larger main-thread stack only postpones it. See
  * `spikes/s9-zio-bridge-ios/REPORT.md`.
  */
private[gtk] object Handles {

  private val callbacks  = new ConcurrentHashMap[java.lang.Long, () => Unit]()
  private val valued     = new ConcurrentHashMap[java.lang.Long, String => Unit]()
  private val readers    = new ConcurrentHashMap[java.lang.Long, () => Any]()
  private val muted      = new ConcurrentHashMap[java.lang.Long, () => Boolean]()
  private val listBinders = new ConcurrentHashMap[java.lang.Long, Ptr[Byte] => Unit]()
  private val nextId     = new AtomicLong(1L)

  def register(f: () => Unit): Long = {
    val id = nextId.getAndIncrement()
    callbacks.put(id, f)
    id
  }

  /** Swapping the closure behind an existing id, rather than connecting a second signal, is
    * what stops repeated property updates stacking handlers.
    */
  def replace(id: Long, f: () => Unit): Unit = {
    val _ = callbacks.put(id, f)
  }

  def release(id: Long): Unit = {
    val _ = callbacks.remove(id)
    val _ = valued.remove(id)
    val _ = listBinders.remove(id)
    val _ = readers.remove(id)
    val _ = muted.remove(id)
  }

  private def invoke(id: Long): Unit = {
    val f = callbacks.get(id)
    if f != null then f()
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

  def registerValued(f: String => Unit): Long = {
    val id = nextId.getAndIncrement()
    valued.put(id, f)
    id
  }

  def replaceValued(id: Long, f: String => Unit): Unit = {
    val _ = valued.put(id, f)
  }

  def bindTextSource(id: Long, read: () => Any, isMuted: () => Boolean): Unit = {
    val _ = readers.put(id, read)
    val _ = muted.put(id, isMuted)
  }

  private def invokeValued(id: Long): Unit = {
    val isMuted = muted.get(id)
    if isMuted == null || !isMuted() then {
      val f    = valued.get(id)
      val read = readers.get(id)
      if f != null && read != null then f(read().toString)
    }
  }

  /** GTK "changed"/"toggled": (GtkWidget*, gpointer) -> void */
  private val changed: CFuncPtr2[Ptr[Byte], gpointer, Unit] =
    CFuncPtr2.fromScalaFunction { (_: Ptr[Byte], data: gpointer) =>
      GcState.guarded(invokeValued(pointerToId(data)))
    }

  def changedPtr: CVoidPtr = CFuncPtr.toPtr(changed)

  /** The same handler behind GObject's `notify::<property>`, which carries the `GParamSpec`
    * and so is `(object, pspec, user_data)`.
    *
    * `GtkSwitch` has no 2-argument "it changed" signal: `state-set` also takes the new
    * state, and `activate` is a keybinding signal that a mouse never fires. So a switch
    * binds `notify::active` and reads the widget, exactly as the text field does.
    */
  private val notified: CFuncPtr3[Ptr[Byte], Ptr[Byte], gpointer, Unit] =
    CFuncPtr3.fromScalaFunction { (_: Ptr[Byte], _: Ptr[Byte], data: gpointer) =>
      GcState.guarded(invokeValued(pointerToId(data)))
    }

  def notifiedPtr: CVoidPtr = CFuncPtr.toPtr(notified)

  /** `GtkGestureClick::released` is `(gesture, n_press, x, y, user_data)`, so a tap on a
    * plain container needs its own arity rather than reusing the button trampoline.
    */
  private val released: CFuncPtr5[Ptr[Byte], CInt, Double, Double, gpointer, Unit] =
    CFuncPtr5.fromScalaFunction {
      (_: Ptr[Byte], _: CInt, _: Double, _: Double, data: gpointer) =>
        GcState.guarded(invoke(pointerToId(data)))
    }

  def releasedPtr: CVoidPtr = CFuncPtr.toPtr(released)

  // -- GtkListView factory callbacks ---------------------------------------
  //
  // `GtkSignalListItemFactory::bind` is (factory, listitem, user_data). The list item is
  // the recycling unit: it carries the row position and whatever child it last held, which
  // is exactly what `RowSource.bind` wants.

  def registerListBinder(f: Ptr[Byte] => Unit): Long = {
    val id = nextId.getAndIncrement()
    listBinders.put(id, f)
    id
  }

  private val listBind: CFuncPtr3[Ptr[Byte], Ptr[Byte], gpointer, Unit] =
    CFuncPtr3.fromScalaFunction { (_: Ptr[Byte], item: Ptr[Byte], data: gpointer) =>
      GcState.guarded {
        val binder = listBinders.get(pointerToId(data))
        if binder != null then binder(item)
      }
    }

  def listBindPtr: CVoidPtr = CFuncPtr.toPtr(listBind)

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
