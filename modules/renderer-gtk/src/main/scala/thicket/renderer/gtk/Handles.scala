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

package thicket.renderer.gtk

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.scalanative.unsafe.*
import scala.scalanative.runtime.{Intrinsics, fromRawPtr, toRawPtr}
import sn.gnome.glib.internal.{gpointer, gboolean, gint}
import sn.gnome.gtk4.internal.{GtkAlertDialog, gtk_alert_dialog_choose_finish}
import sn.gnome.gio.internal.GAsyncResult

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

  /** How many callbacks the table is holding.
    *
    * For the self-test. An id that `destroy` forgets to release keeps its closure here
    * forever, and the closure can capture a whole subtree — so "the table is the same size
    * after mounting and unmounting" is the only cheap way to notice. Found the hard way:
    * `valueIds` had been leaking on GTK since `Slider` landed, and a review caught the same
    * omission for `Picker` on Apple.
    */
  def liveCount: Int = callbacks.size + valued.size + readers.size + muted.size + listBinders.size

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

  private val chosen: ConcurrentHashMap[java.lang.Long, Int => Unit] =
    new ConcurrentHashMap[java.lang.Long, Int => Unit]()

  def registerChosen(f: Int => Unit): Long = {
    val id = nextId.getAndIncrement()
    chosen.put(id, f)
    id
  }

  def releaseChosen(id: Long): Unit = {
    val _ = chosen.remove(id)
  }

  /** `GAsyncReadyCallback`: (source, result, user_data).
    *
    * `gtk_alert_dialog_choose_finish` returns the chosen button's index, or -1 with the
    * error set — which is what a cancellation looks like, and cancelling is the only way to
    * take an alert down programmatically. So -1 is "no choice was made", and it is the
    * caller that knows whether that was a dismissal or its own `dismiss`.
    */
  private val alertChosen: CFuncPtr3[Ptr[Byte], Ptr[Byte], gpointer, Unit] =
    CFuncPtr3.fromScalaFunction { (source: Ptr[Byte], result: Ptr[Byte], data: gpointer) =>
      GcState.guarded {
        val id = pointerToId(data)
        val index =
          gtk_alert_dialog_choose_finish(
            source.asInstanceOf[Ptr[GtkAlertDialog]],
            result.asInstanceOf[Ptr[GAsyncResult]],
            null
          )
        val f = chosen.get(id)
        if f != null then f(index)
        releaseChosen(id)
      }
    }

  def alertChosenPtr: CVoidPtr = CFuncPtr.toPtr(alertChosen)

  /** `GtkWindow::close-request`: (window, user_data) -> gboolean.
    *
    * Returns FALSE, meaning "go ahead and close". Its own arity is needed because a handler
    * declared as returning Unit would leave whatever happened to be in the return register
    * for GTK to read as the answer — and TRUE there would veto the close, silently and
    * intermittently.
    */
  private val closeRequest: CFuncPtr2[Ptr[Byte], gpointer, gboolean] =
    CFuncPtr2.fromScalaFunction { (_: Ptr[Byte], data: gpointer) =>
      GcState.guarded(invoke(pointerToId(data)))
      0.asInstanceOf[gboolean]
    }

  def closeRequestPtr: CVoidPtr = CFuncPtr.toPtr(closeRequest)

  /** Malformed CSS, counted rather than logged.
    *
    * GTK reports a bad stylesheet by emitting `parsing-error` on the provider and writing a
    * `Gtk-WARNING` to stderr — then carrying on with whatever it managed to parse. That is
    * how a renderer bug survived from the day theming landed: the stylesheet asked for CSS
    * nesting, which GTK4 does not support, so the rule for a tinted container's children was
    * discarded and the colour silently never arrived. Twenty-six warnings per run of the
    * demo, and nothing failed, because the self-tests look for `Gtk-CRITICAL`.
    *
    * A count the renderer keeps is checkable; a warning on stderr is not. The self-tests
    * assert this is zero.
    */
  private val cssErrors = new AtomicLong(0L)

  def cssParseErrors: Long = cssErrors.get()

  /** `parsing-error` is `(GtkCssProvider*, GtkCssSection*, GError*, gpointer)`. None of the
    * arguments are needed: that it fired at all is the failure.
    */
  private val cssParsingError: CFuncPtr4[Ptr[Byte], Ptr[Byte], Ptr[Byte], gpointer, Unit] =
    CFuncPtr4.fromScalaFunction {
      (
        _:  Ptr[Byte],
        _:  Ptr[Byte],
        _:  Ptr[Byte],
        _:  gpointer
      ) =>
        GcState.guarded { val _ = cssErrors.incrementAndGet() }
    }

  def cssParsingErrorPtr: CVoidPtr = CFuncPtr.toPtr(cssParsingError)

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
