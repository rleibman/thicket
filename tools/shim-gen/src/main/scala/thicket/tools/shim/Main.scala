package thicket.tools.shim

import java.nio.file.{Files, Path, Paths}

/** Writes the generated artefacts into the repository, or checks that they are current.
  *
  * {{{
  * sbt shimGen/run                          # regenerate thicket_apple.h and Shim.scala in place
  * sbt "shimGen/run --check"                # exit 1 if either differs from what Abi generates
  * sbt "shimGen/run --scaffold <dir>"       # Swift @_cdecl signatures for a new shim, into <dir>
  * }}}
  *
  * The header and the Scala externs are generated artefacts, checked in so that a build of `rendererApple` needs
  * nothing but the repository. `GenerateSpec` fails `sbt test` when either one differs from what [[Abi]] generates,
  * which is what catches a hand edit.
  *
  * The Swift shims are not written here: only their signatures are mechanical, and the bodies are hand-written per
  * toolkit (see [[Emit]]). `--scaffold` writes signatures with `fatalError` bodies for someone starting a shim, and
  * never into `Sources/`.
  */
object Main {

  /** The generated files, as paths relative to the repository root. */
  val generated: List[(String, () => String)] = List(
    "modules/renderer-apple/shim/include/thicket_apple.h"                     -> (() => Emit.cHeader()),
    "modules/renderer-apple/src/main/scala/thicket/renderer/apple/Shim.scala" -> (() => Emit.scalaExterns())
  )

  private def root: Path = Paths.get(sys.props.getOrElse("thicket.root", ".")).toAbsolutePath.normalize

  private def current(p: Path): Option[String] = if Files.exists(p) then Some(Files.readString(p)) else None

  def main(args: Array[String]): Unit =
    args.toList match {
      case Nil =>
        generated.foreach {
          (
            rel,
            text
          ) =>
            val path = root.resolve(rel)
            val want = text()
            if current(path).contains(want) then println(s"unchanged $rel")
            else {
              Files.createDirectories(path.getParent)
              Files.writeString(path, want)
              println(s"wrote     $rel")
            }
        }
        println(s"${Abi.all.size} functions, ${Abi.kinds.size} kind codes.")

      case "--check" :: Nil =>
        val stale = generated.collect { case (rel, text) if !current(root.resolve(rel)).contains(text()) => rel }
        if stale.isEmpty then println("generated files are current.")
        else {
          stale.foreach(rel => System.err.println(s"stale: $rel"))
          System.err.println("Edit Abi.scala, not the generated file, then run `sbt shimGen/run`.")
          sys.exit(1)
        }

      case "--scaffold" :: dir :: Nil =>
        val path = Paths.get(dir).resolve("Shim+New.swift")
        Files.createDirectories(path.getParent)
        Files.writeString(path, Emit.swiftSignatures())
        println(s"wrote $path")

      case other =>
        System.err.println(s"unrecognised arguments: ${other.mkString(" ")}")
        System.err.println("usage: shimGen/run [--check | --scaffold <dir>]")
        sys.exit(2)
    }

}
