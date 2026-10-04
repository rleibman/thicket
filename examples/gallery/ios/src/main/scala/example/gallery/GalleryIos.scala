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

import scala.scalanative.unsafe.exported
import thicket.renderer.apple.{AppleApp, GcState}

/** The gallery on iOS (UIKit) — the same tree, the same renderer, a different entry shape.
  *
  * No `main`: iOS refuses to launch an app that does not adopt the UIScene lifecycle (S3),
  * and a scene delegate cannot live in the static library Scala Native produces, so the
  * entry point belongs to the Swift host. The host creates the window, hands its root view
  * to the shim and calls this, on the main thread — the only non-Scala thread allowed to
  * enter Scala (S1).
  */
object GalleryIos {

  /** Called by the Swift host. `guarded` because this is a host -> Scala entry and the main
    * thread is Unmanaged while the run loop owns it (S3).
    */
  @exported("thicket_gallery_start")
  def thicket_gallery_start(): Unit =
    GcState.guarded {
      // The root view is not a parameter: the host has already given it to the shim with
      // `sui_set_root_view`, and the renderer reads it back through `sui_root_view()`.
      // Width and height are ignored on iOS - the window is the screen - but are passed
      // anyway so the two Apple hosts differ in nothing but their entry shape.
      AppleApp.run("Thicket gallery", 520, 760) {
        if sys.env.contains("THICKET_GALLERY_SELFTEST") then AppleApp.postToUi(() => GallerySelfTest.run())
        Gallery.app()
      }
    }

}
