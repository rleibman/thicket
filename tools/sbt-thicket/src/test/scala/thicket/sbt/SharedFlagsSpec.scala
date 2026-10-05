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

import scala.scalanative.build.{GC, LTO, Mode, NativeConfig}
import zio.test.*
import zio.test.Assertion.*

/** What the shared link settings actually set.
  *
  * The other half of #40 — that `build.sbt` has not grown a second copy of these flags — is checked in
  * `project/build.sbt`, at build load, and not here. It was here first, reading `build.sbt` off the filesystem, and it
  * did not work: sbt reported "No tests to run" for every edit to `build.sbt`, because a build file is not one of a
  * test's inputs. A guard that silently stops running is worse than no guard.
  */
object SharedFlagsSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Any] =
    suite("ThicketNativeFlags")(
      test("the GTK packages are the renderer's own") {
        assert(ThicketNativeFlags.gtkPackages)(equalTo(Seq("gtk4", "libadwaita-1")))
      },
      suite("gtk")(
        test("LTO, mode and GC are pinned") {
          // The GC is the one #40 names: wrong, and the failure is at link time with nothing pointing at it. immix is
          // Scala Native's default only while SCALANATIVE_GC is unset, so pinning it is not redundant.
          val c = ThicketNativeFlags.gtk(NativeConfig.empty)
          assert(c.lto)(equalTo(LTO.none)) &&
          assert(c.mode)(equalTo(Mode.debug)) &&
          assert(c.gc)(equalTo(GC.immix))
        },
        test("GTK's own include and library flags are on the compile and link lines") {
          val c = ThicketNativeFlags.gtk(NativeConfig.empty)
          assert(c.compileOptions.exists(_.contains("gtk-4.0")))(isTrue) &&
          assert(c.linkingOptions)(contains("-lgtk-4")) &&
          assert(c.linkingOptions)(contains("-ladwaita-1"))
        },
        test("existing options are kept, not replaced") {
          val before = NativeConfig.empty.withCompileOptions(Seq("-DMINE")).withLinkingOptions(Seq("-lmine"))
          val c = ThicketNativeFlags.gtk(before)
          assert(c.compileOptions)(contains("-DMINE")) && assert(c.linkingOptions)(contains("-lmine"))
        }
      ) @@ TestAspect.ifEnvNotSet("THICKET_NO_GTK"),
      test("a missing package says which one and how to install it") {
        val e = scala.util.Try(ThicketNativeFlags.pkgConfig("--libs", Seq("thicket-no-such-package"))).failed.get
        assert(e.getMessage)(containsString("thicket-no-such-package")) &&
        assert(e.getMessage)(containsString("sudo apt install")) &&
        assert(e.getMessage)(containsString("libgtk-4-dev"))
      }
    )

}
