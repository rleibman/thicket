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

import java.io.File
import scala.scalanative.build.{BuildTarget, GC, LTO, Mode, NativeConfig}
import scala.sys.process.{Process, ProcessLogger}

/** The one definition of Thicket's Scala Native link settings.
  *
  * This file is compiled **twice, from this one copy**: once into the published `sbt-thicket` plugin, and once into
  * Thicket's own meta-build, which adds this directory to `Compile / unmanagedSourceDirectories` (see
  * `project/build.sbt`). `build.sbt`'s `gtkNativeSettings` and the plugin's `thicketGtkSettings` therefore call the
  * same method on the same bytes, and the drift #40 is about cannot happen: there is nothing to keep in step.
  *
  * The cost, stated plainly: the meta-build will not load at all if that source directory moves or the path in
  * `project/build.sbt` goes stale, and anything this file references has to exist on both compile classpaths — sbt
  * itself and Scala Native's `tools` are the whole of it. A circular alternative (Thicket's build using the published
  * plugin) would have needed a release of the plugin before the renderer could build.
  *
  * Nothing here runs at build-load time. `pkg-config` and `xcrun` are invoked when a link is configured, so a machine
  * that is not Linux never probes for GTK and a machine that is not a Mac never calls `xcrun`.
  */
object ThicketNativeFlags {

  /** The pkg-config modules the GTK renderer and its transitive bindings need. */
  val gtkPackages: Seq[String] = Seq("gtk4", "libadwaita-1")

  /** GTK's C glue (and that of graphene and friends) is compiled by Scala Native in whichever project performs the
    * link, so every downstream project needs these flags — `nativeConfig` is not inherited through `dependsOn`.
    *
    *   - `LTO.none`: link-time optimisation across GTK's headers is not something this has been built against.
    *   - `Mode.debug`: the default for an app being developed; a release build overrides it after this.
    *   - `GC.immix`: immix is Scala Native's default *unless* `SCALANATIVE_GC` says otherwise, and an environment
    *     variable deciding the GC is exactly the failure #40 describes — a wrong GC fails at link time with nothing
    *     pointing at the cause. Pinned, so it cannot.
    */
  def gtk(c: NativeConfig): NativeConfig = {
    c.withLTO(LTO.none)
      .withMode(Mode.debug)
      .withGC(GC.immix)
      .withCompileOptions(c.compileOptions ++ pkgConfig("--cflags"))
      .withLinkingOptions(c.linkingOptions ++ pkgConfig("--libs"))
  }

  /** AppKit (macOS). The Swift shim is a separate static library, because Scala Native's own clang invocation knows
    * nothing about Swift; `modules/renderer-apple/shim/build-shim.sh` builds it and these flags link it.
    *
    * @param shimLibDir
    *   the directory holding `libthicketapple.a`
    * @param shimIncludeDir
    *   the directory holding the shim's C header
    */
  def apple(
    shimLibDir:     File,
    shimIncludeDir: File
  )(
    c: NativeConfig
  ): NativeConfig = {
    c.withLTO(LTO.none)
      .withMode(Mode.debug)
      .withCompileOptions(c.compileOptions ++ Seq("-I" + shimIncludeDir.getAbsolutePath))
      .withLinkingOptions(
        c.linkingOptions ++ Seq(
          "-L" + shimLibDir.getAbsolutePath,
          "-lthicketapple",
          "-framework",
          "AppKit",
          "-framework",
          "Foundation",
          // Swift's own runtime, which the shim's objects need at link time.
          "-L/usr/lib/swift",
          "-Xlinker",
          "-rpath",
          "-Xlinker",
          "/usr/lib/swift"
        )
      )
  }

  /** The iOS simulator variant. Three differences from the macOS one, each forced:
    *
    *   - `libraryStatic`, because the host owns `@main` (iOS 27 requires UIScene adoption, and a scene delegate cannot
    *     live in the archive — S3);
    *   - `GC.immix`, the only GC that builds for iOS (S1);
    *   - `target.os -> "darwin"`. An iOS triple makes `target.os == "ios"`, javalib's `LinktimeInfo.isMac` accepts only
    *     "darwin"/"macosx", and `PosixThread` then calls `pthread_condattr_setclock`, which no Apple platform has (S1).
    *     Mandatory on every iOS target; without it the link fails.
    *
    * No linking options for the shim: a static archive is not linked, so the `sui_*` symbols stay undefined until the
    * Swift host resolves them against the UIKit shim.
    */
  def ios(
    triple: String = "arm64-apple-ios17.0-simulator",
    sdk:    String = "iphonesimulator"
  )(
    c: NativeConfig
  ): NativeConfig = {
    val flags = Seq("-target", triple, "-isysroot", xcrunSdkPath(sdk))
    c.withBuildTarget(BuildTarget.libraryStatic)
      .withGC(GC.immix)
      .withMode(Mode.debug)
      .withLTO(LTO.none)
      .withTargetTriple(triple)
      .withCompileOptions(c.compileOptions ++ flags)
      .withLinkingOptions(c.linkingOptions ++ flags)
      .withLinktimeProperties(
        c.linktimeProperties + ("scala.scalanative.meta.linktimeinfo.target.os" -> "darwin")
      )
  }

  /** `pkg-config`, with the failure said out loud.
    *
    * The point of routing this through one function is the error. Left to itself, a missing `libadwaita-1` fails
    * somewhere between a non-zero exit nobody reads and an undefined symbol at link time; here it names the package
    * that is missing and the command that installs it.
    */
  def pkgConfig(
    flag:     String,
    packages: Seq[String] = gtkPackages
  ): Seq[String] = {
    val args = "pkg-config" +: flag +: packages
    val err = new StringBuilder
    val out = new StringBuilder
    val logger =
      ProcessLogger(line => { out.append(line).append(' '); () }, line => { err.append(line).append('\n'); () })
    val code =
      try Process(args).!(logger)
      catch {
        case _: java.io.IOException =>
          sys.error(missing("`pkg-config` is not installed, or not on PATH.", ""))
      }
    if (code != 0) sys.error(missing(s"`${args.mkString(" ")}` exited with $code.", err.toString.trim))
    out.toString.trim.split("\\s+").filter(_.nonEmpty).toSeq
  }

  private def missing(
    what:   String,
    detail: String
  ): String = {
    val said = if (detail.isEmpty) "" else s"\n  it said: ${detail.linesIterator.mkString("\n           ")}\n"
    s"""|
        |Thicket's GTK renderer needs the GTK 4 and libadwaita development files, and they were not found.
        |
        |  $what
        |$said
        |Install them and build again:
        |
        |  Debian/Ubuntu  sudo apt install pkg-config libgtk-4-dev libadwaita-1-dev clang libunwind-dev
        |  Fedora         sudo dnf install pkgconf-pkg-config gtk4-devel libadwaita-devel clang
        |  Arch           sudo pacman -S pkgconf gtk4 libadwaita clang
        |  macOS/Homebrew brew install pkg-config gtk4 libadwaita
        |
        |Check it yourself with: pkg-config --libs ${gtkPackages.mkString(" ")}
        |""".stripMargin
  }

  /** The SDK path, asked of `xcrun` at link-configuration time rather than at build load, so a non-Apple machine never
    * runs it.
    */
  private def xcrunSdkPath(sdk: String): String = {
    try Process(Seq("xcrun", "--sdk", sdk, "--show-sdk-path")).!!.trim
    catch {
      case _: java.io.IOException =>
        sys.error(
          s"`xcrun` is not available, so the $sdk SDK path cannot be found. Thicket's Apple targets need Xcode and its command-line tools."
        )
    }
  }

}
