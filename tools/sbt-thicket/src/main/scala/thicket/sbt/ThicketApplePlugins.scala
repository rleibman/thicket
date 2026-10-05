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

package thicket.sbt

import sbt.*
import sbt.Keys.{dependencyClasspath, fileConverter, libraryDependencies, target}
import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport.nativeConfig
import thicket.sbt.ThicketGtkPlugin.autoImport.thicketVersion
import thicket.sbt.buildinfo.BuildInfo

/** The Swift shim, unpacked from the published `thicket-renderer-apple` jar.
  *
  * An Apple app needs `libthicketapple.a` as well as Scala artefacts: the renderer's `sui_*` symbols are Swift, built
  * by a shell script in Thicket's repository, and they stay undefined until that archive is linked in. Thicket's build
  * puts both slices and the headers inside the renderer's jar at package time (#41), under `thicket-apple/`; this finds
  * that jar on the resolved classpath and unpacks them into `thicket-apple/` under the project's sbt `target` (which in
  * sbt 2 is below `target/out/`, so ask `print thicketAppleShim` rather than assume), and nothing outside the
  * dependency graph has to be cloned or built.
  */
object ThicketAppleShim {

  /** `thicket-apple/` under `into`, holding `macos/`, `ios-sim/` and `include/`. */
  def unpack(
    classpath: Seq[File],
    into:      File
  ): File = {
    val jar = classpath
      .find(_.getName.startsWith("thicket-renderer-apple"))
      .getOrElse(
        sys.error(
          "thicket-renderer-apple is not on the classpath, so there is no Swift shim to link. " +
            "Enable ThicketMacPlugin or ThicketIosPlugin, which add it."
        )
      )
    IO.unzip(jar, into, new SimpleFilter(_.startsWith("thicket-apple/")))
    val dir = into / "thicket-apple"
    if !(dir / "include" / "thicket_apple.h").exists then
      sys.error(s"$jar has no Swift shim in it. It was published without one — by a Thicket older than #41?")
    dir
  }

  val unpackTask: Def.Initialize[Task[File]] = Def.task {
    val conv = fileConverter.value
    val jars = (Compile / dependencyClasspath).value.map(a => conv.toPath(a.data).toFile)
    unpack(jars, target.value)
  }

  def dependencies(version: String): Seq[ModuleID] =
    Seq(
      "dev.thicket" % "thicket-core_native0.5_3"           % version,
      "dev.thicket" % "thicket-renderer-apple_native0.5_3" % version
    )

}

/** A macOS (AppKit) app: `enablePlugins(ThicketMacPlugin)`. A plain binary, like a GTK one — AppKit apps own `main`.
  *
  * Adds `thicket-core` and `thicket-renderer-apple` at this plugin's version, unpacks the Swift shim from the
  * renderer's jar, and links it with `ThicketNativeFlags.apple`, the definition Thicket's own build uses.
  */
object ThicketMacPlugin extends AutoPlugin {

  override def requires: Plugins = ScalaNativePlugin

  override def trigger: PluginTrigger = noTrigger

  object autoImport {

    /** Unpacks the Swift shim from the resolved renderer jar and returns the directory it went to.
      *
      * `@transient`: unpacking writes into `target/`, which is a side effect to repeat rather than a result to cache —
      * and sbt 2 will not cache a `File` in any case.
      */
    @transient
    lazy val thicketAppleShim = taskKey[File]("the Swift shim, unpacked from thicket-renderer-apple")

    lazy val thicketMacSettings: Seq[Setting[?]] = Seq(
      thicketVersion := BuildInfo.version,
      libraryDependencies ++= ThicketAppleShim.dependencies(thicketVersion.value),
      thicketAppleShim := ThicketAppleShim.unpackTask.value,
      // Uncached: it depends on files unpacked into `target/`, and a NativeConfig has no JSON form for sbt 2's cache.
      nativeConfig := Def.uncached {
        val shim = thicketAppleShim.value
        ThicketNativeFlags.apple(shimLibDir = shim / "macos", shimIncludeDir = shim / "include")(nativeConfig.value)
      }
    )

  }

  import autoImport.*

  override def projectSettings: Seq[Setting[?]] = thicketMacSettings

}

/** An iOS app's Scala half: `enablePlugins(ThicketIosPlugin)`.
  *
  * iOS is not a binary: the Swift host owns `main` (a scene delegate cannot live in a static archive, S3), so this
  * links a static library with `ThicketNativeFlags.ios`, and the host's build script links that, the shim and the host
  * together. `thicketAppleShim` unpacks the shim for it and returns the directory — `ios-sim/libthicketapple.a` and
  * `include/` under it; `print thicketAppleShim` says where.
  */
object ThicketIosPlugin extends AutoPlugin {

  override def requires: Plugins = ScalaNativePlugin

  override def trigger: PluginTrigger = noTrigger

  object autoImport {

    lazy val thicketIosSettings: Seq[Setting[?]] = Seq(
      thicketVersion := BuildInfo.version,
      libraryDependencies ++= ThicketAppleShim.dependencies(thicketVersion.value),
      ThicketMacPlugin.autoImport.thicketAppleShim := ThicketAppleShim.unpackTask.value,
      nativeConfig ~= ThicketNativeFlags.ios()
    )

  }

  import autoImport.*

  override def projectSettings: Seq[Setting[?]] = thicketIosSettings

}
