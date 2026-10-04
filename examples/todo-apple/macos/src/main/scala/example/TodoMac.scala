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

package example

import thicket.renderer.apple.AppleApp

/** The macOS (AppKit) host for [[TodoApp]].
  *
  * AppKit apps own their own process: `main` starts, `AppleApp.run` hands the thread to `NSApp.run()`, and never
  * returns. Compare [[TodoIos]], where the process is already running by the time Scala is reached.
  */
object TodoMac {

  def main(args: Array[String]): Unit =
    // `LazyProbe` is a 10 000-row screen and nothing else: the shared TodoApp cannot start
    // on Apple until Forgejo #9, and #5's measurement should not be hostage to that.
    if sys.env.contains("THICKET_POSTTEST") then
      AppleApp.run("postToUi", 320, 120)(PostProbe.build(sys.env("THICKET_POSTTEST")))
    else if sys.env.contains("THICKET_LAZYTEST") then AppleApp.run("10 000 rows", 480, 640)(LazyProbe.build())
    else AppleApp.run("Todo", 480, 460)(AppleSelfTest.build())

}
