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

package thicket.renderer.apple

import scala.collection.mutable
import scala.scalanative.unsafe.*
import thicket.renderer.PlatformView

/** A [[PlatformView]] that AppKit and UIKit can show (`docs/17` §17.3, #145): the module that provides the view makes
  * its `NSView` or `UIView`, usually in a Swift library of its own, and hands it over as a pointer.
  *
  * **Ownership, exactly.** [[create]] returns an `NSView *` / `UIView *` that is in no superview and carries one retain
  * the caller owns — `Unmanaged.passRetained(v).toOpaque()` in Swift, the result of `alloc`/`init` in C. The renderer
  * hands that retain to the shim (`sui_platform_set_view`), which holds the view as its Platform host's only subview,
  * pinned to the host's edges. From then on the shim owns it: the module must **not** release that retain, neither in
  * [[release]] nor later. It may keep an unretained or weak reference of its own, valid until the shim drops the view,
  * or a strong one it balances itself.
  *
  * [[release]] is called when the view is about to go — the Platform widget is destroyed, or given another view — while
  * the view is still alive and still in its host; the shim removes it and drops its reference straight after. That is
  * where a module stops what the view does not own itself (an `AVCaptureSession`, a timer). Called once for each view
  * [[create]] made, on the main thread, as [[create]] is.
  *
  * A `null` from [[create]] means the platform cannot make the view: the host shows the view's description and "not
  * available", as for a view that is not an `ApplePlatformView` at all, and [[release]] is not called. One trait for
  * both toolkits, as one `AppleRenderer` serves both: a module built for both makes an `NSView` under AppKit and a
  * `UIView` under UIKit (`AppleRenderer.platform` says which).
  */
trait ApplePlatformView extends PlatformView {

  /** A new `NSView *` or `UIView *`, +1 retained, in no superview. The shim takes the retain over. */
  def create(): Ptr[Byte]

  /** The view [[create]] made is about to be removed from its host and released by the shim; it is still alive. */
  def release(view: Ptr[Byte]): Unit = ()

}

/** `WidgetKind.Platform` on Apple: the handle is a `PlatformHost` (kind 28) holding the view's one view, so a changed
  * view replaces the child and the handle stays. A view that is not an [[ApplePlatformView]], or whose `create`
  * returned `null`, gets a label of the host's own saying it is not available here.
  */
final private[apple] class ApplePlatformViews(
  /** For each Platform host: its view, and the native view made for it (`null` for the label). One of the renderer's
    * per-handle tables, so the self-tests' table count sees an entry left behind.
    */
  shown:   mutable.Map[Shim.Handle, (PlatformView, Ptr[Byte])],
  toolkit: () => String
) {

  def setView(
    host: Shim.Handle,
    view: PlatformView
  ): Unit =
    if !shown.get(host).exists(_._1 eq view) then {
      clear(host)
      val made = view match {
        case a: ApplePlatformView => a.create()
        case _ => null
      }
      if made != null then Shim.sui_platform_set_view(host, made)
      else Zone(Shim.sui_set_text(host, toCString(ApplePlatformViews.unavailable(view, toolkit()))))
      shown(host) = (view, made)
    }

  /** Called by `destroy` before the host is destroyed, so the view is released while it is still alive. */
  def destroy(host: Shim.Handle): Unit = clear(host)

  private def clear(host: Shim.Handle): Unit =
    shown.remove(host).foreach {
      (
        view,
        made
      ) =>
        if made != null then {
          view match {
            case a: ApplePlatformView => a.release(made)
            case _ => ()
          }
          // Removes it from the host and drops the shim's reference, the only one it had.
          Shim.sui_platform_set_view(host, null)
        }
    }

}

private[apple] object ApplePlatformViews {

  /** What a Platform widget shows for a view this toolkit has nothing for. */
  def unavailable(
    view:    PlatformView,
    toolkit: String
  ): String = s"${view.description}: not available on $toolkit"

}
