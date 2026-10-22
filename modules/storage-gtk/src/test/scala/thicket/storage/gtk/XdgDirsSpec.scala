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

package thicket.storage.gtk

import zio.test.*

import java.nio.file.Paths

object XdgDirsSpec extends ZIOSpecDefault {

  def spec =
    suite("XdgDirs")(
      test("with no XDG variables, the spec's defaults under home") {
        val d = XdgDirs.resolve(Map.empty, "/home/ann", "dev.thicket.app")
        assertTrue(
          d.data == Paths.get("/home/ann/.local/share/dev.thicket.app"),
          d.cache == Paths.get("/home/ann/.cache/dev.thicket.app"),
          d.temp == Paths.get("/home/ann/.cache/dev.thicket.app/tmp")
        )
      },
      test("absolute XDG variables are used; relative ones are ignored, as the spec says") {
        val d = XdgDirs.resolve(
          Map("XDG_DATA_HOME" -> "/srv/data", "XDG_CACHE_HOME" -> "relative/cache", "TMPDIR" -> "/var/tmp"),
          "/home/ann",
          "app"
        )
        assertTrue(
          d.data == Paths.get("/srv/data/app"),
          d.cache == Paths.get("/home/ann/.cache/app"),
          // TMPDIR is not used: temp lives in the user's own cache directory, never a shared one.
          d.temp == Paths.get("/home/ann/.cache/app/tmp")
        )
      },
      test("temp is inside the cache directory, wherever XDG_CACHE_HOME puts it, and never under /tmp or TMPDIR") {
        val d = XdgDirs.resolve(Map("XDG_CACHE_HOME" -> "/srv/cache", "TMPDIR" -> "/tmp"), "/home/ann", "app")
        assertTrue(d.temp == Paths.get("/srv/cache/app/tmp"), d.temp.startsWith(d.cache), !d.temp.startsWith("/tmp"))
      },
      test("an app id that would escape its directory is refused") {
        val bad = Seq("", "..", ".", "a/b")
        assertTrue(bad.forall(id => scala.util.Try(XdgDirs.resolve(Map.empty, "/home/ann", id)).isFailure))
      }
    )

}
