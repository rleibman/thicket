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
import scala.scalanative.posix.dlfcn
import sn.gnome.gtk4.internal.*
import sn.gnome.gobject.internal.{g_type_name_from_instance, GTypeInstance}

/** Works around a race in GTK's Wayland input method (#56), in the renderer, so an app cannot hit it.
  *
  * GTK creates its Wayland input-method state on the first text-input focus, and only gets the `text_input` it needs to
  * *clear* its "current" context from a registry reply that arrives on a later main-loop turn. Until then
  * `focus_out` does nothing. So if the first text widget ever focused is destroyed in the same turn — an autofocused
  * form dismissed at once, a sheet opened and closed in one callback — GTK keeps a pointer to a freed context, and the
  * next `zwp_text_input_v3.enter` (the window regaining keyboard focus) crashes on it.
  *
  * Before a focused widget is destroyed, this does one `wl_display_roundtrip`, which delivers the pending registry
  * reply now, and then takes the focus off it, so GTK's own focus-out and unrealize release the context normally. The
  * roundtrip dispatches only GDK's own pending Wayland events, which queue work rather than run widget code, and it
  * happens only when a widget holding the focus is destroyed.
  *
  * The three functions are looked up at runtime rather than linked: they are in libraries GTK already loaded, linking
  * them would put `wayland-client` on every app's link line, and a GTK built without Wayland simply has none of this to
  * work around.
  */
private[gtk] object WaylandFocus {

  private type Roundtrip = CFuncPtr1[Ptr[Byte], CInt]
  private type WlDisplay = CFuncPtr1[Ptr[Byte], Ptr[Byte]]

  private lazy val functions: Option[(WlDisplay, Roundtrip)] = Zone {
    val self = dlfcn.dlopen(null, dlfcn.RTLD_LAZY)
    def sym(name: String): Option[Ptr[Byte]] =
      Option(dlfcn.dlsym(self, toCString(name)).asInstanceOf[Ptr[Byte]]).filter(_ != null)
    for {
      wl <- sym("gdk_wayland_display_get_wl_display")
      rt <- sym("wl_display_roundtrip")
    } yield (CFuncPtr.fromPtr[WlDisplay](wl), CFuncPtr.fromPtr[Roundtrip](rt))
  }

  /** Take the keyboard focus off `widget` and anything inside it, after letting GTK's input method catch up. */
  def releaseBeforeDestroy(widget: Ptr[GtkWidget]): Unit = {
    val root = gtk_widget_get_root(widget)
    if root != null then {
      val focus = gtk_root_get_focus(root)
      if focus != null && (focus == widget || gtk_widget_is_ancestor(focus, widget).asInstanceOf[CInt] != 0) then {
        settle(widget)
        gtk_root_set_focus(root, null)
      }
    }
  }

  private def settle(widget: Ptr[GtkWidget]): Unit = {
    val display = gtk_widget_get_display(widget)
    val name    = g_type_name_from_instance(display.asInstanceOf[Ptr[GTypeInstance]])
    // Only on Wayland: on X11 there is no such reply to wait for, and the GDK function
    // below would refuse a non-Wayland display with a critical.
    if name != null && fromCString(name.asInstanceOf[CString]) == "GdkWaylandDisplay" then
      functions.foreach { (wlDisplay, roundtrip) =>
        val wl = wlDisplay(display.asInstanceOf[Ptr[Byte]])
        if wl != null then { val _ = roundtrip(wl) }
      }
  }

}
