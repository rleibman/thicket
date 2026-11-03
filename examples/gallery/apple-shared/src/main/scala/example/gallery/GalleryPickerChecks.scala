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

import java.nio.file.{Files, Path}
import thicket.pickers.apple.ApplePickers
import thicket.renderer.apple.AppleApp

/** The Pickers section on AppKit and UIKit (#125), the GTK host's `pickerChecks` asked of Apple. Each pick must come
  * back as a copy in the app's cache holding the original's bytes, a cancel must answer nothing, and a request while a
  * picker is up must answer empty without showing a second one.
  *
  *   - **iOS:** the pickers are other processes' (PhotosUI's and the document browser's), so, as on Android, the build
  *     script is the user: `[gallery] DRIVE pick-photo`, `cancel-photo`, `pick-document` and `save-document` run the
  *     XCUITest tapper, which taps the photo `build-app.sh` put in the library, the text file it put in Files' "On My
  *     iPhone", and Save there, and then reads the saved file back on the host (a mismatch fails the DRIVE, which fails
  *     the run).
  *   - **macOS 27:** `NSOpenPanel` and `NSSavePanel` run in `com.apple.appkit.xpc.openAndSavePanelService` even for an
  *     unsandboxed app: the panel's content is an `NSRemoteView`, `-[NSSavePanel ok:]` raises "not implemented", and
  *     neither key events nor accessibility reach the service's buttons from this process (measured, #125). So the
  *     sheet, its configuration and its own `cancel(_:)` are the real panel's; an accept is
  *     `ApplePickers.testing.choose`, which closes the panel and answers through its own completion with a known file.
  *     The panel's OK button is not exercised.
  */
object GalleryPickerChecks {

  type Check = (String, Boolean, String) => Unit

  private def eventually(timeoutMs: Long)(cond: => Boolean)(k: Long => Unit): Unit = {
    val start = System.nanoTime()
    val deadline = start + timeoutMs * 1000000L
    def loop(): Unit =
      if cond || System.nanoTime() > deadline then k((System.nanoTime() - start) / 1000000L)
      else AppleApp.postToUiAfter(16)(() => loop())
    loop()
  }

  private def drive(action: String): Unit = println(s"[gallery] DRIVE $action")

  /** What `build-app.sh` puts in Files for the iOS document pick, and what the macOS check writes. */
  val testDocument: String = "a test document\n"

