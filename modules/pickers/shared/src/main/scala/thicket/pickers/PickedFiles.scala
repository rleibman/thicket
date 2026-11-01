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

package thicket.pickers

import java.io.InputStream
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.atomic.AtomicLong

/** Where picked files are copied: a fresh directory per file under `<cache>/picked`, so two picks of files with the
  * same name never overwrite each other. Platform modules use it; an app sees only the paths.
  */
final class PickedFiles(cache: Path) {

  private val counter = AtomicLong(0)

  /** Copies `in` to a new file named after `name` (made safe for a file system), closes `in`, and answers the path. */
  def copy(
    name: String,
    in:   InputStream
  ): Path =
    try {
      val dir = cache.resolve("picked").resolve(s"${System.currentTimeMillis()}-${counter.incrementAndGet()}")
      Files.createDirectories(dir)
      val target = dir.resolve(PickedFiles.safeName(name))
      val _ = Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING)
      target
    } finally in.close()

}

object PickedFiles {

  /** `name` with path separators and control characters replaced, never empty, never `.` or `..`. */
  def safeName(name: String): String = {
    val cleaned = name.map(c => if c == '/' || c == '\\' || c.isControl then '_' else c).trim
    if cleaned.isEmpty || cleaned == "." || cleaned == ".." then "picked" else cleaned
  }

}
