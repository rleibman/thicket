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

package thicket.camera.preview

import java.nio.file.Path
import thicket.core.Element
import thicket.core.dsl.Platform
import thicket.device.Availability
import thicket.renderer.{ContentFit, PlatformView}
import thicket.signals.{Signal, Var}

/** Which camera a [[CameraPreview]] shows. On a desktop `Front` is the first camera and `Back` is none (`docs/17`
  * §17.4).
  */
enum Facing {

  case Front, Back

}

/** A live camera preview in the app's own layout, with a still capture from it (`docs/17`, the owner's decision
  * 2026-11-01). `Camera.capturePhoto` hands the user to the camera app; this keeps them in the app.
  *
  * A handle, made by the platform module (`thicket-camera-preview-android`, …) and kept by the app: its widget is
  * [[view]], placed like any element. The camera runs only while that widget is mounted **and** the app is in front,
  * and is released otherwise, as every platform requires (§17.5). One preview at a time per app.
  *
  * {{{
  * Column()(
  *   preview.view(),
  *   Button("Take")(preview.capture(photo => ...)),
  *   Button("Flip")(preview.facing.update(f => if f == Facing.Back then Facing.Front else Facing.Back))
  * )
  * }}}
  */
trait CameraPreview {

  /** Whether the preview can run here, for the camera [[facing]] asks for: `NeedsPermission(Camera)` until the app has
    * it (asked through `thicket-device`'s `Permissions`), `Unavailable` with no such camera or while another preview
    * holds it.
    */
  def availability: Availability

  /** The camera shown. Setting it closes the one running and opens the other. */
  def facing: Var[Facing]

  /** True while frames are being shown: mounted, in front, permitted and the camera open. */
  def running: Signal[Boolean]

  /** The native view, for [[thicket.core.dsl.Platform]]. [[view]] is the usual way to place it. */
  def platformView(fit: ContentFit): PlatformView

  /** The preview as an element. `Cover` fills the widget and crops the edges, as a camera app does; `Contain` shows the
    * whole frame; `Fill` stretches it. Where the layout leaves its height open, the widget takes the frame's aspect.
    */
  final def view(fit: ContentFit = ContentFit.Cover): Element = Platform(platformView(fit))

  /** A still photo from the running preview, a JPEG in the app's cache, as `Camera.capturePhoto`'s: `None` when the
    * preview is not running or a capture is already under way. Called back on the UI thread.
    */
  def capture(onResult: Option[Path] => Unit): Unit

}

object CameraPreview {

  /** A preview that never runs, for `reason`: GTK until its own (§17.8.2), and the Apple platforms until theirs. Its
    * widget shows the reason.
    */
  def unavailable(reason: String): CameraPreview =
    new CameraPreview {

      def availability: Availability = Availability.Unavailable(reason)

      val facing: Var[Facing] = Var(Facing.Back)

      val running: Signal[Boolean] = Var(false)

      def platformView(fit: ContentFit): PlatformView =
        new PlatformView {
          val description = s"Camera preview ($reason)"
        }

      def capture(onResult: Option[Path] => Unit): Unit = onResult(None)

    }

}
