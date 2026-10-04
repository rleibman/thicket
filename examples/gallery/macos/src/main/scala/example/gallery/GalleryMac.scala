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
