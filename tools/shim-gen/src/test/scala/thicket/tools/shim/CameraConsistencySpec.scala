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

/** [[ConsistencySpec]] for `thicket-camera-apple`'s `thk_cam_*` surface ([[CameraAbi]]): the generated header, the
  * generated Scala externs and the hand-written Swift library all declare the same functions, with the same types.
  *
  * The answer callback is the one to watch: a context and a C string. A `CFuncPtr2` whose types drift from the Swift
  * `@convention(c)` type compiles on both sides and hands Scala a pointer that is not a path.
  */
object CameraConsistencySpec extends ZIOSpecDefault {

  private val root = Paths.get(sys.props.getOrElse("thicket.root", ".")).toAbsolutePath
  private val module = root.resolve("modules/camera-apple")
  private val prefix = "thk_cam"

  private def read(p: java.nio.file.Path): String = {
    if !Files.exists(p) then
      throw new IllegalStateException(s"expected $p to exist; is thicket.root set correctly? (root=$root)")
    Files.readAllLines(p).asScala.mkString("\n")
  }

  lazy private val externsSource = read(module.resolve("src/main/scala/thicket/camera/apple/CameraShim.scala"))
  lazy private val header = Parse.cHeader(read(module.resolve("shim/include/thicket_camera.h")), prefix)
  lazy private val swift = Parse.swiftShim(read(module.resolve("shim/Sources/CameraShim.swift")))
  lazy private val externs = Parse.scalaExterns(externsSource, prefix)

  private def byName(r: Parse.Result): Map[String, Parse.Decl] = r.decls.map(d => d.name -> d).toMap

  def spec =
    suite("the thk_cam ABI")(
      test("every declaration in every artefact parses") {
        assertTrue(header.unparsed == Nil, swift.unparsed == Nil, externs.unparsed == Nil)
      },
      test("CameraAbi describes exactly what the header declares, in order") {
        assertTrue(header.decls == CameraAbi.all.map(Parse.Decl.of))
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
      test("no call is @blocking in Scala, and CameraAbi marks none blocking") {
        val annotated = externsSource
          .split("\n").toList
          .sliding(2)
          .collect { case List(a, b) if a.trim == "@blocking" => b.trim.stripPrefix("def ").takeWhile(_ != '(') }
          .toSet
        // None blocks: presenting returns at once, and the answer comes later, from the picker's delegate.
        assertTrue(annotated == CameraAbi.all.filter(_.blocking).map(_.name).toSet, annotated.isEmpty)
      },
      test("the answer callback is the header's own: a C string after the context") {
        val h = read(module.resolve("shim/include/thicket_camera.h"))
        val s = read(module.resolve("shim/Sources/CameraShim.swift"))
        assertTrue(
          h.contains("typedef void (*thk_cam_text_cb)(int64_t ctx, const char *path);"),
          s.contains("public typealias thk_cam_text_cb = @convention(c) (Int64, UnsafePointer<CChar>) -> Void"),
          externsSource.contains("type TextCb = CFuncPtr2[Long, CString, Unit]")
        )
      },
      test("the surface overlaps none of the renderer's, HTTP's, the device's or motion's") {
        val names = CameraAbi.all.map(_.name).toSet
        assertTrue(
          CameraAbi.all.forall(_.name.startsWith(s"${prefix}_")),
          (names & Abi.all.map(_.name).toSet).isEmpty,
          (names & HttpAbi.all.map(_.name).toSet).isEmpty,
          (names & DeviceAbi.all.map(_.name).toSet).isEmpty,
          (names & MotionAbi.all.map(_.name).toSet).isEmpty
        )
      }
    ) @@ TestAspect.sequential

}
