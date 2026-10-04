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

import thicket.renderer.apple.AppleApp

/** The gallery on macOS (AppKit).
  *
  * AppKit apps own their own process: `main` starts, `AppleApp.run` hands the thread to
  * `NSApp.run()` and never returns. Compare [[GalleryIos]], where the process is already
  * running by the time Scala is reached.
  *
  * Not verified on Linux — see the gallery issue on the tracker. The point of the file
  * existing now is that `galleryMacos/nativeLink` is a real target on a Mac rather than
  * something still to be written.
  */
object GalleryMac {

  def main(args: Array[String]): Unit =
    AppleApp.run("Thicket gallery", 520, 760) {
      // Same switch as the GTK host, so one command shape checks any platform.
      if sys.env.contains("THICKET_GALLERY_SELFTEST") then AppleApp.postToUi(() => GallerySelfTest.run())
      Gallery.app()
    }

}
