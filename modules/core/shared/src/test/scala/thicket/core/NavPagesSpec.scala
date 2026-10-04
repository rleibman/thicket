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
import thicket.signals.{Checks, Var}
import zio.test.*

/** `AppRoot.pages` — the seam a host with a native navigation container mounts.
  *
  * The property that matters is **identity**, not shape: a page already on screen must come back as the *same*
  * `NavPage`, because that is what lets a host mount it once and leave it mounted. Rebuilding it would throw away the
  * scroll position and the in-flight requests that keeping the stack alive exists to preserve.
  */
object NavPagesSpec extends ZIOSpecDefault {

  private enum R {

    case Home, About, Detail

  }

  private def host(counter: Var[Int]): NavHost[R] =
    NavHost(Nav(R.Home)) {
      case R.Home  => Screen("Home", Column()(Label("home")))
      case R.About => Screen("About", Column()(Label("about")))
      // Counts how many times this screen's content is built.
      case R.Detail =>
        counter.set(counter.now + 1)
        Screen("Detail", Column()(Label("detail")))
    }

  def spec =
    suite("AppRoot.pages")(
      test("a single-screen AppRoot is a one-page stack") {
        val chk = Checks()
        val root = AppRoot("Settings", Column()(Label("x")))
        val ps = root.pages.now
        chk.eq(ps.length, 1)
        chk.eq(ps.head.screen.title, "Settings")
        chk.result
      },
      test("pages are bottom first, the opposite of Nav.stack") {
        val chk = Checks()
        val h = host(Var(0))
        h.push(R.About)
        h.push(R.Detail)

        // Nav keeps the top first, because that is what an app asks about; a navigation
        // container stacks from the root upwards. Getting this backwards would show the root.
        chk.eq(h.navigator.stack.now.map(_.route), List(R.Detail, R.About, R.Home))
        chk.eq(h.pages.now.map(_.screen.title), Seq("Home", "About", "Detail"))
        chk.result
      },
      test("a page already on the stack is the same instance after a push") {
        val chk = Checks()
        val h = host(Var(0))
        val homeBefore = h.pages.now.head
        h.push(R.About)
        val homeAfter = h.pages.now.head

        // Reference equality, deliberately: a host keys its mounted pages on this. An equal
        // but fresh NavPage would still cost the screen below its scroll position.
        chk.yes(homeBefore eq homeAfter, "the page below a push must not be rebuilt")
        chk.yes(homeBefore.content eq homeAfter.content)
        chk.result
      },
      test("content is built once per entry, however often pages is read") {
        val chk = Checks()
        val counter = Var(0)
        val h = host(counter)

        // Pull-based, like the rest of the graph: pushing marks `pages` stale, and the screen
        // is built when someone reads it. So the read is what to count around, not the push.
        h.push(R.Detail)
        h.pages.now
        chk.eq(counter.now, 1)
        h.pages.now; h.pages.now; h.pages.now
        chk.eq(counter.now, 1, "reading pages again must not rebuild a live screen")

        // A push above it must not rebuild it either.
        h.push(R.About)
        h.pages.now
        chk.eq(counter.now, 1)
        chk.result
      },
      test("popping drops the page, and pushing the same route again builds a fresh one") {
        val chk = Checks()
        val counter = Var(0)
        val h = host(counter)

        h.push(R.Detail)
        val first = h.pages.now.last
        chk.eq(counter.now, 1)

        chk.yes(h.back())
        chk.eq(h.pages.now.map(_.screen.title), Seq("Home"))

        // Two visits to the same route are two screens — the entry id is what says so. A cache
        // keyed on the route would hand back the popped screen's widgets and state.
        h.push(R.Detail)
        val second = h.pages.now.last
        chk.eq(counter.now, 2, "a second visit is a second screen")
        chk.no(second eq first)
        chk.no(second.id == first.id)
        chk.result
      },
      test("the root is never dropped") {
        val chk = Checks()
        val h = host(Var(0))
        chk.no(h.back(), "nothing to pop")
        chk.eq(h.pages.now.map(_.screen.title), Seq("Home"))
        chk.result
      }
    ) @@ TestAspect.sequential

}
