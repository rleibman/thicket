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
import thicket.device.Availability
import thicket.share.Share

import scala.util.Try

/** The Share section's state: a text and a small file to share, and what the share sheet's attempt said. The file is
  * written under `scratch` (the app's temporary directory), as an app's export would be.
  */
final class ShareDemo(
  share:   Share,
  scratch: Path
) {

  val availability: String = ShareDemo.availabilityLabel(share.availability)

  val status: thicket.signals.Var[String] = thicket.signals.Var(ShareDemo.nothingShared)

  def shareText(): Unit = status.set(ShareDemo.outcome("text", Try(share.text(ShareDemo.text))))

  def shareFile(): Unit =
    status.set(
      ShareDemo.outcome(
        "file",
        Try {
          val file = scratch.resolve(ShareDemo.fileName)
          Files.createDirectories(scratch)
          val _ = Files.write(file, ShareDemo.fileText.getBytes("UTF-8")) // ART has no Files.writeString
          share.file(file, "text/plain")
        }
      )
    )

}

object ShareDemo {

  val text:          String = "Shared from the Thicket gallery"
  val fileName:      String = "thicket-share.txt"
  val fileText:      String = "A file shared from the Thicket gallery\n"
  val nothingShared: String = "Share: nothing shared yet"

  def availabilityLabel(a: Availability): String =
    a match {
      case Availability.Available        => "Share: available"
      case Availability.Unavailable(why) => s"Share: unavailable ($why)"
      case other                         => s"Share: $other"
    }

  def outcome(
    what: String,
    t:    Try[Unit]
  ): String = t.fold(e => s"Share: the $what could not be shared (${e.getMessage})", _ => s"Share: $what handed over")

}