  /** `press`: clicks a button by its label. `cache`: the app's cache directory. `logo`: the file the photo pick must
    * hand back a copy of. `ios`: the pickers are driven from outside (DRIVE lines) rather than answered in-process.
    */
  def run(
    check:   Check,
    press:   String => Boolean,
    pickers: ApplePickers,
    cache:   Path,
    logo:    Path,
    ios:     Boolean
  )(
    next: () => Unit
  ): Unit =
    Gallery.currentPickers match {
      case None =>
        check("the Apple host provides the pickers", false, "Gallery.currentPickers is None")
        next()
      case Some(demo) =>
        val t = ApplePickers.testing
        def same(
          a: Path,
          b: Array[Byte]
        ) = Files.readAllBytes(a).sameElements(b)
        def inCache(ps: Seq[Path]) = ps.nonEmpty && ps.forall(_.toAbsolutePath.startsWith(cache.toAbsolutePath))
        val logoBytes = Files.readAllBytes(logo)
        val dir = Files.createTempDirectory("thicket-gallery-pickers")
        val docFile = dir.resolve("thicket-test.txt")
        Files.write(docFile, testDocument.getBytes("UTF-8"))
        val saveTo = dir.resolve(PickersDemo.savedName)

        // Up: the platform holds the picker `expected` describes. Then `accept` (a DRIVE line, or the panel answered).
        def whenUp(
          what:     String,
          expected: String
        )(
          andThen: => Unit
        ): Unit =
          eventually(10000)(t.describe.nonEmpty) { ms =>
            val d = t.describe
            println(s"[gallery] measured: $what up in $ms ms: \"$d\"")
            check(s"$what is up, as asked: $expected", d.startsWith(expected), d)
            andThen
          }

        val shownBefore = t.shown
        val pressed = press("Pick a photo")
        whenUp("the photo picker", if ios then "photos limit=1" else "open types=public.image multiple=0") {
          if !ios then check("the panel is a sheet on the gallery's window", t.describe.contains("sheet=1"), t.describe)
          // While it is up, a second request answers empty, and no second picker is shown.
          var second: Option[Seq[Path]] = None
          pickers.documents.open(Nil)(r => second = Some(r))
          eventually(5000)(second.isDefined) { _ =>
            check(
              "a picker asked for while one is up answers empty, without a second one",
              pressed && second.contains(Nil) && t.shown == shownBefore + 1,
              s"pressed $pressed; answered $second; ${t.shown - shownBefore} shown"
            )
            val started = System.nanoTime()
            if ios then drive("pick-photo") else { val _ = t.choose(List(logo)) }
            eventually(90000)(demo.photo.now != PickersDemo.nothing("Photo")) { _ =>
              val ms = (System.nanoTime() - started) / 1000000L
              val got = demo.lastPhotos
              println(s"[gallery] measured: photo copied into the cache $ms ms after the answer: $got")
              check(
                "the photo picker hands back the chosen photo, copied into the app's cache, byte for byte",
                got.size == 1 && inCache(got) && got.forall(same(_, logoBytes)) &&
                  demo.photo.now == PickersDemo.pickedLabel("Photo", got),
                s"${demo.photo.now}; $got; cache $cache"
              )
              cancelPhoto()
            }
          }
        }

        def cancelPhoto(): Unit = {
          val _ = press("Pick a photo")
          whenUp("the photo picker, again", if ios then "photos" else "open") {
            if ios then drive("cancel-photo") else { val _ = t.cancel() }
            eventually(60000)(demo.photo.now == "Photo: cancelled") { _ =>
              check("cancelling the photo picker answers nothing", demo.photo.now == "Photo: cancelled", demo.photo.now)
              openDocument()
            }
          }
        }

        def openDocument(): Unit = {
          val _ = press("Open a document")
          whenUp(
            "the document picker",
            if ios then "documents types=public.plain-text multiple=0" else "open types=public.plain-text multiple=0"
          ) {
            if ios then drive("pick-document") else { val _ = t.choose(List(docFile)) }
            eventually(90000)(demo.document.now != PickersDemo.nothing("Document")) { _ =>
              val docs = demo.lastDocuments
              check(
                "the document picker hands back the chosen file, copied into the app's cache, byte for byte",
                docs.size == 1 && inCache(docs) && docs.forall(same(_, testDocument.getBytes("UTF-8"))) &&
                  demo.document.now == "Document: thicket-test.txt (16 bytes)",
                s"${demo.document.now}; $docs"
              )
              saveDocument()
            }
          }
        }

        def saveDocument(): Unit = {
          val _ = press("Save a document")
          whenUp(
            "the save dialog",
            if ios then s"export name=${PickersDemo.savedName}" else "save types=public.plain-text"
          ) {
            if !ios then
              check(
                "the save panel suggests the document's name",
                t.describe.contains(s"name=${PickersDemo.savedName}") && t.describe.contains("sheet=1"),
                t.describe
              )
            if ios then drive("save-document") else { val _ = t.choose(List(saveTo)) }
            eventually(90000)(demo.saved.now != PickersDemo.notSaved) { _ =>
              // On iOS the host reads the file back from Files after its DRIVE: a mismatch fails the run there.
              val written =
                ios || (Files
                  .exists(saveTo) && new String(Files.readAllBytes(saveTo), "UTF-8") == PickersDemo.savedText)
              check(
                "saving a document answers its name" + (if ios then "" else ", and writes where the panel was told"),
                demo.saved.now == PickersDemo.savedLabel(Some(PickersDemo.savedName)) && written,
                s"${demo.saved.now}; written $written"
              )
              val staged = Files.exists(cache.resolve("thicket-save")) && {
                val s = Files.list(cache.resolve("thicket-save"))
                try s.findAny().isPresent
                finally s.close()
              }
              check("the file staged for the save is gone from the cache", !staged, s"staged $staged")
              check("no picker is left up", t.describe.isEmpty, t.describe)
              next()
            }
          }
        }
    }

}
