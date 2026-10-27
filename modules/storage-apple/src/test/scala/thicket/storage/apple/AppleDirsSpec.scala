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

package thicket.storage.apple

import zio.test.*

import java.nio.file.Paths

object AppleDirsSpec extends ZIOSpecDefault {

  def spec =
    suite("AppleDirs")(
      test("macOS: Application Support and Caches in the home's Library, temp in the user's own, each by bundle id") {
        val d = AppleDirs.resolve("/Users/ann", "/var/folders/xy/abc/T/", "dev.thicket.app")
        assertTrue(
          d.data == Paths.get("/Users/ann/Library/Application Support/dev.thicket.app"),
          d.cache == Paths.get("/Users/ann/Library/Caches/dev.thicket.app"),
          d.temp == Paths.get("/var/folders/xy/abc/T/dev.thicket.app")
        )
      },
      test("iOS: the same places inside the container") {
        val c = "/Users/ann/Library/Developer/CoreSimulator/Devices/D/data/Containers/Data/Application/A"
        val d = AppleDirs.resolve(c, s"$c/tmp/", "dev.thicket.app")
        assertTrue(
          d.data == Paths.get(s"$c/Library/Application Support/dev.thicket.app"),
          d.cache == Paths.get(s"$c/Library/Caches/dev.thicket.app"),
          d.temp == Paths.get(s"$c/tmp/dev.thicket.app")
        )
      },
      test("a bundle id that would escape its directory, or a relative home or temp, is refused") {
        val bad = Seq("", "..", ".", "a/b")
        assertTrue(
          bad.forall(id => scala.util.Try(AppleDirs.resolve("/Users/ann", "/tmp", id)).isFailure),
          scala.util.Try(AppleDirs.resolve("Users/ann", "/tmp", "app")).isFailure,
          scala.util.Try(AppleDirs.resolve("/Users/ann", "tmp", "app")).isFailure
        )
      },
      test("on this machine, if it is a Mac: a home, and a per-user temporary directory from confstr") {
        // A branch, not `!mac || …` inside assertTrue: its macro evaluates both sides, so `userTemp()` threw on Linux.
        val mac = System.getProperty("os.name", "").toLowerCase.contains("mac")
        if !mac then assertCompletes
        else assertTrue(AppleDirs.home().startsWith("/"), AppleDirs.userTemp().startsWith("/"))
      }
    )

}
