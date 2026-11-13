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

import java.nio.file.{Files, StandardCopyOption}
import sbt.*
import sbt.Keys.*
import scala.sys.process.Process
import thicket.sbt.ThicketGtkPlugin.autoImport.thicketVersion
import thicket.sbt.buildinfo.BuildInfo

/** `sbt-thicket` for Android: sbt compiles the Scala, the app's own Gradle project packages it (`docs/18`, option A).
  *
  * ```scala
  * // project/plugins.sbt
  * addSbtPlugin("net.leibman" % "sbt-thicket" % "<version>")
  *
  * // build.sbt
  * lazy val app = project.in(file(".")).enablePlugins(ThicketAndroidPlugin)
  * ```
  *
  * Then `sbt thicketApk` builds a debug APK, `sbt thicketApkRelease` the R8-shrunk one.
  *
  * Why two tools: Gradle's `scala` plugin cannot be applied to an Android module (it applies `JavaPlugin`, which
  * collides with AGP's configurations; measured in phase 0, S2), and nothing but Gradle packages an Android app well.
  * So this plugin compiles, and hands Gradle a directory of jars: the app's own, Thicket's, Scala's. The Gradle project
  * is the app's (`templates/hello-thicket-android/android`), with the three settings Scala needs on Android already in
  * it.
  *
  *   - `thicket-renderer-android` comes in at this plugin's own version, as on GTK.
  *   - `-release 17`: Scala 3.9's lowest Java output version, which AGP 9 at minSdk 26 accepts.
  *   - The SDK's `android.jar` is on the compile classpath only (an app's Activity extends `android.app.Activity`),
  *     never copied into the APK: the device has the real one.
  *   - `exportJars`: so the app's own classes reach Gradle as a jar, like everything else.
  *
  * `noTrigger`: enabling it on one project in a cross-platform build does not make the others Android apps.
  */
object ThicketAndroidPlugin extends AutoPlugin {

  override def requires: Plugins = plugins.JvmPlugin

  override def trigger: PluginTrigger = noTrigger

  object autoImport {

    lazy val thicketAndroidSdk =
      settingKey[File]("the Android SDK: ANDROID_HOME, else ANDROID_SDK_ROOT, else the platform's default location")
    lazy val thicketAndroidPlatform =
      settingKey[String]("the SDK platform whose android.jar the Scala is compiled against, e.g. android-36")
    lazy val thicketGradleProject =
      settingKey[File]("the app's Gradle project, which packages the APK (default: android/ beside build.sbt)")
    lazy val thicketGradleJavaHome =
      settingKey[Option[File]]("the JDK Gradle runs on (default: JAVA_HOME). AGP needs a non-EA JDK 17 or later")
    lazy val thicketAndroidJars =
      taskKey[Seq[File]]("write the app's runtime jars into the Gradle project's app/libs, and return them")
    lazy val thicketApk = taskKey[File]("build a debug APK with the app's Gradle project, and return it")
    lazy val thicketApkRelease =
      taskKey[File]("build a release APK (R8-shrunk; signed as the app's Gradle project says) and return it")

