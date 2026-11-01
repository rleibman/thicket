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

package thicket.clipboard.gtk

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.scalanative.runtime.{Intrinsics, fromRawPtr, toRawPtr}
import scala.scalanative.unsafe.*
import sn.gnome.gdk4.internal.*
import sn.gnome.gio.internal.{GAsyncReadyCallback, GAsyncResult}
import sn.gnome.glib.internal.{GError, g_error_free, g_free, gpointer}
import thicket.clipboard.Clipboard
import thicket.core.GcState

/** The display's clipboard, through `GdkClipboard`: the one Ctrl+C and Ctrl+V use, shared with every other app.
  *
  * `get` reads with `gdk_clipboard_read_text_async`, whose callback runs on the main loop. Text this app set is
  * answered locally; another app's is asked of that app, through the compositor. A clipboard holding no text (empty, or
  * an image) answers `None`. Needs a display: create it inside `GtkApp.run`.
  */
final class GtkClipboard private (clipboard: Ptr[GdkClipboard]) extends Clipboard {

  def set(text: String): Unit = Zone(gdk_clipboard_set_text(clipboard, toCString(text)))

  def get(onResult: Option[String] => Unit): Unit = {
    val id = GtkClipboard.nextId.getAndIncrement()
    GtkClipboard.pending.put(id, onResult)
    gdk_clipboard_read_text_async(
      clipboard,
      null,
      GAsyncReadyCallback.fromPtr(CFuncPtr.toPtr(GtkClipboard.read)),
      fromRawPtr[Byte](Intrinsics.castLongToRawPtr(id)).asInstanceOf[gpointer]
    )
  }

}

object GtkClipboard {

  /** The default display's clipboard. */
  def apply(): GtkClipboard = new GtkClipboard(gdk_display_get_clipboard(gdk_display_get_default()))

  // Callbacks cannot close over state in Scala Native: each read's continuation waits here under the id that travels
  // as the callback's user data, as `thicket.renderer.gtk.Handles` does.
  private val pending = new ConcurrentHashMap[java.lang.Long, Option[String] => Unit]()
  private val nextId = new AtomicLong(1L)

  private val read: CFuncPtr3[Ptr[Byte], Ptr[Byte], gpointer, Unit] =
    CFuncPtr3.fromScalaFunction {
      (
        source: Ptr[Byte],
        result: Ptr[Byte],
        data:   gpointer
      ) =>
        GcState.guarded {
          val err = stackalloc[Ptr[GError]]()
          !err = null
          val text = gdk_clipboard_read_text_finish(
            source.asInstanceOf[Ptr[GdkClipboard]],
            result.asInstanceOf[Ptr[GAsyncResult]],
            err
          )
          // An error here is "no text to give": an empty clipboard, or one holding only an image.
          if !err != null then g_error_free(!err)
          val answer =
            if text == null then None
            else {
              val s = fromCString(text)
              g_free(text.asInstanceOf[gpointer])
              Some(s)
            }
          val id = Intrinsics.castRawPtrToLong(toRawPtr(data.asInstanceOf[Ptr[Byte]]))
          val k = pending.remove(id)
          if k != null then k(answer)
        }
    }

}
