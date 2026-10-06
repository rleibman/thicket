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

import scala.scalanative.unsafe.*
import sn.gnome.gtk4.internal.*
import sn.gnome.gobject.internal.g_object_unref
import sn.gnome.gio.internal.{GAsyncReadyCallback, GAsyncResult}
import sn.gnome.glib.internal.{g_error_free, gpointer, GError}

/** Opens a URL the way the desktop does: `GtkUriLauncher`, which hands it to the portal or to `xdg-open`'s registered
  * handler — the default browser for `https:`, the mail client for `mailto:`.
  *
  * Replaceable, for one reason: a self-test that clicks a link must not launch a browser on the machine it runs on.
  * [[GtkInspect.interceptUrls]] swaps the opener for one that records instead; nothing else should.
  */
object UrlOpener {

  @volatile private var opener: (String, Ptr[GtkWidget]) => Unit = launch

  def open(url: String, from: Ptr[GtkWidget]): Unit = opener(url, from)

  private[gtk] def replace(f: (String, Ptr[GtkWidget]) => Unit): Unit = opener = f

  /** The widget's window, so the portal can place any "open with" chooser over the right window and associate the
    * request with this app. A widget not yet in a window passes none, which the launcher accepts.
    */
  private def launch(url: String, from: Ptr[GtkWidget]): Unit = {
    val launcher = Zone(gtk_uri_launcher_new(toCString(url)))
    val root     = gtk_widget_get_root(from)
    gtk_uri_launcher_launch(
      launcher,
      root.asInstanceOf[Ptr[GtkWindow]],
      null,
      GAsyncReadyCallback.fromPtr(CFuncPtr.toPtr(finished)),
      null.asInstanceOf[gpointer]
    )
  }

  /** The launch is asynchronous, and its result is only an error worth reporting: there is no app to tell, because a
    * link that fails to open has nothing the app could do differently. So it is printed, and the launcher — the
    * callback's source, which the operation kept alive until now — is released here rather than leaked.
    */
  private val finished: CFuncPtr3[Ptr[Byte], Ptr[Byte], gpointer, Unit] =
    CFuncPtr3.fromScalaFunction { (source: Ptr[Byte], result: Ptr[Byte], _: gpointer) =>
      GcState.guarded {
        val launcher = source.asInstanceOf[Ptr[GtkUriLauncher]]
        val err      = stackalloc[Ptr[GError]]()
        !err = null
        val ok = gtk_uri_launcher_launch_finish(launcher, result.asInstanceOf[Ptr[GAsyncResult]], err)
        if ok.asInstanceOf[CInt] == 0 then
          System.err.println(
            s"thicket: could not open ${fromCString(gtk_uri_launcher_get_uri(launcher))}" +
              (if !err != null then s": ${fromCString((!(!err)).message.asInstanceOf[CString])}" else "")
          )
        if !err != null then g_error_free(!err)
        g_object_unref(launcher.asInstanceOf[gpointer])
      }
    }

}
