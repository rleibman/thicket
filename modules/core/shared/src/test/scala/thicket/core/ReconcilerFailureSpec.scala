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

import scala.collection.mutable
import zio.test.*
import thicket.core.dsl.*
import thicket.signals.{Checks, ErrorSink, Owner, Signal, Var}

/** The reconciler and NavHost under the review's failure scenarios (2026-10-20: P1-4, P1-7, P1-8). Each of these
  * failed, or could not be written, before the fix.
  */
object ReconcilerFailureSpec extends ZIOSpecDefault {

  private def reported(body: => Unit): List[Throwable] = {
    val seen = mutable.ArrayBuffer.empty[Throwable]
    val previous = ErrorSink.installed
    ErrorSink.install(e => seen += e)
    try body
    finally ErrorSink.install(previous)
    seen.toList
  }

  def spec =
    suite("Reconciler failures")(
      test("duplicate keys: the first item is shown, the duplicate is reported, nothing is left behind") {
        val chk = Checks()
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        val items = Var(Seq("a", "b"))
        var m: Mounted[Int] = null
        val seen = reported {
          m = Reconciler.mount(r, Column()(ForEach(items, key = (s: String) => s.take(1))(s => Label(s))))
          items.set(Seq("a1", "a2", "b"))
        }
        chk.eq(r.childrenOf(m.handle).map(r.text), Seq("a1", "b"))
        chk.yes(seen.exists(_.getMessage.contains("duplicate key a")), seen.toString)
        items.set(Nil)
        chk.eq(r.nodes.size, 1, "only the Column is left: no ghost row")
        o.dispose()
        chk.result
      },
      test("a throw while mounting a new entry destroys nothing twice, and leaves nothing mounted untracked") {
        val chk = Checks()
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        val items = Var(Seq(1, 2, 3))
        val m = Reconciler.mount(
          r,
          Column()(
            ForEach(items, key = (i: Int) => i)(i =>
              if i.now == 99 then sys.error("bad row") else Label(i.map(_.toString))
            )
          )
        )
        val thrown =
          try { items.set(Seq(3, 99, 1)); None }
          catch { case e: Throwable => Some(e) }
        chk.yes(thrown.exists(_.getMessage == "bad row"))
        m.dispose()
        val twice = r.destroyed.groupBy(identity).collect { case (h, hs) if hs.size > 1 => h }
        chk.eq(twice.toList, Nil, "every handle destroyed once")
        chk.eq(r.nodes.size, 0, "and every handle destroyed")
        o.dispose()
        chk.result
      },
      test("disposing a mounted tree twice destroys it once") {
        val chk = Checks()
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        val m = Reconciler.mount(r, Column()(Label("x")))
        m.dispose()
        m.dispose()
        chk.eq(r.destroyed.size, 2)
        o.dispose()
        chk.result
      },
      test("NavHost builds each entry's screen once: content, title and actions are one Screen") {
        val chk = Checks()
        var built = 0
        val nav = Nav("home")
        val host = NavHost(nav) { route =>
          built += 1
          val field = Var("")
          Screen(title = route, actions = Seq(Action("Save")(())), content = TextField(field)(field.set))
        }
        val _ = host.pages.now
        val _ = host.title.now
        val _ = host.actions.now
        val _ = host.screen.now
        chk.eq(built, 1)
        chk.yes(host.screen.now eq host.pages.now.last.screen, "the chrome's Screen is the content's")
        nav.push("detail")
        val _ = host.title.now
        val _ = host.pages.now
        chk.eq(built, 2)
        chk.result
      },
      test("modifiers: on a LazyColumn they reach its container; through Provide, its child") {
        val chk = Checks()
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        val m = Reconciler.mount(
          r,
          Column()(
            LazyColumn(Signal.const(Seq(1, 2)), key = (i: Int) => i)(i => Label(i.map(_.toString))).grow,
            Provide(Theme.active)(Label("themed")).grow
          )
        )
        val kids = r.childrenOf(m.handle)
        chk.eq(kids.map(k => r.nodes(k).props.get("grow")), Seq(Some("true"), Some("true")))
        o.dispose()
        chk.result
      },
      test("modifiers on Show, ForEach and Fragment fail where they are written, not silently") {
        val chk = Checks()
        val flag = Var(true)
        def fails(e: => Element) =
          try { val _ = e; false }
          catch { case _: IllegalArgumentException => true }
        chk.yes(fails(Show(flag)(Label("x")).padding(8)))
        chk.yes(fails(ForEach(Var(Seq(1)), key = (i: Int) => i)(_ => Label("x")).grow))
        chk.yes(fails(Fragment(Label("x")).safeArea()))
        chk.result
      },
      test("a root Fragment with children is refused like a root Show") {
        val chk = Checks()
        val o = Owner(); given Owner = o
        val thrown =
          try { val _ = Reconciler.mount(TestRenderer(), Fragment(Label("x"))); false }
          catch { case _: IllegalArgumentException => true }
        chk.yes(thrown)
        o.dispose()
        chk.result
      }
    ) @@ TestAspect.sequential

}
