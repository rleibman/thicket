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

import thicket.storage.testing.{MemoryPreferences, MemoryStorage}
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** The [[Preferences]] contract, run against the file implementation and the memory one; Android's runs the same rules
  * through [[StringPreferences]] and is checked on the emulator by the gallery's self-test.
  */
object PreferencesSpec extends ZIOSpecDefault {

  private def tempFile(): Path = Files.createTempDirectory("thicket-prefs").resolve("preferences.properties")

  private def contract(
    name: String,
    make: () => Preferences
  ) =
    suite(name)(
      test("each type reads back as itself") {
        val p = make()
        p.putString("s", "Leek soup")
        p.putLong("l", Long.MinValue)
        p.putDouble("d", 0.1 + 0.2)
        p.putBoolean("b", true)
        assertTrue(
          p.getString("s").contains("Leek soup"),
          p.getLong("l").contains(Long.MinValue),
          p.getDouble("d").contains(0.1 + 0.2),
          p.getBoolean("b").contains(true)
        )
      },
      test("a value read as another type is None, not a conversion") {
        val p = make()
        p.putString("n", "42")
        p.putLong("m", 42)
        assertTrue(
          p.getLong("n").isEmpty,
          p.getString("m").isEmpty,
          p.getDouble("m").isEmpty,
          p.getBoolean("m").isEmpty
        )
      },
      test("NaN and the infinities round-trip") {
        val p = make()
        p.putDouble("nan", Double.NaN)
        p.putDouble("inf", Double.NegativeInfinity)
        assertTrue(p.getDouble("nan").exists(_.isNaN), p.getDouble("inf").contains(Double.NegativeInfinity))
      },
      test("overwrite, remove and keys") {
        val p = make()
        p.putLong("a", 1)
        p.putLong("a", 2)
        p.putString("b", "x")
        p.remove("b")
        p.remove("never-set")
        assertTrue(p.getLong("a").contains(2L), p.getString("b").isEmpty, p.keys == Set("a"))
      },
      test("a missing key is None") {
        assertTrue(make().getString("nothing").isEmpty)
      }
    )

  def spec =
    suite("Preferences")(
      contract("MemoryPreferences", () => MemoryPreferences()),
      contract("FilePreferences", () => FilePreferences(tempFile())),
      suite("FilePreferences on disk")(
        test("values survive a new instance on the same file, awkward characters included") {
          val f = tempFile()
          val awkward = "a=b\\c\nnew line\r\tand ünïcödé, 🌿"
          val p1 = FilePreferences(f)
          p1.putString(awkward, awkward)
          p1.putString("empty", "")
          p1.putLong("count", 7)
          val p2 = FilePreferences(f)
          assertTrue(
            p2.getString(awkward).contains(awkward),
            p2.getString("empty").contains(""),
            p2.getLong("count").contains(7L),
            p2.keys == Set(awkward, "empty", "count")
          )
        },
        test("creates its directory, and leaves no temporary file behind") {
          val dir = Files.createTempDirectory("thicket-prefs").resolve("not/yet/there")
          val f = dir.resolve("p.properties")
          FilePreferences(f).putBoolean("ok", true)
          val names = Files.list(dir).toArray.map(_.toString.split('/').last).toSet
          assertTrue(names == Set("p.properties"))
        },
        test("a hand-edited file with a broken line loses that line, not the file") {
          val f = tempFile()
          Files.createDirectories(f.getParent)
          Files.write(f, "garbage without separator\ngood=s:yes\n".getBytes(UTF_8))
          val p = FilePreferences(f)
          assertTrue(p.getString("good").contains("yes"), p.keys == Set("good"))
        }
      ),
      suite("AppDirs")(
        test("ensure creates all three, and MemoryStorage's are fresh and writable") {
          val s = MemoryStorage()
          val probe = s.dirs.data.resolve("probe.txt")
          Files.write(probe, "x".getBytes(UTF_8))
          assertTrue(
            Seq(s.dirs.data, s.dirs.cache, s.dirs.temp).forall(Files.isDirectory(_)),
            Files.exists(probe),
            MemoryStorage().dirs.data != s.dirs.data
          )
        }
      )
    )

}