    lazy val thicketAndroidSettings: Seq[Setting[?]] = Seq(
      thicketVersion                               := BuildInfo.version,
      libraryDependencies += BuildInfo.organization % "thicket-renderer-android_3" % thicketVersion.value,
      scalacOptions ++= Seq("-release", "17"),
      exportJars             := true,
      thicketAndroidSdk      := ThicketAndroidPlugin.defaultSdk,
      thicketAndroidPlatform := "android-36",
      thicketGradleProject   := baseDirectory.value / "android",
      thicketGradleJavaHome  := sys.env.get("JAVA_HOME").map(file),
      // `Def.uncached`: an `Attributed` classpath entry has no JSON format for sbt 2's cache. The jar is the SDK's,
      // read where it is; S2's note that sbt 2 cannot put a `File` on a classpath predates `toVirtualFile` working here.
      Compile / unmanagedJars += Def.uncached {
        val jar = androidJar(thicketAndroidSdk.value, thicketAndroidPlatform.value)
        Attributed.blank[xsbti.HashedVirtualFileRef](fileConverter.value.toVirtualFile(jar.toPath))
      },
      thicketAndroidJars := Def.uncached {
        val converter = fileConverter.value
        val skip = androidJar(thicketAndroidSdk.value, thicketAndroidPlatform.value).getCanonicalFile
        val jars = (Runtime / fullClasspath).value
          .map(a => converter.toPath(a.data).toFile)
          .filter(f => f.getName.endsWith(".jar") && f.getCanonicalFile != skip)
        val libs = thicketGradleProject.value / "app" / "libs"
        IO.createDirectory(libs)
        // A jar left over from an earlier build would still be packaged.
        IO.delete((libs * "*.jar").get())
        // sbt 2's jars are symlinks into a content-addressed store; copy what they point at.
        val out = jars.map { jar =>
          val to = libs / jar.getName
          Files.copy(jar.toPath.toRealPath(), to.toPath, StandardCopyOption.REPLACE_EXISTING)
          to
        }
        streams.value.log.info(s"thicket: ${out.size} jars into $libs")
        out
      },
      thicketApk := Def.uncached {
        val _ = thicketAndroidJars.value
        gradle(thicketGradleProject.value, thicketGradleJavaHome.value, "debug", streams.value.log)
      },
      thicketApkRelease := Def.uncached {
        val _ = thicketAndroidJars.value
        gradle(thicketGradleProject.value, thicketGradleJavaHome.value, "release", streams.value.log)
      }
    )

  }

  import autoImport.*

  override def projectSettings: Seq[Setting[?]] = thicketAndroidSettings

  private def defaultSdk: File = {
    val home = file(sys.props("user.home"))
    sys.env
      .get("ANDROID_HOME")
      .orElse(sys.env.get("ANDROID_SDK_ROOT"))
      .map(file)
      .getOrElse(
        if sys.props.getOrElse("os.name", "").toLowerCase.contains("mac") then home / "Library" / "Android" / "sdk"
        else home / "Android" / "Sdk"
      )
  }

  private def androidJar(
    sdk:      File,
    platform: String
  ): File = {
    val jar = sdk / "platforms" / platform / "android.jar"
    if !jar.isFile then
      sys.error(
        s"thicket: no $jar. Install it with `sdkmanager \"platforms;$platform\"`, or point ANDROID_HOME (or " +
          "thicketAndroidSdk) at the SDK."
      )
    jar
  }

  /** `./gradlew assemble<Variant>` in the app's Gradle project; the APK it wrote. */
  private def gradle(
    project:  File,
    javaHome: Option[File],
    variant:  String,
    log:      Logger
  ): File = {
    val isWindows = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")
    val wrapper = if isWindows then project / "gradlew.bat" else project / "gradlew"
    if !wrapper.isFile then sys.error(s"thicket: no Gradle wrapper at $wrapper (thicketGradleProject)")
    if !isWindows then wrapper.setExecutable(true)
    val task = s"assemble${variant.capitalize}"
    val env = javaHome.map(h => "JAVA_HOME" -> h.getAbsolutePath).toSeq
    val cmd =
      if isWindows then Seq("cmd", "/c", wrapper.getAbsolutePath, task, "--console=plain", "-q")
      else Seq(wrapper.getAbsolutePath, task, "--console=plain", "-q")
    log.info(s"thicket: gradle $task in $project")
    val code = Process(cmd, project, env*).!(log)
    if code != 0 then sys.error(s"thicket: gradle $task failed (exit $code)")
    val apks = (project / "app" / "build" / "outputs" / "apk" / variant * "*.apk").get()
    val apk = apks.headOption.getOrElse(sys.error(s"thicket: gradle $task wrote no APK"))
    log.info(s"thicket: ${apk.length} bytes, $apk")
    apk
  }

}
