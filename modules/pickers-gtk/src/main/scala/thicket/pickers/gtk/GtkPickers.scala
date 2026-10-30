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

package thicket.pickers.gtk

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.scalanative.runtime.{Intrinsics, fromRawPtr, toRawPtr}
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.UnsignedRichInt
import sn.gnome.gio.internal.{
  GAsyncReadyCallback,
  GAsyncResult,
  GFile,
  GListModel,
  g_file_get_path,
  g_list_model_get_item,
  g_list_model_get_n_items
}
import sn.gnome.glib.internal.{GError, g_error_free, g_free, gpointer, guint}
import sn.gnome.gobject.internal.g_object_unref
import sn.gnome.gtk4.internal.*
import thicket.core.GcState
import thicket.device.Availability
import thicket.pickers.{Documents, Photos, PickedFiles}
import thicket.renderer.gtk.GtkApp

/** The pickers on GTK, through `GtkFileDialog` (GTK 4.10+), shown over `GtkApp`'s window: the desktop's own file
  * chooser, through the portal when the app is sandboxed.
  *
  * Picked files are copied into `cache` on a worker thread and handed back on the main loop, as on Android, so an app
  * behaves the same everywhere (owner's decision, 2026-10-30). A cancelled dialog answers empty.
  */
final class GtkPickers(cache: Path) {

  import GtkPickers.*

  private val picked = PickedFiles(cache)

  val photos: Photos = new Photos {

    def availability: Availability = Availability.Available

    def pick(limit: Int)(onResult: Seq[Path] => Unit): Unit =
      choose(List("image/*"), multiple = limit > 1)(paths => copyAll(paths.take(limit))(onResult))

  }

  val documents: Documents = new Documents {

    def availability: Availability = Availability.Available

    def open(
      mimeTypes: Seq[String],
      multiple:  Boolean
    )(
      onResult: Seq[Path] => Unit
    ): Unit = choose(mimeTypes.toList, multiple)(paths => copyAll(if multiple then paths else paths.take(1))(onResult))

    def save(
      name:     String,
      mimeType: String,
      bytes:    Array[Byte]
    )(
      onResult: Option[String] => Unit
    ): Unit = {
      val dialog = gtk_file_dialog_new()
      Zone(gtk_file_dialog_set_initial_name(dialog, toCString(name)))
      start(dialog) {
        (
          d,
          result
        ) =>
          val err = stackalloc[Ptr[GError]]()
          !err = null
          val file = gtk_file_dialog_save_finish(d, result, err)
          if !err != null then g_error_free(!err)
          pathOf(file) match {
            case None       => onResult(None)
            case Some(path) =>
              onWorker(scala.util.Try { Files.write(path, bytes); path.getFileName.toString }.toOption)(onResult)
          }
      }(gtk_file_dialog_save, () => onResult(None))
    }

  }

  /** Shows the open dialog for `mimeTypes` (any, if empty), and hands the chosen files' paths to `then`. */
  private def choose(
    mimeTypes: List[String],
    multiple:  Boolean
  )(
    chosen: List[Path] => Unit
  ): Unit = {
    val dialog = gtk_file_dialog_new()
    if mimeTypes.nonEmpty then {
      val filter = gtk_file_filter_new()
      Zone(mimeTypes.foreach(m => gtk_file_filter_add_mime_type(filter, toCString(m))))
      gtk_file_dialog_set_default_filter(dialog, filter)
      g_object_unref(filter.asInstanceOf[gpointer])
    }
    if multiple then
      start(dialog) {
        (
          d,
          result
        ) =>
          val err = stackalloc[Ptr[GError]]()
          !err = null
          val list = gtk_file_dialog_open_multiple_finish(d, result, err)
          if !err != null then g_error_free(!err)
          chosen(if list == null then Nil else filesIn(list))
      }(gtk_file_dialog_open_multiple, () => chosen(Nil))
    else
      start(dialog) {
        (
          d,
          result
        ) =>
          val err = stackalloc[Ptr[GError]]()
          !err = null
          val file = gtk_file_dialog_open_finish(d, result, err)
          if !err != null then g_error_free(!err)
          chosen(pathOf(file).toList)
      }(gtk_file_dialog_open, () => chosen(Nil))
  }

