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

import java.nio.file.{Files, Path, Paths}

/** Writes the generated artefacts into the repository, or checks that they are current.
  *
  * {{{
  * sbt shimGen/run                          # regenerate the renderer, http and device headers and externs
  * sbt "shimGen/run --check"                # exit 1 if any differs from what Abi or HttpAbi generates
  * sbt "shimGen/run --scaffold <dir>"       # Swift @_cdecl signatures for a new shim, into <dir>
  * }}}
  *
  * The headers and the Scala externs are generated artefacts, checked in so that a build of `rendererApple` needs
  * nothing but the repository. `GenerateSpec` fails `sbt test` when any one differs from what [[Abi]] or [[HttpAbi]]
  * generates, which is what catches a hand edit.
  *
  * The Swift shims are not written here: only their signatures are mechanical, and the bodies are hand-written per
  * toolkit (see [[Emit]]). `--scaffold` writes signatures with `fatalError` bodies for someone starting a shim, and
  * never into `Sources/`.
  */
object Main {

  /** The generated files, as paths relative to the repository root. */
  val generated: List[(String, () => String)] = List(
    "modules/renderer-apple/shim/include/thicket_apple.h"                     -> (() => Emit.cHeader()),
    "modules/renderer-apple/src/main/scala/thicket/renderer/apple/Shim.scala" -> (() => Emit.scalaExterns()),
    "modules/http-apple/shim/include/thicket_http.h"                          -> (() => Emit.httpHeader()),
    "modules/http-apple/src/main/scala/thicket/http/apple/HttpShim.scala"     -> (() => Emit.httpScalaExterns()),
    "modules/device-apple/shim/include/thicket_device.h"                      -> (() => Emit.deviceHeader()),
    "modules/device-apple/src/main/scala/thicket/device/apple/DeviceShim.scala" -> (() => Emit.deviceScalaExterns())
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
        println(s"${Abi.all.size} sui functions, ${Abi.kinds.size} kind codes, ${HttpAbi.all.size} thk_http functions, ${DeviceAbi.all.size} thk_dev functions.")

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
