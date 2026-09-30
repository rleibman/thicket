package thicket.tools.shim

import java.nio.file.{Files, Paths}
import zio.test.*
import scala.jdk.CollectionConverters.*

/** Checks the one thing that nothing else checks: that the C header, both Swift shims and
  * the Scala `@extern` bindings all declare the same ABI, and that [[Abi]] describes it.
  *
  * None of these files can catch a disagreement on its own. Swift compiles against its own
  * `@_cdecl` signature, Scala Native compiles against its own `extern`, and the linker
  * matches them by *name only* — so a parameter that is `Int32` on one side and `Int64` on
  * the other links cleanly and reads a garbage register at runtime, on a phone, in a
  * callback. This suite is the only place that failure is visible before it happens.
  *
  * It runs on the JVM, on any machine, and needs no Xcode.
  */
object ConsistencySpec extends ZIOSpecDefault {

  private val root  = Paths.get(sys.props.getOrElse("thicket.root", ".")).toAbsolutePath
  private val shim  = root.resolve("modules/renderer-apple/shim")
  private val scala = root.resolve("modules/renderer-apple/src/main/scala/thicket/renderer/apple")

  private def read(p: java.nio.file.Path): String = {
    // A plain throw, not an assertion: zio-test's `assert` is a different thing, and a
    // missing shim file is a setup failure rather than a check that failed.
    if !Files.exists(p) then
      throw new IllegalStateException(
        s"expected $p to exist; is thicket.root set correctly? (root=$root)"
      )
    Files.readAllLines(p).asScala.mkString("\n")
  }

  private lazy val header  = Parse.cHeader(read(shim.resolve("include/thicket_apple.h")))
  private lazy val appkit  = Parse.swiftShim(read(shim.resolve("Sources/Shim+AppKit.swift")))
  private lazy val uikit   = Parse.swiftShim(read(shim.resolve("Sources/Shim+UIKit.swift")))
  private lazy val externs = Parse.scalaExterns(read(scala.resolve("Shim.scala")))

  private def byName(r: Parse.Result): Map[String, Parse.Decl] =
    r.decls.map(d => d.name -> d).toMap

  /** Names whose declarations disagree between two artefacts.
    *
    * Collected rather than asserted one at a time: a drifting ABI usually drifts in several
    * places at once, and a check that stops at the first one makes you run it N times to
    * find out how bad it is.
    */
  private def disagreeing(
    a: Map[String, Parse.Decl],
    b: Map[String, Parse.Decl]
  )(same: (Parse.Decl, Parse.Decl) => Boolean): Set[String] =
    (a.keySet & b.keySet).filterNot(n => same(a(n), b(n)))

  def spec = suite("the Apple ABI")(
    test("every declaration in every artefact parses") {
      // An unparsed declaration is not a pass: it is a declaration this suite is silently
      // not checking, which is exactly the hole it exists to close.
      assertTrue(
        header.unparsed == Nil,
        appkit.unparsed == Nil,
        uikit.unparsed == Nil,
        externs.unparsed == Nil
      )
    },
    test("Abi describes exactly what the C header declares") {
      val spec = Abi.all.map(f => f.name -> Parse.Decl.of(f)).toMap
      val head = byName(header)
      assertTrue(head.keySet == spec.keySet) &&
        assertTrue(disagreeing(head, spec)(_ == _).isEmpty)
    },
    test("the Scala externs match the C header") {
      import Abi.repr
      val head = byName(header)
      val sc   = byName(externs)
      // Compared at `Repr`, not at `CType`: Scala Native binds `sui_handle` and
      // `const uint8_t *` to the same `Ptr[Byte]`, so demanding it tell them apart would be
      // a false positive forever. See Abi.Repr — the distinctions that survive are the ones
      // that cost a wrong register.
      val differing = disagreeing(sc, head) { (x, y) =>
        x.ret.repr == y.ret.repr && x.params.map(_.repr) == y.params.map(_.repr)
      }
      assertTrue(sc.keySet == head.keySet) && assertTrue(differing.isEmpty)
    },
    test("both Swift shims export the C header's functions, and agree with it") {
      val head = byName(header)
      val problems = List("AppKit" -> appkit, "UIKit" -> uikit).flatMap { (label, result) =>
        val exported = byName(result)
        val missing  = head.keySet -- exported.keySet
        val extra    = exported.keySet -- head.keySet -- Abi.swiftOnly
        val differing = disagreeing(exported, head)(_ == _)
        List(
          Option.when(missing.nonEmpty)(s"$label does not export: $missing"),
          Option.when(extra.nonEmpty)(s"$label exports undeclared: $extra"),
          Option.when(differing.nonEmpty)(s"$label signatures differ: $differing")
        ).flatten
      }
      assertTrue(problems.isEmpty)
    },
    test("the two shims export the same surface") {
      val a = byName(appkit).keySet -- Abi.swiftOnly
      val u = byName(uikit).keySet -- Abi.swiftOnly
      assertTrue(a == u)
    },
    test("each shim's sui_create builds the view Abi.kinds chooses for it") {
      // The per-platform *choice* of widget — a checkbox on the Mac, a switch on iOS — is
      // in the description, and this is what stops a shim quietly building something else.
      val problems = List(
        ("AppKit", "Sources/Shim+AppKit.swift", (k: Abi.Kind) => k.appKit),
        ("UIKit", "Sources/Shim+UIKit.swift", (k: Abi.Kind) => k.uiKit)
      ).flatMap { (label, path, view) =>
        val cases = Parse.swiftCreateCases(read(shim.resolve(path)))
        val known = Abi.kinds.filter(view(_).isDefined).map(_.code).toSet
        val wrong = Abi.kinds.flatMap { k =>
          view(k).flatMap { expected =>
            Option.when(!cases.blockFor(k.code).exists(_.contains(expected)))(
              s"$label kind ${k.code} ${k.name} does not build $expected"
            )
          }
        }
        val undeclared = (cases.explicit.keySet -- known).toList.sorted
          .map(c => s"$label handles kind $c, which Abi.kinds does not declare for it")
        val empty = Option.when(cases.explicit.isEmpty)(s"$label: no sui_create cases found")
        wrong ++ undeclared ++ empty
      }
      assertTrue(problems.isEmpty)
    },
    test("kind codes are unique and their names are Scala identifiers") {
      val codes = Abi.kinds.map(_.code)
      val names = Abi.kinds.map(_.name)
      assertTrue(
        codes.distinct == codes,
        names.distinct == names,
        names.forall(_.matches("[A-Z][A-Za-z0-9]*"))
      )
    },
    test("nothing crosses the boundary that cannot cross it safely") {
      // The rule S3 and S4 paid for: no struct by value, in either direction. The type
      // vocabulary enforces it by construction, so this asserts the vocabulary has not
      // grown a hole rather than re-deriving the finding.
      val allowed = Abi.CType.values.toSet
      val bad = Abi.all.filterNot(fn =>
        allowed.contains(fn.ret) && fn.params.forall(p => allowed.contains(p.tpe))
      )
      assertTrue(bad.isEmpty)
    }
  ) @@ TestAspect.sequential
}
