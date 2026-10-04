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

import zio.ZIO
import zio.test.*
import thicket.signals.Checks

import thicket.core.dsl.*
import thicket.signals.Owner

/** App-level theming.
  *
  * The property that matters most is the *negative* one: an app that sets no theme must produce widgets with no colour
  * set at all, so every one of them keeps following the user's platform theme, dark mode and contrast settings.
  */

object ThemeSpec extends ZIOSpecDefault {

  def spec =
    suite("Theme")(
      test("the default theme sets no colours at all") {
        val chk = Checks()
        Theme.install(Theme.platform)
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        val m = Reconciler.mount(r, Column()(Label("hello"), Button("go")(())))

        r.childrenOf(m.handle).foreach { h =>
          chk.yes(!r.nodes(h).props.contains("tint"), "no tint means the platform decides")
          chk.yes(!r.nodes(h).props.contains("fill"), "no fill means the platform decides")
        }
        o.dispose()
        chk.result
      },
      test("an overridden role reaches the widgets that use it") {
        val chk = Checks()
        Theme.install(
          Theme.platform
            .withColor(ColorRole.OnSurface, Rgb(0x11, 0x22, 0x33))
            .withColor(ColorRole.Accent, Rgb(0xff, 0x00, 0x66))
        )
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        val m = Reconciler.mount(r, Column()(Label("hello"), Button("go")(())))
        val kids = r.childrenOf(m.handle)

        chk.eq(r.nodes(kids.head).props.get("tint"), Some("17,34,51"))
        chk.eq(r.nodes(kids(1)).props.get("fill"), Some("255,0,102"))
        o.dispose()
        chk.result
      },
      test("roles are independent: overriding one leaves the others to the platform") {
        val chk = Checks()
        Theme.install(Theme.platform.withColor(ColorRole.Accent, Rgb(1, 2, 3)))
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        val m = Reconciler.mount(r, Column()(Label("hello"), Button("go")(())))
        val kids = r.childrenOf(m.handle)

        chk.yes(!r.nodes(kids.head).props.contains("tint"), "OnSurface was not overridden")
        chk.eq(r.nodes(kids(1)).props.get("fill"), Some("1,2,3"))
        o.dispose()
        chk.result
      },
      test("secondary text resolves a different role from primary text") {
        val chk = Checks()
        Theme.install(
          Theme.platform
            .withColor(ColorRole.OnSurface, Rgb(0, 0, 0))
            .withColor(ColorRole.OnSurfaceSecondary, Rgb(128, 128, 128))
        )
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        val m = Reconciler.mount(
          r,
          Column()(
            Label("primary"),
            Label("secondary", emphasis = thicket.renderer.Emphasis.Secondary)
          )
        )
        val kids = r.childrenOf(m.handle)
        chk.eq(r.nodes(kids.head).props.get("tint"), Some("0,0,0"))
        chk.eq(r.nodes(kids(1)).props.get("tint"), Some("128,128,128"))
        o.dispose()
        chk.result
      },
      test("a button may opt into a different role, such as Danger") {
        val chk = Checks()
        Theme.install(Theme.platform.withColor(ColorRole.Danger, Rgb(200, 0, 0)))
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        val m = Reconciler.mount(r, Column()(Button("Delete", role = ColorRole.Danger)(())))
        chk.eq(r.nodes(r.childrenOf(m.handle).head).props.get("fill"), Some("200,0,0"))
        o.dispose()
        chk.result
      },
      test("branding a background derives a readable foreground") {
        val chk = Checks()
        // The failure this prevents: dark text on a dark brand colour, because the app set a
        // background and the platform's text colour was chosen for the platform's background.
        Theme.install(Theme.platform.withColor(ColorRole.Accent, Rgb(0x2e, 0x6f, 0x40)))
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        val m = Reconciler.mount(r, Column()(Button("go")(())))
        val b = r.childrenOf(m.handle).head

        chk.eq(r.nodes(b).props.get("fill"), Some("46,111,64"))
        chk.eq(r.nodes(b).props.get("tint"), Some("255,255,255"), "white on a dark accent")
        o.dispose()
        chk.result
      },
      test("a light accent gets black text, a dark accent white") {
        val chk = Checks()
        chk.eq(Rgb(0x2e, 0x6f, 0x40).contrasting, Rgb(255, 255, 255))
        chk.eq(Rgb(0xff, 0xe0, 0x66).contrasting, Rgb(0, 0, 0))
        chk.eq(Rgb(255, 255, 255).contrasting, Rgb(0, 0, 0))
        chk.eq(Rgb(0, 0, 0).contrasting, Rgb(255, 255, 255))
        chk.result
      },
      test("an explicit OnAccent wins over the derived one") {
        val chk = Checks()
        Theme.install(
          Theme.platform
            .withColor(ColorRole.Accent, Rgb(0, 0, 0))
            .withColor(ColorRole.OnAccent, Rgb(255, 0, 0))
        )
        chk.eq(Theme.active.get(ColorRole.OnAccent), Some(Rgb(255, 0, 0)))
        chk.result
      },
      test("with no accent there is no OnAccent either") {
        val chk = Checks()
        Theme.install(Theme.platform)
        chk.eq(Theme.active.get(ColorRole.OnAccent), None)
        chk.result
      },
      test("Rgb renders as hex for stylesheet-based renderers") {
        val chk = Checks()
        chk.eq(Rgb(0xff, 0x00, 0x66).hex, "#ff0066")
        chk.eq(Rgb(1, 2, 3).hex, "#010203")
        chk.result
      }
      // `Theme.install` is process-global, so each test has to put it back. munit did this
      // with `afterEach`; zio-test does it with an aspect, which has the advantage of being
      // visible at the suite's declaration rather than buried in an override.
    ) @@ TestAspect.sequential @@ TestAspect.after(ZIO.succeed(Theme.install(Theme.platform)))

}
