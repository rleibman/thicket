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

package thicket.renderer.android

import android.content.Context
import android.view.{View, ViewGroup}
import android.widget.{FrameLayout, TextView}
import scala.collection.mutable
import thicket.renderer.PlatformView

/** A [[PlatformView]] that Android can show (`docs/17` §17.3): the module that provides the view makes its `View`.
  *
  * The renderer adds the view to the layout, sizes it with the layout and removes it with its owner. [[release]] is
  * called just before the view is removed, for what the view does not own itself (a camera session, a listener).
  */
trait AndroidPlatformView extends PlatformView {

  /** A new view with no parent. Called once per mount, and again if the view is replaced. */
  def create(context: Context): View

  /** The view [[create]] made is about to be removed. */
  def release(view: View): Unit = ()

}

/** `WidgetKind.Platform` on Android: the handle is a `FrameLayout` holding the view, so a changed view replaces the
  * child and the handle stays. A view that is not an [[AndroidPlatformView]] gets a label saying it is not available.
  */
final private[android] class AndroidPlatformViews(
  context: Context,
  /** For each Platform frame: its view, and the Android view made for it. One of the renderer's per-view tables, so it
    * is swept in `destroy` and counted by the self-tests: an entry left here would keep the Activity alive.
    */
  shown: mutable.Map[View, (PlatformView, View)]
) {

  def create(): View = FrameLayout(context)

  def setView(
    frame: View,
    view:  PlatformView
  ): Unit =
    if !shown.get(frame).exists(_._1 eq view) then {
      clear(frame)
      val child = view match {
        case a: AndroidPlatformView => a.create(context)
        case other =>
          val t = TextView(context)
          t.setText(AndroidPlatformViews.unavailable(other))
          t
      }
      // The frame is as large as the layout makes it; the view fills it.
      frame
        .asInstanceOf[FrameLayout].addView(
          child,
          FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
      shown(frame) = (view, child)
    }

  def destroy(frame: View): Unit = clear(frame)

  private def clear(frame: View): Unit =
    shown.remove(frame).foreach {
      (
        view,
        child
      ) =>
        view match {
          case a: AndroidPlatformView => a.release(child)
          case _ => ()
        }
        frame.asInstanceOf[FrameLayout].removeView(child)
    }

}

private[android] object AndroidPlatformViews {

  /** What a Platform widget shows for a view Android has nothing for. */
  def unavailable(view: PlatformView): String = s"${view.description}: not available on Android"

}
