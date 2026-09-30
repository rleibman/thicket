package thicket.tools.shim

import java.nio.file.{Files, Paths}
import zio.test.*
import scala.jdk.CollectionConverters.*

/** The round trip: does generating from [[Abi]] reproduce what is checked in?
  *
  * Compared as *declarations*, not as text. A byte diff would be a test of the formatter —
  * it would fail on a comment reflow or a scalafmt alignment and say nothing about whether
  * the ABI was preserved. What has to hold is that the generated artefact declares exactly
  * the same functions, with the same types, in the same order as the file it would replace.
  *
  * Passing this is what makes adopting the generator safe: it says the switch from
  * hand-written to generated changes no declaration.
  */
object GenerateSpec extends ZIOSpecDefault {

  private val root  = Paths.get(sys.props.getOrElse("thicket.root", ".")).toAbsolutePath
  private val shim  = root.resolve("modules/renderer-apple/shim")
  private val scala = root.resolve("modules/renderer-apple/src/main/scala/thicket/renderer/apple")

  private def read(p: java.nio.file.Path): String =
    Files.readAllLines(p).asScala.mkString("\n")

  def spec = suite("generating from Abi")(
    test("the generated C header declares exactly what the hand-written one declares") {
      val generated = Parse.cHeader(Emit.cHeader())
      val existing  = Parse.cHeader(read(shim.resolve("include/thicket_apple.h")))
      // Order too: the header is read by humans and diffed by reviewers, so a generator
      // that shuffled the declarations would be correct and useless.
      assertTrue(generated.unparsed == Nil, generated.decls == existing.decls)
    },
    test("the generated Scala externs declare exactly what the hand-written ones declare") {
      import Abi.repr
      val generated = Parse.scalaExterns(Emit.scalaExterns())
      val existing  = Parse.scalaExterns(read(scala.resolve("Shim.scala")))
      val differing = generated.decls.zip(existing.decls).filterNot { (g, e) =>
        g.ret.repr == e.ret.repr && g.params.map(_.repr) == e.params.map(_.repr)
      }
      assertTrue(
        generated.unparsed == Nil,
        generated.decls.map(_.name) == existing.decls.map(_.name),
        differing.isEmpty
      )
    },
    test("the generated Swift signatures match both hand-written shims") {
      val generated = Parse.swiftShim(Emit.swiftSignatures())
      val mismatches = List("AppKit" -> "Sources/Shim+AppKit.swift", "UIKit" -> "Sources/Shim+UIKit.swift")
        .flatMap { (label, path) =>
          val existing = Parse.swiftShim(read(shim.resolve(path))).decls
            .filterNot(d => Abi.swiftOnly.contains(d.name))
            .map(d => d.name -> d)
            .toMap
          generated.decls.collect {
            case g if existing.get(g.name) != Some(g) => s"$label: ${g.name}"
          }
        }
      assertTrue(generated.unparsed == Nil, mismatches.isEmpty)
    },
    test("the generated header and the generated externs agree with each other") {
      import Abi.repr
      val h  = Parse.cHeader(Emit.cHeader()).decls.map(d => d.name -> d).toMap
      val sc = Parse.scalaExterns(Emit.scalaExterns()).decls.map(d => d.name -> d).toMap
      val differing = (h.keySet & sc.keySet).filterNot(n =>
        sc(n).params.map(_.repr) == h(n).params.map(_.repr)
      )
      assertTrue(sc.keySet == h.keySet, differing.isEmpty)
    }
  ) @@ TestAspect.sequential
}
