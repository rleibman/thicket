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

package thicket.storage

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import scala.jdk.CollectionConverters.*

/** [[Preferences]] in one file, for the platforms with no preferences store of their own (GTK: GSettings needs a schema
  * installed system-wide).
  *
  * The whole file is read once and rewritten on every change, through a temporary file and a rename, so a crash leaves
  * either the old file or the new one, never half of each. Meant for a few hundred small values, which is what
  * preferences are; anything bigger belongs in a database.
  *
  * One line per value, `key=value`, with `\`, newlines, carriage returns and `=` in keys escaped.
  */
final class FilePreferences(file: Path) extends StringPreferences {

  private val values: collection.mutable.LinkedHashMap[String, String] = load()

  protected def read(key: String): Option[String] = synchronized(values.get(key))

  protected def write(
    key:    String,
    tagged: String
  ): Unit =
    synchronized {
      values(key) = tagged
      save()
    }

  def remove(key: String): Unit =
    synchronized {
      if values.remove(key).isDefined then save()
    }

  def keys: Set[String] = synchronized(values.keySet.toSet)

  private def load(): collection.mutable.LinkedHashMap[String, String] = {
    val m = collection.mutable.LinkedHashMap.empty[String, String]
    if Files.exists(file) then
      Files.readAllLines(file, UTF_8).asScala.foreach { line =>
        FilePreferences
          .splitLine(line).foreach(
            (
              k,
              v
            ) => m(k) = v
          )
      }
    m
  }

  private def save(): Unit = {
    Option(file.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
    val tmp = file.resolveSibling(s"${file.getFileName}.tmp")
    val text = values.iterator
      .map(
        (
          k,
          v
        ) => s"${FilePreferences.escape(k, key = true)}=${FilePreferences.escape(v, key = false)}\n"
      ).mkString
    Files.write(tmp, text.getBytes(UTF_8))
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
  }

}

object FilePreferences {

  private[storage] def escape(
    s:   String,
    key: Boolean
  ): String = {
    val b = new StringBuilder
    s.foreach {
      case '\\'       => b.append("\\\\")
      case '\n'       => b.append("\\n")
      case '\r'       => b.append("\\r")
      case '=' if key => b.append("\\=")
      case c          => b.append(c)
    }
    b.toString
  }

  /** The key and value of one line, or `None` for a line that is not one — a hand-edited file degrades to fewer values,
    * not an exception at startup.
    */
  private[storage] def splitLine(line: String): Option[(String, String)] = {
    val key = new StringBuilder
    var i = 0
    var sep = -1
    while i < line.length && sep < 0 do {
      line.charAt(i) match {
        case '\\' if i + 1 < line.length =>
          key.append(unescape(line.charAt(i + 1)))
          i += 2
        case '=' => sep = i
        case c   =>
          key.append(c)
          i += 1
      }
    }
    if sep < 0 then None
    else {
      val value = new StringBuilder
      var j = sep + 1
      while j < line.length do {
        if line.charAt(j) == '\\' && j + 1 < line.length then {
          value.append(unescape(line.charAt(j + 1)))
          j += 2
        } else {
          value.append(line.charAt(j))
          j += 1
        }
      }
      Some(key.toString -> value.toString)
    }
  }

  private def unescape(c: Char): Char =
    c match {
      case 'n'   => '\n'
      case 'r'   => '\r'
      case other => other
    }

}