  private def copyAll(paths: List[Path])(onResult: Seq[Path] => Unit): Unit =
    onWorker(paths.flatMap(p => scala.util.Try(picked.copy(p.getFileName.toString, Files.newInputStream(p))).toOption))(
      onResult
    )

}

object GtkPickers {

  // Callbacks cannot close over state in Scala Native: the dialog's continuation waits here under the id that travels
  // as the callback's user data, as `thicket.renderer.gtk.Handles` does. At most one entry: one picker at a time.
  private val pending = new ConcurrentHashMap[java.lang.Long, (Ptr[GtkFileDialog], Ptr[GAsyncResult]) => Unit]()
  private val nextId = new AtomicLong(1L)

  private type Show = (
    Ptr[GtkFileDialog],
    Ptr[GtkWindow],
    Ptr[sn.gnome.gio.internal.GCancellable],
    GAsyncReadyCallback,
    gpointer
  ) => Unit

  /** Shows `dialog` with `show` over the app's window; `k` gets it back with the result, after which it is released.
    *
    * One picker at a time, for the photos and the documents alike, as on Android: while one is up, `dialog` is released
    * unshown and `busy` answers on the next turn of the main loop.
    */
  private def start(
    dialog: Ptr[GtkFileDialog]
  )(
    k: (Ptr[GtkFileDialog], Ptr[GAsyncResult]) => Unit
  )(
    show: Show,
    busy: () => Unit
  ): Unit =
    if !pending.isEmpty then {
      g_object_unref(dialog.asInstanceOf[gpointer])
      GtkApp.postToUi(busy)
    } else {
      val id = nextId.getAndIncrement()
      pending.put(id, k)
      show(
        dialog,
        GtkApp.windowHandle.asInstanceOf[Ptr[GtkWindow]],
        null,
        GAsyncReadyCallback.fromPtr(CFuncPtr.toPtr(finished)),
        fromRawPtr[Byte](Intrinsics.castLongToRawPtr(id)).asInstanceOf[gpointer]
      )
    }

  private val finished: CFuncPtr3[Ptr[Byte], Ptr[Byte], gpointer, Unit] =
    CFuncPtr3.fromScalaFunction {
      (
        source: Ptr[Byte],
        result: Ptr[Byte],
        data:   gpointer
      ) =>
        GcState.guarded {
          val dialog = source.asInstanceOf[Ptr[GtkFileDialog]]
          val k = pending.remove(Intrinsics.castRawPtrToLong(toRawPtr(data.asInstanceOf[Ptr[Byte]])))
          if k != null then k(dialog, result.asInstanceOf[Ptr[GAsyncResult]])
          g_object_unref(dialog.asInstanceOf[gpointer])
        }
    }

  /** A `GFile`'s local path, releasing the file; `None` for no file, or one with no local path. */
  private def pathOf(file: Ptr[GFile]): Option[Path] =
    if file == null then None
    else {
      val c = g_file_get_path(file)
      val p = if c == null then None else Some(Path.of(fromCString(c)))
      if c != null then g_free(c.asInstanceOf[gpointer])
      g_object_unref(file.asInstanceOf[gpointer])
      p
    }

  private def filesIn(list: Ptr[GListModel]): List[Path] = {
    val n = g_list_model_get_n_items(list).asInstanceOf[scala.scalanative.unsigned.UInt].toInt
    val paths = (0 until n).toList.flatMap(i =>
      pathOf(g_list_model_get_item(list, i.toUInt.asInstanceOf[guint]).asInstanceOf[Ptr[GFile]])
    )
    g_object_unref(list.asInstanceOf[gpointer])
    paths
  }

  /** Runs `work` on a thread of its own and hands its result to `deliver` on the main loop. */
  private def onWorker[A](work: => A)(deliver: A => Unit): Unit = {
    val t = Thread(() => {
      val a = work
      GtkApp.postToUi(() => deliver(a))
    })
    t.setName("thicket-pickers")
    t.start()
  }

}
