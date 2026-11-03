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

import scala.collection.mutable
import scala.scalanative.unsafe.*
import sn.gnome.gtk4.internal.*
import thicket.renderer.PlatformView

/** A [[PlatformView]] that GTK can show (`docs/17` §17.3): the module that provides the view makes its `GtkWidget`.
  *
  * The renderer parents the widget, sizes it with the layout and frees it with its owner. [[release]] is called just
  * before that, while the widget is still alive, for what the widget does not own itself (a pipeline, a file); it must
  * not unref or unparent the widget.
  */
trait GtkPlatformView extends PlatformView {

  /** A new, unparented widget. Called once per mount, and again if the view is replaced. */
  def create(): Ptr[GtkWidget]

  /** The widget [[create]] made is about to be freed. */
  def release(widget: Ptr[GtkWidget]): Unit = ()

}

/** `WidgetKind.Platform` on GTK: the handle is a box holding the view's one widget, so a changed view replaces the
  * child and the handle stays. A view that is not a [[GtkPlatformView]] gets a label saying it is not available here.
  */
final private[gtk] class GtkPlatformViews(
  /** For each Platform box: its view, and the widget made for it. One of the renderer's per-handle tables, so the
    * self-tests' table count sees an entry left behind.
    */
  shown: mutable.Map[Ptr[GtkWidget], (PlatformView, Ptr[GtkWidget])]
) {

  def create(): Ptr[GtkWidget] = gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)

  def setView(
    box:  Ptr[GtkWidget],
    view: PlatformView
  ): Unit =
    if !shown.get(box).exists(_._1 eq view) then {
      clear(box)
      val child = view match {
        case g: GtkPlatformView => g.create()
        case other => Zone(gtk_label_new(toCString(GtkPlatformViews.unavailable(other))))
      }
      // The box is as large as the layout makes it; the view fills it.
      gtk_widget_set_hexpand(child, Handles.gbool(true))
      gtk_widget_set_vexpand(child, Handles.gbool(true))
      gtk_box_append(box.asInstanceOf[Ptr[GtkBox]], child)
      shown(box) = (view, child)
    }

  /** Called by `destroy` before the box is unparented, which frees the child with it. */
  def destroy(box: Ptr[GtkWidget]): Unit = clear(box)

  private def clear(box: Ptr[GtkWidget]): Unit =
    shown.remove(box).foreach {
      (
        view,
        child
      ) =>
        view match {
          case g: GtkPlatformView => g.release(child)
          case _ => ()
        }
        // Removing drops the box's reference, the only one, which frees the child.
        gtk_box_remove(box.asInstanceOf[Ptr[GtkBox]], child)
    }

}

private[gtk] object GtkPlatformViews {

  /** What a Platform widget shows for a view GTK has nothing for. */
  def unavailable(view: PlatformView): String = s"${view.description}: not available on GTK"

}
