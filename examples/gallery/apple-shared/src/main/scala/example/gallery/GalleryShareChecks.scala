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
import thicket.clipboard.apple.AppleClipboard
import thicket.device.Availability
import thicket.renderer.apple.AppleApp
import thicket.share.apple.AppleShare

/** The Share section on AppKit and UIKit (#134). The share sheet is the system's, and neither platform says what the
  * user does with it, so these look at the sheet itself:
  *
  *   - **macOS:** `NSSharingServicePicker` is a popover whose content the sharing service draws out of process (an
  *     `NSRemoteView`). The checks find its window, read the services its delegate was offered for the item, close it
  *     and read that it closed with no choice. Nothing inside it can be clicked from the app, and nothing outside can
  *     without the Accessibility permission, so no service is driven.
  *   - **iOS:** the `UIActivityViewController` must be on top of the window. The text's item source is asked what UIKit
  *     asks it, subject included; then `[gallery] DRIVE share-copy` has the build script tap "Copy" in the sheet with
  *     an XCUITest, and read the simulator's pasteboard with `simctl pbpaste`; the app sees the sheet go and the
  *     pasteboard change.
  *   - **Both:** a file is shared as a copy under the cache directory, with its bytes and type.
  */
object GalleryShareChecks {

  type Check = (String, Boolean, String) => Unit

  private def ios: Boolean = AppleApp.renderer.platform == "uikit"

  private def drive(action: String): Unit = {
    println(s"[gallery] DRIVE $action")
    System.out.flush()
  }

  /** Re-checks `cond` every 16 ms until it holds or `timeoutMs` passes, then continues with the time it took. */
  private def eventually(timeoutMs: Long)(cond: => Boolean)(k: Long => Unit): Unit = {
    val start = System.nanoTime()
    val deadline = start + timeoutMs * 1000000L
    def loop(): Unit =
      if cond || System.nanoTime() > deadline then k((System.nanoTime() - start) / 1000000L)
      else AppleApp.postToUiAfter(16)(() => loop())
    loop()
  }

  private def up: Boolean = AppleShare.testing.showing._1

  /** What the sheet looks like to each platform: the controller's class on iOS, the popover's on macOS. */
  private def expected: String = if ios then "UIActivityViewController" else "_NSPopoverWindow/NSRemoteView"

  /** The subject the iOS check shares, and the activity it asks for it as. */
  val subject:     String = "A subject from the Thicket gallery"
  val mailType:    String = "com.apple.UIKit.activity.Mail"
  val subjectText: String = "Shared with a subject"

  def run(
    check: Check,
    click: String => Boolean,
    texts: () => List[String],
    cache: Option[Path]
  )(
    next: () => Unit
  ): Unit =
    (Gallery.currentShare, cache) match {
      case (Some(_), Some(dir)) =>
        val available = ShareDemo.availabilityLabel(Availability.Available)
        check(
          "the Apple host provides share, available",
          texts().contains(available),
          texts().filter(_.startsWith("Share")).toString
        )
        textSteps(check, click, texts) { () =>
          fileSteps(check, click, texts, dir) { () =>
            // The subject: only iOS takes one.
            if ios then subjectSteps(check, dir)(next) else next()
          }
        }
      case (share, cache) =>
        check("the Apple host provides share, and storage for its copies", false, s"share $share; cache $cache")
        next()
    }

  /** Clicks `button`, waits for the sheet, and checks it is up and the section says it was handed over. */
  private def show(
    check:  Check,
    click:  String => Boolean,
    texts:  () => List[String],
    button: String,
    status: String
  )(
    k: () => Unit
  ): Unit = {
    val before = AppleShare.testing.shownCount
    val clicked = click(button)
    eventually(5000)(up) { ms =>
      val (shown, what) = AppleShare.testing.showing
      println(s"[gallery] measured: \"$button\" -> the share sheet up in $ms ms ($what)")
      check(
        s"\"$button\": the system's share sheet appears ($expected)",
        clicked && shown && what == expected && AppleShare.testing.shownCount == before + 1 &&
          texts().contains(status),
        s"clicked $clicked; showing $shown ($what); shown ${AppleShare.testing.shownCount} after $before; " +
          texts().filter(_.startsWith("Share:"))
      )
      if !ios then {
        val (services, _) = AppleShare.testing.proposed
        println(s"[gallery] measured: the picker was offered ${services.size} services: ${services.mkString(", ")}")
        check("the picker was offered services for it", services.nonEmpty, services.toString)
      }
      k()
    }
  }

