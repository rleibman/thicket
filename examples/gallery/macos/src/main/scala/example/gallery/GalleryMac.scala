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
import thicket.sqlite.NativeSqlite
import thicket.storage.apple.AppleStorage

/** The gallery on macOS (AppKit).
  *
  * AppKit apps own their own process: `main` starts, `AppleApp.run` hands the thread to `NSApp.run()` and never
  * returns. Compare [[GalleryIos]], where the process is already running by the time Scala is reached.
  *
  * Built and self-tested on the Mac by `bin/verify-apple.sh`, which `.github/workflows/apple.yml` runs on every push.
  * Its Scala type-checks on Linux too (`sbt platforms/Test/compile`); only linking needs Xcode.
  */
object GalleryMac {

  /** Not read from the bundle: this executable is run outside an `.app`, so it has none. */
  val bundleId: String = "dev.thicket.gallery"

  def main(args: Array[String]): Unit =
    AppleApp.run("Thicket gallery", 520, 760) {
      // Same switch as the GTK host, so one command shape checks any platform.
      val storage = AppleStorage(bundleId)
      if sys.env.contains("THICKET_GALLERY_SELFTEST") then
        AppleApp.postToUi(() => GallerySelfTest.run(Some(storage), bundleId, svgWhereDecoded = true))
      val services = Some(Services(storage, NativeSqlite.driver))
      // The SVG where NSImage draws it - macOS 14 and later - and the PNG made from it otherwise
      // (#85). Asked of the decoder, not the OS version: on macOS 13, the shim's minimum, NSImage
      // is not reported to take SVG, and an image view given bytes it cannot decode is an empty
      // frame, with nothing beside it to fall back to.
      val svg = Gallery.logoBytes(Gallery.logoSvg)
      if AppleApp.decodesImage(svg) then Gallery.app(Gallery.logoSvg, svg, services)
      else Gallery.app(services = services)
    }

}
