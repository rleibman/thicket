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

package example.gallery

import java.nio.file.Path
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.UnsignedRichInt
import sn.gnome.gio.internal.{g_file_new_for_path, g_list_model_get_item, g_list_model_get_n_items}
import sn.gnome.glib.internal.{gpointer, guint}
import sn.gnome.gobject.internal.{GTypeInstance, g_object_unref, g_type_name_from_instance}
import sn.gnome.gtk4.internal.*

/** Answers GTK's file chooser as a user would, for the gallery's self-test: finds it among the app's windows, sets a
  * file on it, and accepts or cancels. It is in-process only when the portal is off (`GDK_DEBUG=no-portals`), which
  * `GalleryGtk.main` sets for the self-test.
  */
private[gallery] object PickerDriver {

  /** Makes GTK's file chooser in-process: `GDK_DEBUG=no-portals`, before GTK starts, which reads it once. */
  def withoutPortal(): Unit = {
    val _ = scala.scalanative.posix.stdlib.setenv(c"GDK_DEBUG", c"no-portals", 1)
  }

  /** The open file chooser, if one is up. */
  def chooser: Option[Ptr[GtkWidget]] = all.headOption

  /** How many file choosers are up. */
  def choosers: Int = all.size

  private def all: List[Ptr[GtkWidget]] = {
    val windows = gtk_window_get_toplevels()
    val n = g_list_model_get_n_items(windows).asInstanceOf[scala.scalanative.unsigned.UInt].toInt
    (0 until n).iterator
      .map(i => g_list_model_get_item(windows, i.toUInt.asInstanceOf[guint]))
      .flatMap { item =>
        val name = fromCString(g_type_name_from_instance(item.asInstanceOf[Ptr[GTypeInstance]]).asInstanceOf[CString])
        val w = item.asInstanceOf[Ptr[GtkWidget]]
        g_object_unref(item)
        Option.when(name == "GtkFileChooserDialog" && gtk_widget_get_visible(w).asInstanceOf[CInt] != 0)(w)
      }
      .toList
  }

  /** Sets `file` on the chooser and accepts, or cancels when there is none. */
  def respond(
    dialog: Ptr[GtkWidget],
    file:   Option[Path]
  ): Unit =
    file match {
      case Some(path) =>
        val f = Zone(g_file_new_for_path(toCString(path.toString)))
        val _ = gtk_file_chooser_set_file(dialog.asInstanceOf[Ptr[GtkFileChooser]], f, null)
        g_object_unref(f.asInstanceOf[gpointer])
        gtk_dialog_response(dialog.asInstanceOf[Ptr[GtkDialog]], GtkResponseAccept)
      case None => gtk_dialog_response(dialog.asInstanceOf[Ptr[GtkDialog]], GtkResponseCancel)
    }

  // GtkResponseType's GTK_RESPONSE_ACCEPT and GTK_RESPONSE_CANCEL.
  private val GtkResponseAccept = -3
  private val GtkResponseCancel = -6

}
