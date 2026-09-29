package scalaui.tools.shim

import java.nio.file.{Files, Paths}
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
class ConsistencySuite extends munit.FunSuite {

  private val root  = Paths.get(sys.props.getOrElse("scalaui.root", ".")).toAbsolutePath
  private val shim  = root.resolve("modules/renderer-apple/shim")
  private val scala = root.resolve("modules/renderer-apple/src/main/scala/scalaui/renderer/apple")

  private def read(p: java.nio.file.Path): String = {
    assert(Files.exists(p), s"expected $p to exist; is scalaui.root set correctly? (root=$root)")
    Files.readAllLines(p).asScala.mkString("\n")
  }

  private lazy val header  = Parse.cHeader(read(shim.resolve("include/scalaui_apple.h")))
  private lazy val appkit  = Parse.swiftShim(read(shim.resolve("Sources/Shim+AppKit.swift")))
  private lazy val uikit   = Parse.swiftShim(read(shim.resolve("Sources/Shim+UIKit.swift")))
  private lazy val externs = Parse.scalaExterns(read(scala.resolve("Shim.scala")))

  private def byName(r: Parse.Result): Map[String, Parse.Decl] =
    r.decls.map(d => d.name -> d).toMap

  test("every declaration in every artefact parses") {
    // An unparsed declaration is not a pass: it is a declaration this suite is silently not
    // checking, which is exactly the hole it exists to close.
    assertEquals(header.unparsed, Nil, "unparsed C declarations")
    assertEquals(appkit.unparsed, Nil, "unparsed AppKit signatures")
    assertEquals(uikit.unparsed, Nil, "unparsed UIKit signatures")
    assertEquals(externs.unparsed, Nil, "unparsed Scala externs")
  }

  test("Abi describes exactly what the C header declares") {
    val spec = Abi.all.map(f => f.name -> Parse.Decl.of(f)).toMap
    val head = byName(header)
    assertEquals(head.keySet, spec.keySet, "function names")
    spec.foreach { (name, d) => assertEquals(head(name), d, s"signature of $name") }
  }

  test("the Scala externs match the C header") {
    import Abi.repr
    val head = byName(header)
    val sc   = byName(externs)
    assertEquals(sc.keySet, head.keySet, "Scala binds exactly the header's functions")
    // Compared at `Repr`, not at `CType`: Scala Native binds `sui_handle` and
    // `const uint8_t *` to the same `Ptr[Byte]`, so demanding it tell them apart would be
    // a false positive forever. See Abi.Repr — the distinctions that survive are the ones
    // that cost a wrong register.
    head.foreach { (name, d) =>
      assertEquals(sc(name).ret.repr, d.ret.repr, s"return type of $name")
      assertEquals(sc(name).params.map(_.repr), d.params.map(_.repr), s"parameters of $name")
    }
  }

  test("both Swift shims export the C header's functions, and agree with it") {
    List("AppKit" -> appkit, "UIKit" -> uikit).foreach { (label, shimResult) =>
      val head     = byName(header)
      val exported = byName(shimResult)
      val missing  = head.keySet -- exported.keySet
      val extra    = exported.keySet -- head.keySet -- Abi.swiftOnly
      assertEquals(missing, Set.empty[String], s"$label does not export")
      assertEquals(extra, Set.empty[String], s"$label exports undeclared functions")
      head.foreach { (name, d) =>
        // Swift has no `void` return in its signature, so compare it as such.
        assertEquals(exported(name), d, s"$label signature of $name")
      }
    }
  }

  test("the two shims export the same surface") {
    val a = byName(appkit).keySet -- Abi.swiftOnly
    val u = byName(uikit).keySet -- Abi.swiftOnly
    assertEquals(a, u, "AppKit and UIKit must export the same functions")
  }

  test("nothing crosses the boundary that cannot cross it safely") {
    // The rule S3 and S4 paid for: no struct by value, in either direction. The type
    // vocabulary enforces it by construction, so this asserts the vocabulary has not grown
    // a hole rather than re-deriving the finding.
    val allowed = Abi.CType.values.toSet
    Abi.all.foreach { fn =>
      assert(allowed.contains(fn.ret), s"${fn.name} returns an unsupported type")
      fn.params.foreach(p => assert(allowed.contains(p.tpe), s"${fn.name}.${p.name}"))
    }
  }
}