  /** Takes the sheet down from the app and checks it goes; on macOS the picker's delegate must hear it closed with no
    * choice.
    */
  private def dismiss(
    check: Check,
    what:  String
  )(
    k: () => Unit
  ): Unit = {
    val dismissed = AppleShare.testing.dismiss()
    eventually(5000)(!up) { ms =>
      val (_, outcome) = AppleShare.testing.proposed
      println(s"[gallery] measured: the $what's sheet down in $ms ms after dismissing")
      check(
        s"the $what's sheet goes when dismissed" + (if ios then "" else ", and the picker hears no choice"),
        dismissed && !up && (ios || outcome.contains(false)),
        s"dismissed $dismissed; showing ${AppleShare.testing.showing}; outcome $outcome"
      )
      k()
    }
  }

  private def textSteps(
    check: Check,
    click: String => Boolean,
    texts: () => List[String]
  )(
    k: () => Unit
  ): Unit =
    show(check, click, texts, "Share text", ShareDemo.outcome("text", scala.util.Success(()))) { () =>
      if !ios then dismiss(check, "text")(k)
      else {
        val asked = AppleShare.testing.ask(mailType)
        check(
          "the text's item source answers UIKit with the text, and no subject",
          asked.contains((ShareDemo.text, "")),
          asked.toString
        )
        // A user's choice: the build script taps "Copy" in the sheet through XCUITest, then reads the pasteboard
        // with simctl pbpaste, outside the app. Here: the sheet goes, and something wrote the pasteboard.
        val count = AppleClipboard.changeCount
        drive(s"share-copy ${ShareDemo.text}")
        eventually(90000)(!up && AppleClipboard.changeCount > count) { ms =>
          println(s"[gallery] measured: \"Copy\" -> the sheet down and the pasteboard written $ms ms after DRIVE")
          check(
            "\"Copy\" tapped in the share sheet: it goes, and the pasteboard changes",
            !up && AppleClipboard.changeCount > count,
            s"showing ${AppleShare.testing.showing}; change count $count -> ${AppleClipboard.changeCount}"
          )
          k()
        }
      }
    }

  /** "Share a file": the sheet, and the copy it was given, under `<cache>/thicket-shared` with the bytes and the type.
    */
  private def fileSteps(
    check: Check,
    click: String => Boolean,
    texts: () => List[String],
    cache: Path
  )(
    k: () => Unit
  ): Unit =
    show(check, click, texts, "Share a file", ShareDemo.outcome("file", scala.util.Success(()))) { () =>
      val dir = newest(cache.resolve(AppleShare.SharedDir))
      def read(p: Path) = new String(Files.readAllBytes(p), "UTF-8")
      val bytes = dir.map(_.resolve(ShareDemo.fileName)).filter(Files.isRegularFile(_)).map(read)
      val kind = dir.map(_.resolve(".type")).filter(Files.isRegularFile(_)).map(read)
      check(
        "the file is shared as a copy under <cache>/thicket-shared, with its bytes and type",
        bytes.contains(ShareDemo.fileText) && kind.contains("text/plain"),
        s"copy in $dir; bytes $bytes; type $kind"
      )
      dismiss(check, "file")(k)
    }

  /** The newest share's directory under `shared`. */
  private def newest(shared: Path): Option[Path] =
    if !Files.isDirectory(shared) then None
    else {
      val s = Files.list(shared)
      try s.toArray.toList.map(_.asInstanceOf[Path]).sortBy(p => Files.getLastModifiedTime(p).toMillis).lastOption
      finally s.close()
    }

  /** iOS: a text with a subject; its item source must give Mail that subject. */
  private def subjectSteps(
    check: Check,
    cache: Path
  )(
    next: () => Unit
  ): Unit = {
    AppleShare(cache).text(subjectText, Some(subject))
    eventually(5000)(up) { _ =>
      val asked = AppleShare.testing.ask(mailType)
      check(
        "with a subject, the item source gives Mail the text and the subject",
        asked.contains((subjectText, subject)),
        asked.toString
      )
      dismiss(check, "subject")(next)
    }
  }

}
