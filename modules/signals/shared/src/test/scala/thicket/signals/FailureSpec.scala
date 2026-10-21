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

package thicket.signals

import scala.collection.mutable
import zio.test.*

/** What the graph does when user code throws, and when lifetimes end out of order (review 2026-10-20, P0-3b, P1-6,
  * P2-18). Each test is one of the review's failure scenarios, written so that it failed before the fix.
  */
object FailureSpec extends ZIOSpecDefault {

  private def throws(body: => Unit): Option[Throwable] =
    try { body; None }
    catch { case e: Throwable => Some(e) }

  def spec =
    suite("Failures")(
      test("a failed effect is not re-run by an unrelated write, and does not throw at that writer") {
        val chk = Checks()
        val o = Owner()
        given Owner = o
        val a = Var(0)
        val unrelated = Var(0)
        var runs = 0
        Signal.effect { runs += 1; if a() == 1 then sys.error("bad") }
        chk.yes(throws(a.set(1)).exists(_.getMessage == "bad"), "the write that caused it hears about it")
        chk.eq(runs, 2)
        chk.eq(throws(unrelated.set(1)), None, "an unrelated writer does not")
        chk.eq(runs, 2, "and the failed effect did not run again")
        a.set(2)
        chk.eq(runs, 3, "a change to its own dependency re-runs it")
        o.dispose()
        chk.result
      },
      test("an effect whose first run throws is still alive") {
        val chk = Checks()
        val o = Owner()
        given Owner = o
        val a = Var(0)
        var seen: List[Int] = Nil
        chk.yes(throws(Signal.effect { val v = a(); if v == 0 then sys.error("first"); seen = v :: seen }).nonEmpty)
        a.set(1)
        chk.eq(seen, List(1), "it re-ran when what it read changed")
        a.set(2)
        chk.eq(seen, List(2, 1))
        o.dispose()
        chk.result
      },
      test("a computed's failure is rethrown on every read until a dependency changes") {
        val chk = Checks()
        val o = Owner()
        given Owner = o
        val a = Var(1)
        val c = Signal.computed(if a() == 2 then sys.error("boom") else a() * 10)
        chk.eq(c.now, 10)
        a.set(2)
        chk.yes(throws(c.now).exists(_.getMessage == "boom"))
        chk.yes(throws(c.now).exists(_.getMessage == "boom"), "not a stale 10 on the second read")
        a.set(3)
        chk.eq(c.now, 30)
        o.dispose()
        chk.result
      },
      test("an effect reading a failed computed hears when it recovers") {
        val chk = Checks()
        val o = Owner()
        given Owner = o
        val a = Var(1)
        val c = Signal.computed(if a() == 2 then sys.error("boom") else a() * 10)
        var seen: List[Int] = Nil
        Signal.effect { seen = c() :: seen }
        chk.yes(throws(a.set(2)).nonEmpty)
        chk.eq(throws(Var(0).set(1)), None, "nothing else is poisoned")
        a.set(4)
        chk.eq(seen, List(40, 10))
        o.dispose()
        chk.result
      },
      test("an owner created under a disposed parent is disposed, and runs nothing") {
        val chk = Checks()
        val parent = Owner()
        parent.dispose()
        val child = Owner.child(using parent)
        chk.yes(child.disposed)
        val a = Var(0)
        var runs = 0
        Signal.effect { a(); runs += 1 }(using child)
        a.set(1)
        chk.eq(runs, 0)
        chk.result
      },
      test("a computed under a disposed owner still reads its value") {
        val chk = Checks()
        val o = Owner()
        o.dispose()
        val a = Var(7)
        val c = Signal.computed(a() + 1)(using o)
        chk.eq(c.now, 8, "not () cast to Int")
        chk.result
      },
      test("an effect disposed on its own is no longer held by its owner") {
        val chk = Checks()
        val o = Owner()
        given Owner = o
        val effects = (1 to 100).map(_ => Signal.effect(()))
        chk.eq(o.childCount, 100)
        effects.foreach(_.dispose())
        chk.eq(o.childCount, 0, "each left its owner when it was disposed")
        o.dispose()
        chk.result
      },
      test("an effect marked Check and then Dirty in one write runs once") {
        val chk = Checks()
        val o = Owner()
        given Owner = o
        val a = Var(1)
        val c = Signal.computed(a() * 2)
        var runs = 0
        // Reads `c` (marked Check when `a` changes) and `a` (marked Dirty): one write, one run.
        Signal.effect { val _ = c() + a(); runs += 1 }
        a.set(2)
        chk.eq(runs, 2)
        o.dispose()
        chk.result
      },
      test("disposal carries on past a child that throws, and reports it") {
        val chk = Checks()
        val reported = mutable.ArrayBuffer.empty[Throwable]
        val previous = ErrorSink.installed
        ErrorSink.install(e => reported += e)
        val o = Owner()
        var disposedLater = false
        o.own(() => disposedLater = true)
        o.own(() => sys.error("cleanup failed"))
        o.dispose()
        ErrorSink.install(previous)
        chk.yes(disposedLater, "the earlier child was still disposed")
        chk.eq(reported.map(_.getMessage).toList, List("cleanup failed"))
        chk.result
      }
    ) @@ TestAspect.sequential

}
