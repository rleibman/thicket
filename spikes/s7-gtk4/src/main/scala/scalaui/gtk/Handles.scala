package scalaui.gtk

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.scalanative.runtime.{Intrinsics, fromRawPtr, toRawPtr}
import scala.collection.mutable
import sn.gnome.gtk4.internal.GtkWidget
import sn.gnome.glib.internal.{gpointer, gboolean, gint}

/** S4 found that Scala Native rejects a `CFuncPtr` closing over local state, so every
  * C callback must be a *static* function plus an explicit context. This is the handle
  * table docs/07 §7.6 proposes, implemented for real.
  *
  * Confirms independently that the design is forced, not a preference.
  */
object Handles:
  private val callbacks: mutable.LongMap[() => Unit]        = mutable.LongMap.empty
  private var nextId: Long                                  = 1L
  val kindOf: mutable.Map[Ptr[GtkWidget], WidgetKind]        = mutable.Map.empty

  /** Callback ids travel to C as the `user_data` pointer.
    *
    * CONTRACT FRICTION: `id.asInstanceOf[Ptr[Byte]]` compiles and then throws
    * `ClassCastException: java.lang.Long cannot be cast to Ptr` at runtime. Long <-> Ptr
    * must go through `Intrinsics.castLongToRawPtr`. Another silent-until-runtime trap in
    * the same family as S4's struct-return bug.
    */
  def idToPointer(id: Long): gpointer =
    fromRawPtr[Byte](Intrinsics.castLongToRawPtr(id)).asInstanceOf[gpointer]

  def pointerToId(p: gpointer): Long =
    Intrinsics.castRawPtrToLong(toRawPtr(p.asInstanceOf[Ptr[Byte]]))

  def registerCallback(f: () => Unit): Long =
    val id = nextId
    nextId += 1
    callbacks(id) = f
    id

  private def invoke(id: Long): Unit =
    callbacks.get(id).foreach(_())

  /** `clicked` handler: (GtkWidget*, gpointer) => void.
    *
    * CONTRACT FRICTION: GTK's generic `GCallback` is `CFuncPtr0[Unit]`, so every real
    * handler must be laundered through a raw pointer to be accepted. That defeats the
    * type checker at exactly the place a UI framework most wants it.
    */
  private val clicked: CFuncPtr2[Ptr[Byte], gpointer, Unit] =
    CFuncPtr2.fromScalaFunction { (_: Ptr[Byte], data: gpointer) =>
      invoke(pointerToId(data))
    }

  def clickedPtr: CVoidPtr = CFuncPtr.toPtr(clicked)

  /** GSourceFunc: (gpointer) => gboolean; returning 0 (G_SOURCE_REMOVE) runs it once. */
  val idleTrampoline: CFuncPtr1[gpointer, gboolean] =
    CFuncPtr1.fromScalaFunction { (data: gpointer) =>
      invoke(pointerToId(data))
      0.asInstanceOf[gint] // G_SOURCE_REMOVE: run once
    }
