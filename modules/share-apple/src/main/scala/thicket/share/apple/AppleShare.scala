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

package thicket.share.apple

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import scala.scalanative.unsafe.*
import thicket.device.Availability
import thicket.share.{Share, SharedFiles}

/** The system share sheet (#134): `UIActivityViewController` on iOS, presented over whatever is on top of the key
  * window; `NSSharingServicePicker` on macOS, a popover below the key window's content.
  *
  *   - **Text** on iOS goes through a `UIActivityItemSource`, so `subject` reaches the apps that take one (Mail). macOS
  *     has no subject for a plain text, and drops it.
  *   - **A file** is copied first into `<cache>/thicket-shared`, through [[thicket.share.SharedFiles]], and the copy's
  *     URL is shared, so the app may change or delete the original at once. The copy is made on the calling thread,
  *     before the sheet shows. No grant is needed: the system hands a file in the app's sandbox to the app the user
  *     picks. Its type is the file name's extension's, as Apple's share sheets read it; `mimeType` is kept beside the
  *     copy, as on Android, but nothing on Apple asks for it. Copies older than a day are deleted at each share.
  *
  * Fire and forget: neither sheet says what the user picked. Create it inside `AppleApp.run`, and call from the main
  * thread. A call with no window to show the sheet from throws `IllegalStateException`.
  *
  * @param cache
  *   the app's cache directory (`Storage.dirs.cache` from `thicket-storage-apple`).
  */
final class AppleShare private (cache: Path) extends Share {

  private val shared = SharedFiles(cache.resolve(AppleShare.SharedDir))

  def availability: Availability = Availability.Available

  def text(
    text:    String,
    subject: Option[String]
  ): Unit = {
    val ok = Zone {
      val (t, tn) = AppleShare.bytes(text)
      val (s, sn) = AppleShare.bytes(subject.getOrElse(""))
      ShareHelper.thk_share_text(t, tn, s, sn, if subject.isDefined then 1 else 0)
    }
    if ok == 0 then throw IllegalStateException(AppleShare.noWindow)
  }

  def file(
    path:     Path,
    mimeType: String
  ): Unit = {
    scala.util.Try(shared.prune())
    val (id, name) = shared.put(path, mimeType)
    val copy = cache.resolve(AppleShare.SharedDir).resolve(id).resolve(name).toAbsolutePath.toString
    val ok = Zone {
      val (p, n) = AppleShare.bytes(copy)
      ShareHelper.thk_share_file(p, n)
    }
    if ok == 0 then throw IllegalStateException(AppleShare.noWindow)
  }

}

object AppleShare {

  def apply(cache: Path): AppleShare = new AppleShare(cache)

  /** Under the cache directory, where shared copies wait for the app they were shared with. */
  val SharedDir: String = "thicket-shared"

  private val noWindow = "no window to show the share sheet from"

  private def bytes(s: String)(using Zone): (Ptr[Byte], CLongLong) = {
    val b = s.getBytes(UTF_8)
    val p = alloc[Byte](math.max(b.length, 1))
    var i = 0
    while i < b.length do {
      p(i) = b(i)
      i += 1
    }
    (p, b.length.toLong)
  }

  private def cString(f: (Ptr[Byte], CLongLong) => CInt): (Int, String) = {
    val cap = 4096
    val buf = stackalloc[Byte](cap)
    val r = f(buf, cap.toLong)
    (r, fromCString(buf, UTF_8))
  }

  /** What a self-test can ask of the share sheet: neither platform says what the user does with it, so these look at
    * the sheet itself.
    */
  object testing {

    /** How many sheets this process has shown. */
    def shownCount: Long = ShareHelper.thk_share_shown_count()

    /** Whether a share sheet is up now, and what shows it: on iOS the class of the controller on top (a
      * `UIActivityViewController` while it is up), on macOS the picker's popover window and its remote content view.
      */
    def showing: (Boolean, String) = {
      val (r, what) = cString(ShareHelper.thk_share_showing)
      (r > 0, what)
    }

    /** Takes the sheet down, as its close button or a click away would; false if none was up. */
    def dismiss(): Boolean = ShareHelper.thk_share_dismiss() == 1

    /** iOS: what the last text shared answers an activity of `activityType` for its item and its subject, asked as
      * UIKit asks it; `None` with no sheet up, and always on macOS.
      */
    def ask(activityType: String): Option[(String, String)] =
      Zone {
        val t = stackalloc[Byte](4096)
        val s = stackalloc[Byte](4096)
        if ShareHelper.thk_share_ask(toCString(activityType), t, 4096, s, 4096) == 0 then None
        else Some((fromCString(t, UTF_8), fromCString(s, UTF_8)))
      }

    /** macOS: the services the last picker proposed, by title, and how it ended (`None` not yet, `Some(false)` closed
      * with no choice, `Some(true)` a service chosen). Empty, and `None`, on iOS.
      */
    def proposed: (List[String], Option[Boolean]) = {
      val (r, titles) = cString(ShareHelper.thk_share_proposed)
      (titles.split('\n').toList.filter(_.nonEmpty), Option.when(r >= 0)(r == 1))
    }

  }

}

/** The C helper in `resources/scala-native/thicket_share.c`. */
@extern
private[apple] object ShareHelper {

  def thk_share_text(
    utf8:       Ptr[Byte],
    len:        CLongLong,
    subject:    Ptr[Byte],
    subjectLen: CLongLong,
    hasSubject: CInt
  ): CInt = extern

  def thk_share_file(
    path: Ptr[Byte],
    len:  CLongLong
  ): CInt = extern

  def thk_share_shown_count(): CLongLong = extern

  def thk_share_showing(
    buf: Ptr[Byte],
    cap: CLongLong
  ): CInt = extern

  def thk_share_dismiss(): CInt = extern

  def thk_share_ask(
    activityType: CString,
    text:         Ptr[Byte],
    textCap:      CLongLong,
    subject:      Ptr[Byte],
    subjectCap:   CLongLong
  ): CInt = extern

  def thk_share_proposed(
    buf: Ptr[Byte],
    cap: CLongLong
  ): CInt = extern

}
