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

package thicket.core

import thicket.core.dsl.*
import thicket.renderer.{Icon, MaterialSymbols, SvgPath, WidgetKind}
import thicket.renderer.SvgPath.Cmd
import thicket.signals.{Checks, Owner}
import zio.test.*

/** Icons (#61): the curated set, the Android artwork it is drawn from, and `IconButton`.
  *
  * Most of the risk is in the artwork, which is data parsed at runtime on a phone: a path that does not parse, or that
  * parses to the wrong shape, is an invisible or garbled button found by a user. So every bundled path is parsed here,
  * on every backend, and the parser's trickier rules are pinned by exact results.
  */
object IconSpec extends ZIOSpecDefault {

  def spec =
    suite("Icons")(
      test("every curated icon has Android artwork, and every path parses inside its view box") {
        val chk = Checks()
        Icon.curated.foreach { icon =>
          MaterialSymbols.pathFor(icon) match {
            case None => chk.yes(false, s"$icon has no artwork")
            case Some(d) =>
              val cmds = scala.util.Try(SvgPath.parse(d))
              chk.yes(cmds.isSuccess, s"$icon: ${cmds.failed.map(_.getMessage).getOrElse("")}")
              cmds.foreach { cs =>
                chk.yes(cs.headOption.exists(_.isInstanceOf[Cmd.MoveTo]), s"$icon starts with a moveto")
                chk.yes(cs.contains(Cmd.Close), s"$icon closes its shape")
                val points = cs.collect {
                  case Cmd.MoveTo(x, y)       => Seq((x, y))
                  case Cmd.LineTo(x, y)       => Seq((x, y))
                  case Cmd.QuadTo(a, b, x, y) => Seq((a, b), (x, y))
                }.flatten
                val inside = points.forall(
                  (
                    x,
                    y
                  ) => x >= 0 && x <= 960 && y >= -960 && y <= 0
                )
                chk.yes(
                  inside,
                  s"$icon stays inside 0 -960 960 960: ${points
                      .filterNot(
                        (
                          x,
                          y
                        ) => x >= 0 && x <= 960 && y >= -960 && y <= 0
                      ).take(3)}"
                )
              }
          }
        }
        chk.eq(Icon.curated.size, 24)
        chk.result
      },
      test("a real icon parses to exactly the shape it describes") {
        val chk = Checks()
        // Material Symbols' `check`: an absolute moveto whose second pair is an implicit
        // lineto, then relative linetos repeated without letters.
        val cmds = SvgPath.parse(MaterialSymbols.pathFor(Icon.Check).get)
        chk.eq(
          cmds,
          Vector(
            Cmd.MoveTo(382, -240),
            Cmd.LineTo(154, -468),
            Cmd.LineTo(211, -525),
            Cmd.LineTo(382, -354),
            Cmd.LineTo(749, -721),
            Cmd.LineTo(806, -664),
            Cmd.LineTo(382, -240),
            Cmd.Close
          )
        )
        chk.result
      },
      test("T reflects the previous control point; relative q is from the current point") {
        val chk = Checks()
        chk.eq(
          SvgPath.parse("M0 0Q10 10 20 0T40 0"),
          Vector(Cmd.MoveTo(0, 0), Cmd.QuadTo(10, 10, 20, 0), Cmd.QuadTo(30, -10, 40, 0))
        )
        chk.eq(
          SvgPath.parse("M100 100q10 10 20 0t20 0"),
          Vector(Cmd.MoveTo(100, 100), Cmd.QuadTo(110, 110, 120, 100), Cmd.QuadTo(130, 90, 140, 100))
        )
        // T after a non-quadratic: the control point is the current point itself.
        chk.eq(SvgPath.parse("M0 0L10 0T20 0").last, Cmd.QuadTo(10, 0, 20, 0))
        chk.result
      },
      test("compact numbers: a sign or a second dot starts the next number") {
        val chk = Checks()
        chk.eq(SvgPath.parse("M.5.5l-1-1Z"), Vector(Cmd.MoveTo(0.5f, 0.5f), Cmd.LineTo(-0.5f, -0.5f), Cmd.Close))
        chk.result
      },
      test("a command the bundled set does not use is refused, not guessed at") {
        val chk = Checks()
        chk.yes(scala.util.Try(SvgPath.parse("M0 0C1 1 2 2 3 3")).isFailure)
        chk.yes(scala.util.Try(SvgPath.parse("M0 0A1 1 0 0 1 2 2")).isFailure)
        chk.result
      },
      test("an IconButton carries its icon and its label, and a tap reaches the app") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        var taps = 0
        val m = Reconciler.mount(r, IconButton(Icon.Delete, "Delete item")(taps += 1))

        chk.eq(r.kind(m.handle), WidgetKind.IconButton)
        chk.eq(r.nodes(m.handle).props("icon"), "Delete")
        chk.eq(r.nodes(m.handle).props("label"), "Delete item")
        r.nodes(m.handle).onTap.foreach(_())
        chk.eq(taps, 1)
        o.dispose()
        chk.result
      },
      test("an IconButton without a label is refused where it is written") {
        val chk = Checks()
        chk.yes(scala.util.Try(IconButton(Icon.Add, "  ")(())).isFailure)
        chk.result
      },
      test("a platform icon names its own artwork, so there is none bundled for it") {
        val chk = Checks()
        chk.eq(MaterialSymbols.pathFor(Icon.Platform("emoji-symbolic", "face.smiling", "ic_smile")), None)
        chk.result
      }
    )

}
