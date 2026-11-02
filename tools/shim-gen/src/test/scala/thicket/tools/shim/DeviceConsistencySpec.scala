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

/** [[ConsistencySpec]] for `thicket-device-apple`'s `thk_dev_*` surface ([[DeviceAbi]]): the generated header, the
  * generated Scala externs and the hand-written Swift library all declare the same functions, with the same types.
  *
  * The hazard is sharper here than for HTTP: this surface calls back into Scala, and a callback whose `int32_t` status
  * is an `Int64` on one side reads a garbage register on the main thread, on a phone.
  */
object DeviceConsistencySpec extends ZIOSpecDefault {

  private val root = Paths.get(sys.props.getOrElse("thicket.root", ".")).toAbsolutePath
  private val module = root.resolve("modules/device-apple")
  private val prefix = "thk_dev"

  private def read(p: java.nio.file.Path): String = {
    if !Files.exists(p) then
      throw new IllegalStateException(s"expected $p to exist; is thicket.root set correctly? (root=$root)")
    Files.readAllLines(p).asScala.mkString("\n")
  }

  lazy private val externsSource = read(module.resolve("src/main/scala/thicket/device/apple/DeviceShim.scala"))
  lazy private val header = Parse.cHeader(read(module.resolve("shim/include/thicket_device.h")), prefix)
  lazy private val swift = Parse.swiftShim(read(module.resolve("shim/Sources/DeviceShim.swift")))
  lazy private val externs = Parse.scalaExterns(externsSource, prefix)

  private def byName(r: Parse.Result): Map[String, Parse.Decl] = r.decls.map(d => d.name -> d).toMap

  def spec =
    suite("the thk_dev ABI")(
      test("every declaration in every artefact parses") {
        assertTrue(header.unparsed == Nil, swift.unparsed == Nil, externs.unparsed == Nil)
      },
      test("DeviceAbi describes exactly what the header declares, in order") {
        assertTrue(header.decls == DeviceAbi.all.map(Parse.Decl.of))
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
      test("the blocking calls, and only those, are @blocking in Scala") {
        val annotated = externsSource
          .split("\n").toList
          .sliding(2)
          .collect { case List(a, b) if a.trim == "@blocking" => b.trim.stripPrefix("def ").takeWhile(_ != '(') }
          .toSet
        assertTrue(annotated == DeviceAbi.all.filter(_.blocking).map(_.name).toSet, annotated.nonEmpty)
      },
      test("the callback types are the header's own, the same shape as the renderer's") {
        val h = read(module.resolve("shim/include/thicket_device.h"))
        val s = read(module.resolve("shim/Sources/DeviceShim.swift"))
        assertTrue(
          h.contains("typedef void (*thk_dev_void_cb)(int64_t ctx);"),
          h.contains("typedef void (*thk_dev_int_cb)(int64_t ctx, int32_t value);"),
          s.contains("public typealias thk_dev_void_cb = @convention(c) (Int64) -> Void"),
          s.contains("public typealias thk_dev_int_cb = @convention(c) (Int64, Int32) -> Void"),
          externsSource.contains("type VoidCb = CFuncPtr1[Long, Unit]"),
          externsSource.contains("type IntCb = CFuncPtr2[Long, CInt, Unit]")
        )
      },
      test("the surface overlaps neither the renderer's nor HTTP's") {
        val names = DeviceAbi.all.map(_.name).toSet
        assertTrue(
          DeviceAbi.all.forall(_.name.startsWith(s"${prefix}_")),
          (names & Abi.all.map(_.name).toSet).isEmpty,
          (names & HttpAbi.all.map(_.name).toSet).isEmpty
        )
      }
    ) @@ TestAspect.sequential

}
