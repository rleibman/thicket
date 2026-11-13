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

package thicket.tools.shim

import java.nio.file.{Files, Paths}
import zio.test.*
import scala.jdk.CollectionConverters.*

/** [[ConsistencySpec]] for `thicket-camera-preview-apple`'s `thk_prev_*` surface ([[PreviewAbi]]): the generated
  * header, the generated Scala externs and the hand-written Swift library all declare the same functions, with the same
  * types.
  *
  * The answer callbacks are the ones to watch: a context and an `int32_t`, a context and a C string. A `CFuncPtr2`
  * whose types drift from the Swift `@convention(c)` type compiles on both sides and hands Scala a register that is not
  * the answer.
  */
object PreviewConsistencySpec extends ZIOSpecDefault {

  private val root = Paths.get(sys.props.getOrElse("thicket.root", ".")).toAbsolutePath
  private val module = root.resolve("modules/camera-preview-apple")
  private val prefix = "thk_prev"

  private def read(p: java.nio.file.Path): String = {
    if !Files.exists(p) then
      throw new IllegalStateException(s"expected $p to exist; is thicket.root set correctly? (root=$root)")
    Files.readAllLines(p).asScala.mkString("\n")
  }

  lazy private val externsSource = read(module.resolve("src/main/scala/thicket/camera/preview/apple/PreviewShim.scala"))
  lazy private val header = Parse.cHeader(read(module.resolve("shim/include/thicket_preview.h")), prefix)
  lazy private val swift = Parse.swiftShim(read(module.resolve("shim/Sources/PreviewShim.swift")))
  lazy private val externs = Parse.scalaExterns(externsSource, prefix)

  private def byName(r: Parse.Result): Map[String, Parse.Decl] = r.decls.map(d => d.name -> d).toMap

  def spec =
    suite("the thk_prev ABI")(
      test("every declaration in every artefact parses") {
        assertTrue(header.unparsed == Nil, swift.unparsed == Nil, externs.unparsed == Nil)
      },
      test("PreviewAbi describes exactly what the header declares, in order") {
        assertTrue(header.decls == PreviewAbi.all.map(Parse.Decl.of))
      },
      test("the Scala externs match the header") {
        import Abi.repr
        val head = byName(header)
        val sc = byName(externs)
        val differing = (sc.keySet & head.keySet).filterNot { n =>
          sc(n).ret.repr == head(n).ret.repr && sc(n).params.map(_.repr) == head(n).params.map(_.repr)
        }
        assertTrue(sc.keySet == head.keySet, differing.isEmpty)
      },
      test("the Swift library exports exactly the header's functions, with its types") {
        val head = byName(header)
        val exported = byName(swift)
        val differing = (exported.keySet & head.keySet).filterNot(n => exported(n) == head(n))
        assertTrue(exported.keySet == head.keySet, differing.isEmpty)
      },
      test("no call is @blocking in Scala, and PreviewAbi marks none blocking") {
        val annotated = externsSource
          .split("\n").toList
          .sliding(2)
          .collect { case List(a, b) if a.trim == "@blocking" => b.trim.stripPrefix("def ").takeWhile(_ != '(') }
          .toSet
        // None blocks: the session starts and stops on the library's queue, and the answers come later.
        assertTrue(annotated == PreviewAbi.all.filter(_.blocking).map(_.name).toSet, annotated.isEmpty)
      },
      test("the answer callbacks are the header's own: an int32_t, and a C string, after the context") {
        val h = read(module.resolve("shim/include/thicket_preview.h"))
        val s = read(module.resolve("shim/Sources/PreviewShim.swift"))
        assertTrue(
          h.contains("typedef void (*thk_prev_int_cb)(int64_t ctx, int32_t result);"),
          h.contains("typedef void (*thk_prev_text_cb)(int64_t ctx, const char *path);"),
          s.contains("public typealias thk_prev_int_cb = @convention(c) (Int64, Int32) -> Void"),
          s.contains("public typealias thk_prev_text_cb = @convention(c) (Int64, UnsafePointer<CChar>) -> Void"),
          externsSource.contains("type IntCb = CFuncPtr2[Long, CInt, Unit]"),
          externsSource.contains("type TextCb = CFuncPtr2[Long, CString, Unit]")
        )
      },
      test(
        "the surface overlaps none of the renderer's, HTTP's, the device's, motion's, the pickers' or the camera's"
      ) {
        val names = PreviewAbi.all.map(_.name).toSet
        assertTrue(
          PreviewAbi.all.forall(_.name.startsWith(s"${prefix}_")),
          (names & Abi.all.map(_.name).toSet).isEmpty,
          (names & HttpAbi.all.map(_.name).toSet).isEmpty,
          (names & DeviceAbi.all.map(_.name).toSet).isEmpty,
          (names & MotionAbi.all.map(_.name).toSet).isEmpty,
          (names & PickersAbi.all.map(_.name).toSet).isEmpty,
          (names & CameraAbi.all.map(_.name).toSet).isEmpty
        )
      }
    ) @@ TestAspect.sequential

}
