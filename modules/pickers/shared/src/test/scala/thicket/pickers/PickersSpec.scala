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

import java.io.ByteArrayInputStream
import java.nio.file.{Files, Path}
import thicket.pickers.testing.{FakeDocuments, FakePhotos}
import zio.test.*

object PickersSpec extends ZIOSpecDefault {

  private def tempDir(): Path = Files.createTempDirectory("thicket-pickers")

  def spec: Spec[Any, Nothing] =
    suite("Pickers")(
      test("PickedFiles copies the bytes, under the file's own name, in a directory of its own") {
        val picked = PickedFiles(tempDir())
        val a = picked.copy("photo.jpg", ByteArrayInputStream(Array[Byte](1, 2, 3)))
        val b = picked.copy("photo.jpg", ByteArrayInputStream(Array[Byte](4, 5)))
        assertTrue(
          a.getFileName.toString == "photo.jpg",
          b.getFileName.toString == "photo.jpg",
          a.getParent != b.getParent,
          Files.readAllBytes(a).toList == List[Byte](1, 2, 3),
          Files.readAllBytes(b).toList == List[Byte](4, 5)
        )
      },
      test("PickedFiles closes the stream, even when the copy fails") {
        var closed = false
        val in = new ByteArrayInputStream(Array[Byte](1)) {
          override def close(): Unit = closed = true
        }
        val notADir = Files.createTempFile("thicket-pickers", ".file")
        val failed = scala.util.Try(PickedFiles(notADir).copy("x", in))
        assertTrue(failed.isFailure, closed)
      },
      test("safeName keeps a picked file inside its directory") {
        assertTrue(
          PickedFiles.safeName("../../etc/passwd") == ".._.._etc_passwd",
          PickedFiles.safeName("a\\b") == "a_b",
          PickedFiles.safeName("..") == "picked",
          PickedFiles.safeName("  ") == "picked",
          PickedFiles.safeName("tab\there") == "tab_here",
          PickedFiles.safeName("Résumé 2026.pdf") == "Résumé 2026.pdf"
        )
      },
      test("the fakes answer at once: at most the limit, one unless multiple, and what was saved") {
        val paths = Seq(Path.of("/a"), Path.of("/b"), Path.of("/c"))
        val photos = FakePhotos(paths)
        val docs = FakeDocuments(paths)
        var picked:  Seq[Path] = Nil
        var one:     Seq[Path] = Nil
        var all:     Seq[Path] = Nil
        var savedAs: Option[String] = None
        photos.pick(2)(picked = _)
        docs.open(Nil, multiple = false)(one = _)
        docs.open(Nil, multiple = true)(all = _)
        docs.save("notes.txt", "text/plain", "hi".getBytes)(savedAs = _)
        assertTrue(
          picked == paths.take(2),
          photos.asked.toList == List(2),
          one == paths.take(1),
          all == paths,
          savedAs.contains("notes.txt"),
          docs.saved.get("notes.txt").map(_.toList).contains("hi".getBytes.toList)
        )
      }
    )

}
