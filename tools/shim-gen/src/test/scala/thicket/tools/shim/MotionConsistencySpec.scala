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

/** [[ConsistencySpec]] for `thicket-motion-ios`'s `thk_mot_*` surface ([[MotionAbi]]): the generated header, the
  * generated Scala externs and the hand-written Swift library all declare the same functions, with the same types.
  *
  * The sample callback is the one to watch: three doubles in three float registers. A `CFuncPtr4` whose types drift
  * from the Swift `@convention(c)` type compiles on both sides and delivers garbage readings.
  */
object MotionConsistencySpec extends ZIOSpecDefault {

  private val root = Paths.get(sys.props.getOrElse("thicket.root", ".")).toAbsolutePath
  private val module = root.resolve("modules/motion-ios")
  private val prefix = "thk_mot"

  private def read(p: java.nio.file.Path): String = {
    if !Files.exists(p) then
      throw new IllegalStateException(s"expected $p to exist; is thicket.root set correctly? (root=$root)")
    Files.readAllLines(p).asScala.mkString("\n")
  }

  lazy private val externsSource = read(module.resolve("src/main/scala/thicket/motion/ios/MotionShim.scala"))
  lazy private val header = Parse.cHeader(read(module.resolve("shim/include/thicket_motion.h")), prefix)
  lazy private val swift = Parse.swiftShim(read(module.resolve("shim/Sources/MotionShim.swift")))
  lazy private val externs = Parse.scalaExterns(externsSource, prefix)

  private def byName(r: Parse.Result): Map[String, Parse.Decl] = r.decls.map(d => d.name -> d).toMap

  def spec =
    suite("the thk_mot ABI")(
      test("every declaration in every artefact parses") {
        assertTrue(header.unparsed == Nil, swift.unparsed == Nil, externs.unparsed == Nil)
      },
      test("MotionAbi describes exactly what the header declares, in order") {
        assertTrue(header.decls == MotionAbi.all.map(Parse.Decl.of))
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
      test("no call is @blocking in Scala, and MotionAbi marks none blocking") {
        val annotated = externsSource
          .split("\n").toList
          .sliding(2)
          .collect { case List(a, b) if a.trim == "@blocking" => b.trim.stripPrefix("def ").takeWhile(_ != '(') }
          .toSet
        // None blocks: every call returns at once, and the samples come later, on the main queue.
        assertTrue(annotated == MotionAbi.all.filter(_.blocking).map(_.name).toSet, annotated.isEmpty)
      },
      test("the sample callback is the header's own: three doubles after the context") {
        val h = read(module.resolve("shim/include/thicket_motion.h"))
        val s = read(module.resolve("shim/Sources/MotionShim.swift"))
        assertTrue(
          h.contains("typedef void (*thk_mot_vec3_cb)(int64_t ctx, double x, double y, double z);"),
          s.contains("public typealias thk_mot_vec3_cb = @convention(c) (Int64, Double, Double, Double) -> Void"),
          externsSource.contains("type Vec3Cb = CFuncPtr4[Long, CDouble, CDouble, CDouble, Unit]")
        )
      },
      test("the surface overlaps none of the renderer's, HTTP's or the device's") {
        val names = MotionAbi.all.map(_.name).toSet
        assertTrue(
          MotionAbi.all.forall(_.name.startsWith(s"${prefix}_")),
          (names & Abi.all.map(_.name).toSet).isEmpty,
          (names & HttpAbi.all.map(_.name).toSet).isEmpty,
          (names & DeviceAbi.all.map(_.name).toSet).isEmpty
        )
      }
    ) @@ TestAspect.sequential

}
