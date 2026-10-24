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

/** [[ConsistencySpec]] for `thicket-http-apple`'s `thk_http_*` surface ([[HttpAbi]]): the generated header, the
  * generated Scala externs and the hand-written Swift library all declare the same functions, with the same types.
  *
  * Same hazard, same reason it runs on the JVM: the linker matches `thk_http_wait` by name, so an `Int32` waiting time
  * on one side and an `Int64` on the other links cleanly and blocks for a garbage number of milliseconds.
  */
object HttpConsistencySpec extends ZIOSpecDefault {

  private val root = Paths.get(sys.props.getOrElse("thicket.root", ".")).toAbsolutePath
  private val module = root.resolve("modules/http-apple")
  private val prefix = "thk_http"

  private def read(p: java.nio.file.Path): String = {
    if !Files.exists(p) then
      throw new IllegalStateException(s"expected $p to exist; is thicket.root set correctly? (root=$root)")
    Files.readAllLines(p).asScala.mkString("\n")
  }

  lazy private val externsSource = read(module.resolve("src/main/scala/thicket/http/apple/HttpShim.scala"))
  lazy private val header = Parse.cHeader(read(module.resolve("shim/include/thicket_http.h")), prefix)
  lazy private val swift = Parse.swiftShim(read(module.resolve("shim/Sources/HttpShim.swift")))
  lazy private val externs = Parse.scalaExterns(externsSource, prefix)

  private def byName(r: Parse.Result): Map[String, Parse.Decl] = r.decls.map(d => d.name -> d).toMap

  def spec =
    suite("the thk_http ABI")(
      test("every declaration in every artefact parses") {
        assertTrue(header.unparsed == Nil, swift.unparsed == Nil, externs.unparsed == Nil)
      },
      test("HttpAbi describes exactly what the header declares, in order") {
        assertTrue(header.decls == HttpAbi.all.map(Parse.Decl.of))
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
        // A blocking wait without it stalls every collection for as long as the request takes.
        val annotated = externsSource
          .split("\n").toList
          .sliding(2)
          .collect { case List(a, b) if a.trim == "@blocking" => b.trim.stripPrefix("def ").takeWhile(_ != '(') }
          .toSet
        assertTrue(annotated == HttpAbi.all.filter(_.blocking).map(_.name).toSet, annotated.nonEmpty)
      },
      test("nothing in it calls back into Scala") {
        // URLSession completes on a thread the GC does not know; the surface is pull-only.
        import Abi.repr
        val callbacks = HttpAbi.all.filter(fn => (fn.ret :: fn.params.map(_.tpe)).exists(_.repr == Abi.Repr.FnPointer))
        assertTrue(callbacks.isEmpty)
      },
      test("the surface does not overlap the renderer's") {
        assertTrue(
          HttpAbi.all.forall(_.name.startsWith(s"${prefix}_")),
          (HttpAbi.all.map(_.name).toSet & Abi.all.map(_.name).toSet).isEmpty
        )
      }
    ) @@ TestAspect.sequential

}
