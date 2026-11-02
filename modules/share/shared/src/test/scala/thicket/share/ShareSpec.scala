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

import java.nio.file.{Files, Path}
import thicket.device.Availability
import thicket.share.testing.RecordingShare
import zio.test.*

object ShareSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Nothing] =
    suite("Share")(
      test("SharedFiles serves a copy under its own name and type, and the original can go") {
        val dir = Files.createTempDirectory("thicket-share")
        val original = Files.createTempFile("notes", ".txt")
        Files.writeString(original, "hello")
        val shared = SharedFiles(dir)
        val (id, name) = shared.put(original, "text/plain")
        Files.delete(original)
        val served = shared.resolve(id, name)
        assertTrue(
          name == original.getFileName.toString,
          served.exists(p => Files.readString(p) == "hello"),
          shared.mimeType(id).contains("text/plain")
        )
      },
      test("SharedFiles serves nothing outside its directory, nor the type file") {
        val root = Files.createTempDirectory("thicket-share-root")
        val dir = Files.createDirectories(root.resolve("shared"))
        Files.writeString(root.resolve("secret.txt"), "no")
        val shared = SharedFiles(dir)
        val (id, name) = shared.put(Files.writeString(Files.createTempFile("a", ".txt"), "a"), "text/plain")
        assertTrue(
          shared.resolve("..", "secret.txt").isEmpty,
          shared.resolve(id, "../../secret.txt").isEmpty,
          shared.resolve(id, "..").isEmpty,
          shared.resolve(id, SharedFiles.TypeFile).isEmpty,
          shared.resolve("missing", name).isEmpty,
          shared.mimeType("..").isEmpty,
          shared.resolve(id, name).isDefined
        )
      },
      test("prune deletes the copies older than the limit and keeps the rest") {
        val dir = Files.createTempDirectory("thicket-share-prune")
        val shared = SharedFiles(dir)
        val src = Files.writeString(Files.createTempFile("p", ".txt"), "p")
        val (old, _) = shared.put(src, "text/plain")
        Files.setLastModifiedTime(dir.resolve(old), java.nio.file.attribute.FileTime.fromMillis(0))
        val (fresh, freshName) = shared.put(src, "text/plain")
        shared.prune()
        assertTrue(!Files.exists(dir.resolve(old)), shared.resolve(fresh, freshName).isDefined)
      },
      test("safeName keeps a name to one segment") {
        assertTrue(
          SharedFiles.safeName("../x") == ".._x",
          SharedFiles.safeName("..") == "shared",
          SharedFiles.safeName(".type") == "shared",
          SharedFiles.safeName("Résumé.pdf") == "Résumé.pdf"
        )
      },
      test("RecordingShare records; Share.unavailable says why") {
        val r = RecordingShare()
        r.text("hi")
        r.file(Path.of("/a.png"), "image/png")
        val u = Share.unavailable("no share sheet on the desktop")
        u.text("ignored")
        assertTrue(
          r.shared.toList == List(Left("hi"), Right(Path.of("/a.png") -> "image/png")),
          u.availability == Availability.Unavailable("no share sheet on the desktop")
        )
      }
    )

}
