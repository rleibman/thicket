package thicket.tools.shim

import java.nio.file.{Files, Path, Paths}
import zio.test.*
import scala.jdk.CollectionConverters.*

/** Checks each iOS example's entry symbol agrees across Scala, C and Swift.
  *
  * Same hazard as [[ConsistencySpec]], one layer out. An iOS example is three files in three languages that meet only
  * at link time: Scala exports a symbol with `@exported`, the bridging header declares it, and the Swift host calls it.
  * The linker matches by **name only**, so a rename in one place is not a compile error anywhere — it is an undefined
  * symbol on a Mac, which is the machine least convenient to discover it on, and nothing on a Linux box would have
  * noticed.
  *
  * Cheap, and it generalises: a new example is covered by adding its directory below.
  */
object HostEntrySpec extends ZIOSpecDefault {

  private val root = Paths.get(sys.props.getOrElse("thicket.root", ".")).toAbsolutePath

  /** Each iOS example: where its Scala host, bridging header and Swift host live. */
  private val examples = List(
    "todo"    -> root.resolve("examples/todo-apple/ios"),
    "gallery" -> root.resolve("examples/gallery/ios")
  )

  private def read(p: Path): String = {
    if !Files.exists(p) then
      throw new IllegalStateException(s"expected $p to exist; is thicket.root set correctly? (root=$root)")
    Files.readAllLines(p).asScala.mkString("\n")
  }

  private def scalaSources(dir: Path): String =
    Files
      .walk(dir.resolve("src"))
      .iterator
      .asScala
      .filter(p => p.toString.endsWith(".scala"))
      .map(read)
      .mkString("\n")

  private val exported = """@exported\("([A-Za-z0-9_]+)"\)""".r
  private val externDecl = """extern\s+void\s+([A-Za-z0-9_]+)\s*\(\s*void\s*\)\s*;""".r
  private val swiftCall = """\b(thicket_[A-Za-z0-9_]*start)\s*\(\s*\)""".r

  def spec =
    suite("iOS host entry symbols")(
      examples.map {
        (
          name,
          dir
        ) =>
          test(s"$name: Scala's @exported, the bridging header and the Swift host agree") {
            val scala = scalaSources(dir)
            val header = read(dir.resolve("ios-app/Sources/Thicket-Bridging-Header.h"))
            val swift = read(dir.resolve("ios-app/Sources/Host.swift"))

            val exportedNames = exported.findAllMatchIn(scala).map(_.group(1)).toSet
            val declared = externDecl.findAllMatchIn(header).map(_.group(1)).toSet
            val called = swiftCall.findAllMatchIn(swift).map(_.group(1)).toSet

            // One entry point per example, so this is a single name rather than a set
            // relation. If an example ever needs two, this is the assertion to revisit
            // deliberately instead of loosening by accident.
            val entry = exportedNames.filter(_.endsWith("_start"))

            assertTrue(
              entry.size == 1,
              // The header also declares ScalaNativeInit and sui_set_root_view, so it is a
              // superset rather than an equal.
              entry.subsetOf(declared),
              called == entry
            )
          }
      }*
    )

}
