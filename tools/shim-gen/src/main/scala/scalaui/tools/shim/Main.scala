package scalaui.tools.shim

import java.nio.file.{Files, Paths, StandardOpenOption}

/** Writes the generated artefacts.
  *
  * `sbt "shimGen/run <outputDir>"`, or with no argument a dry run that prints what it would
  * write. Nothing here overwrites the checked-in shim: adopting the generated files is a
  * deliberate step, taken on a machine that can compile Swift, and
  * [[GenerateSuite]] is what says the step is safe.
  */
object Main {

  def main(args: Array[String]): Unit = {
    val outputs = List(
      "scalaui_apple.h" -> Emit.cHeader(),
      "Shim.scala"      -> Emit.scalaExterns(),
      "Shim+New.swift"  -> Emit.swiftSignatures()
    )

    args.headOption match {
      case None =>
        outputs.foreach { (name, text) =>
          println(s"--- $name (${text.linesIterator.size} lines) ---")
        }
        println(s"\n${Abi.all.size} functions described.")
        println("Pass an output directory to write them; nothing is written without one.")

      case Some(dir) =>
        val out = Paths.get(dir)
        Files.createDirectories(out)
        outputs.foreach { (name, text) =>
          val path = out.resolve(name)
          Files.writeString(
            path,
            text,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
          )
          println(s"wrote $path")
        }
    }
  }
}
