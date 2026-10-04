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
import thicket.signals.{Checks, Owner, Var}
import zio.test.*

/** Per-subtree theming.
  *
  * The subtle part is *when* a theme applies. Roles resolve to colours as an element is **built**, not as it is
  * rendered, so a `Provide` is simply the theme in scope during construction — and the trap is that regions build their
  * contents later, long after the enclosing `Provide` has returned.
  */
object ProvideSpec extends ZIOSpecDefault {

  private val red = Rgb(0xb0, 0x30, 0x30)
  private val blue = Rgb(0x20, 0x40, 0xc0)

  private def fillOf(
    r: TestRenderer,
    h: Int
  ): String = r.nodes(h).props.getOrElse("fill", "none")

  def spec =
    suite("Provide")(
      test("a provided theme applies inside and nowhere else") {
        val o = Owner(); given Owner = o
        Theme.install(Theme.platform)
        val chk = Checks()
        val r = TestRenderer()

        val m = Reconciler.mount(
          r,
          Column()(
            Button("outside")(()),
            Provide(Theme.platform.withColor(ColorRole.Accent, red))(Button("inside")(()))
          )
        )
        val kids = r.childrenOf(m.handle)

        chk.eq(fillOf(r, kids.head), "none", "outside the Provide the platform decides")
        chk.eq(fillOf(r, kids(1)), s"${red.r},${red.g},${red.b}")
        o.dispose()
        chk.result
      },
      test("it nests, and the inner one wins only inside itself") {
        val o = Owner(); given Owner = o
        Theme.install(Theme.platform)
        val chk = Checks()
        val r = TestRenderer()

        val m = Reconciler.mount(
          r,
          Provide(Theme.platform.withColor(ColorRole.Accent, red))(
            Column()(
              Button("outer")(()),
              Provide(Theme.platform.withColor(ColorRole.Accent, blue))(Button("inner")(())),
              Button("after the inner one")(())
            )
          )
        )
        val kids = r.childrenOf(m.handle)

        chk.eq(fillOf(r, kids.head), s"${red.r},${red.g},${red.b}")
        chk.eq(fillOf(r, kids(1)), s"${blue.r},${blue.g},${blue.b}")
        // The sibling after the inner Provide must be red again, not blue — which is the
        // whole reason the scope is a stack rather than a global set.
        chk.eq(fillOf(r, kids(2)), s"${red.r},${red.g},${red.b}", "the scope must pop")
        o.dispose()
        chk.result
      },
      test("a region inside a Provide stays themed when it rebuilds later") {
        val o = Owner(); given Owner = o
        Theme.install(Theme.platform)
        val chk = Checks()
        val r = TestRenderer()
        val visible = Var(false)

        val m = Reconciler.mount(
          r,
          Provide(Theme.platform.withColor(ColorRole.Accent, red))(
            Column()(Show(visible)(Button("later")(())))
          )
        )

        // Nothing built yet. The Provide has already returned by the time this flips, so a
        // naive construction-time scope would give the platform's colour here.
        visible.set(true)
        val built = r.childrenOf(m.handle)
        chk.eq(built.length, 1)
        chk.eq(
          fillOf(r, built.head),
          s"${red.r},${red.g},${red.b}",
          "a region rebuilds under the theme it was declared in"
        )
        o.dispose()
        chk.result
      },
      test("rows appended to a themed list are themed too") {
        val o = Owner(); given Owner = o
        Theme.install(Theme.platform)
        val chk = Checks()
        val r = TestRenderer()
        val items = Var(Seq(1))

        val m = Reconciler.mount(
          r,
          Provide(Theme.platform.withColor(ColorRole.Accent, red))(
            Column()(ForEach(items, key = (i: Int) => i)(i => Button(i.map(_.toString))(())))
          )
        )
        items.set(Seq(1, 2, 3))
        val rows = r.childrenOf(m.handle)
        chk.eq(rows.length, 3)
        chk.eq(
          rows.map(h => fillOf(r, h)).distinct,
          Seq(s"${red.r},${red.g},${red.b}"),
          "every row, including the two added after the fact"
        )
        o.dispose()
        chk.result
      },
      test("Provide adds no node to the tree") {
        val o = Owner(); given Owner = o
        Theme.install(Theme.platform)
        val chk = Checks()
        val r = TestRenderer()
        val m = Reconciler.mount(
          r,
          Column()(Provide(Theme.platform.withColor(ColorRole.Accent, red))(Label("only child")))
        )
        chk.eq(r.childrenOf(m.handle).map(r.text), Seq("only child"))
        o.dispose()
        chk.result
      }
    ) @@ TestAspect.sequential @@ TestAspect.after(zio.ZIO.succeed(Theme.install(Theme.platform)))

}
