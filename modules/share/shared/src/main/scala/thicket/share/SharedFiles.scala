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

package thicket.share

import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.atomic.AtomicLong

/** Where shared files wait for the app they were shared with: a copy per share, at `<dir>/<id>/<name>`, so the app can
  * change or delete the original at once. A platform module serves them from here, and only from here.
  */
final class SharedFiles(dir: Path) {

  private val counter = AtomicLong(0)

  /** Copies `from` under a fresh id, keeping its (made safe) file name and remembering `mimeType`; answers the id and
    * the name the copy is served under.
    */
  def put(
    from:     Path,
    mimeType: String
  ): (String, String) = {
    val id = s"${System.currentTimeMillis()}-${counter.incrementAndGet()}"
    val name = SharedFiles.safeName(from.getFileName.toString)
    val target = dir.resolve(id)
    Files.createDirectories(target)
    val _ = Files.copy(from, target.resolve(name), StandardCopyOption.REPLACE_EXISTING)
    // Not Files.writeString: Android's ART has no Java 11 Files API.
    val _ = Files.write(target.resolve(SharedFiles.TypeFile), mimeType.getBytes("UTF-8"))
    (id, name)
  }

  /** The file a request for `id`/`name` may read, if there is one: never anything outside `dir`, never the type file.
    */
  def resolve(
    id:   String,
    name: String
  ): Option[Path] =
    Option
      .when(SharedFiles.plain(id) && SharedFiles.plain(name) && name != SharedFiles.TypeFile) {
        dir.resolve(id).resolve(name)
      }.filter(p => p.normalize.startsWith(dir.normalize) && Files.isRegularFile(p))

  /** The MIME type `put` remembered for `id`. */
  def mimeType(id: String): Option[String] =
    Option
      .when(SharedFiles.plain(id))(dir.resolve(id).resolve(SharedFiles.TypeFile))
      .filter(Files.isRegularFile(_)).map(p => new String(Files.readAllBytes(p), "UTF-8").trim)

  /** Deletes the copies older than `maxAgeMillis`; a day is plenty for an app to read what it was given. */
  def prune(maxAgeMillis: Long = 24L * 60 * 60 * 1000): Unit =
    if Files.isDirectory(dir) then {
      val cutoff = System.currentTimeMillis() - maxAgeMillis
      val stream = Files.list(dir)
      try
        stream.toArray.toList
          .map(_.asInstanceOf[Path]).filter(p => Files.getLastModifiedTime(p).toMillis < cutoff).foreach { old =>
            val files = Files.list(old)
            try files.toArray.toList.map(_.asInstanceOf[Path]).foreach(Files.deleteIfExists(_))
            finally files.close()
            val _ = Files.deleteIfExists(old)
          }
      finally stream.close()
    }

}

object SharedFiles {

  /** Beside each copy, its MIME type. */
  val TypeFile: String = ".type"

  /** One path segment and nothing more: no separators, no `.` or `..`, not empty. */
  def plain(segment: String): Boolean =
    segment.nonEmpty && segment != "." && segment != ".." && !segment.exists(c => c == '/' || c == '\\' || c.isControl)

  /** `name` with separators and control characters replaced, never empty, `.` or `..`. */
  def safeName(name: String): String = {
    val cleaned = name.map(c => if c == '/' || c == '\\' || c.isControl then '_' else c).trim
    if cleaned.isEmpty || cleaned == "." || cleaned == ".." || cleaned == TypeFile then "shared" else cleaned
  }

}
