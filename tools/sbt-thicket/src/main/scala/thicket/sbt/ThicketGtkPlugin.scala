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
import sbt.Keys.libraryDependencies
import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport.nativeConfig
import thicket.sbt.buildinfo.BuildInfo

/** `sbt-thicket`: everything a Thicket GTK app needs from Thicket's build, published instead of copied.
  *
  * ```scala
  * // project/plugins.sbt
  * addSbtPlugin("dev.thicket" % "sbt-thicket" % "<version>")
  *
  * // build.sbt
  * lazy val app = project.in(file(".")).enablePlugins(ThicketGtkPlugin)
  * ```
  *
  * That is the whole of it. `ScalaNativePlugin` is a dependency of this plugin, so an app's `project/plugins.sbt` no
  * longer adds it and `enablePlugins(ThicketGtkPlugin)` enables it; `thicket-core` and `thicket-renderer-gtk` come in
  * at this plugin's own version, which is the version of the framework it was built from; and `nativeConfig` carries
  * the GTK link settings from `ThicketNativeFlags`, the same definition Thicket's own `build.sbt` uses.
  *
  * `noTrigger`: enabling Scala Native in a build does not make every project in it a GTK app.
  *
  * There is no Apple equivalent yet, and the reason is not flags — see `README.md` and #41.
  */
object ThicketGtkPlugin extends AutoPlugin {

  override def requires: Plugins = ScalaNativePlugin

  override def trigger: PluginTrigger = noTrigger

  object autoImport {

    /** The version of Thicket this plugin was built from, and the version its dependencies default to. The plugin and
      * the framework come out of one build, so they cannot disagree.
      */
    lazy val thicketVersion = settingKey[String]("the version of the Thicket artefacts to depend on")

    /** The settings, for a project that wants them without enabling the plugin — identical to what enabling it adds. */
    lazy val thicketGtkSettings: Seq[Setting[?]] = Seq(
      thicketVersion := BuildInfo.version,
      // sbt 2 has no `%%%`, so Scala Native artefacts carry explicit suffixes.
      libraryDependencies ++= Seq(
        "dev.thicket" % "thicket-core_native0.5_3"         % thicketVersion.value,
        "dev.thicket" % "thicket-renderer-gtk_native0.5_3" % thicketVersion.value
      ),
      nativeConfig ~= ThicketNativeFlags.gtk
    )

  }

  import autoImport.*

  override def projectSettings: Seq[Setting[?]] = thicketGtkSettings

}
