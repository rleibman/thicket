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

import zio.test.*

/** The signal graph's behavioural tests.
  *
  * `sequential` is mandatory: the tracking context is two process-global `var`s (`Runtime.observer`,
  * `Runtime.collected`), so two tests tracking at once corrupt each other. See [[GraphPropertySpec]].
  */
object SignalSpec extends ZIOSpecDefault {

  def spec =
    suite("Signal")(
      test("var: read, set, equality cutoff") {
        val chk = Checks()
        val o = Owner()
        given Owner = o
        val a = Var(1)
        var runs = 0
        Signal.effect { a(); runs += 1 }
        chk.eq(runs, 1)
        a.set(1) // same value: no propagation
        chk.eq(runs, 1)
        a.set(2)
        chk.eq(runs, 2)
        o.dispose()
        chk.result
      },
      test("diamond: each computed evaluates at most once per change") {
        val chk = Checks()
        val o = Owner()
        given Owner = o
        val a = Var(1)
        var bE, cE, dE = 0
        val b = Signal.computed { bE += 1; a() * 2 }
        val c = Signal.computed { cE += 1; a() + 10 }
        val d = Signal.computed { dE += 1; b() + c() }
        var seen: List[Int] = Nil
        Signal.effect { seen = d() :: seen }

        chk.eq((bE, cE, dE), (1, 1, 1))
        chk.eq(seen, List(2 + 11))

        a.set(2)
        chk.eq((bE, cE, dE), (2, 2, 2), "no node re-evaluated twice for one write")
        chk.eq(seen, List(4 + 12, 2 + 11))
        o.dispose()
        chk.result
      },
      test("equality cutoff stops propagation at an unchanged computed") {
        val chk = Checks()
        val o = Owner()
        given Owner = o
        val a = Var(1)
        val parity = Signal.computed(a() % 2)
        var runs = 0
        Signal.effect { parity(); runs += 1 }
        chk.eq(runs, 1)
        a.set(3) // parity unchanged (1) -> effect must not re-run
        chk.eq(runs, 1)
        a.set(2) // parity changes to 0
        chk.eq(runs, 2)
        o.dispose()
        chk.result
      },
      test("dynamic dependencies: unread branch is unsubscribed") {
        val chk = Checks()
        val o = Owner()
        given Owner = o
        val useA = Var(true)
        val a = Var(1)
        val b = Var(100)
        var runs = 0
        val c = Signal.computed { runs += 1; if useA() then a() else b() }
        Signal.effect(c())

        chk.eq(c.now, 1)
        chk.eq(runs, 1)

        b.set(200) // not a dependency yet
        chk.eq(runs, 1, "writing an untracked var must not recompute")

        useA.set(false)
        chk.eq(c.now, 200)
        a.set(5) // no longer a dependency
        val afterA = runs
        a.set(6)
        chk.eq(runs, afterA, "old dependency must have been unlinked")

        b.set(300)
        chk.eq(c.now, 300)
        o.dispose()
        chk.result
      },
      test("batch coalesces writes into one propagation") {
        val chk = Checks()
        val o = Owner()
        given Owner = o
        val a, b = Var(0)
        var runs = 0
        val sum = Signal.computed(a() + b())
        var seen: List[Int] = Nil
        Signal.effect { seen = sum() :: seen; runs += 1 }
        chk.eq(runs, 1)

        Signal.batch {
          a.set(1)
          b.set(2)
          chk.eq(runs, 1, "effects must not run inside the batch")
        }

        chk.eq(runs, 2, "one propagation for two writes")
        chk.eq(seen.head, 3)
        chk.eq(seen.length, 2, "no intermediate value (1,0) was ever observed")
        o.dispose()
        chk.result
      },
      test("untracked reads do not subscribe") {
        val chk = Checks()
        val o = Owner()
        given Owner = o
        val a, b = Var(0)
        var runs = 0
        Signal.effect {
          a()
          Signal.untracked(b())
          runs += 1
        }
        chk.eq(runs, 1)
        b.set(5)
        chk.eq(runs, 1)
        a.set(1)
        chk.eq(runs, 2)
        o.dispose()
        chk.result
      },
      test("disposal: effect stops firing and graph is unlinked") {
        val chk = Checks()
        val o = Owner()
        given Owner = o
        val a = Var(0)
        var runs = 0
        val c = Signal.computed(a() + 1)
        Signal.effect { c(); runs += 1 }
        a.set(1)
        chk.eq(runs, 2)
        chk.eq(a.observers.length, 1, "computed is the only observer of the var")

        o.dispose()
        a.set(2)
        chk.eq(runs, 2, "disposed effect must not run")
        chk.eq(a.observers.length, 0, "var must hold no observers after disposal")
        chk.eq(c.asInstanceOf[Computation].deps.length, 0)
        chk.result
      },
      test("nested owners: disposing the parent disposes children") {
        val chk = Checks()
        val parent = Owner()
        val a = Var(0)
        var outer, inner = 0

        def underParent(using Owner): Owner = {
          Signal.effect { a(); outer += 1 }
          Owner.scoped(Signal.effect { a(); inner += 1 })
        }

        val child = {
          given Owner = parent
          underParent
        }

        a.set(1)
        chk.eq((outer, inner), (2, 2))
        child.dispose()
        a.set(2)
        chk.eq((outer, inner), (3, 2), "only the child's effect stopped")
        parent.dispose()
        a.set(3)
        chk.eq((outer, inner), (3, 2))
        chk.eq(a.observers.length, 0)
        chk.result
      },
      test("owning after disposal disposes immediately") {
        val chk = Checks()
        val o = Owner()
        o.dispose()
        given Owner = o
        val a = Var(0)
        var runs = 0
        Signal.effect { a(); runs += 1 }
        chk.eq(runs, 0, "an effect created under a dead owner never runs")
        a.set(1)
        chk.eq(runs, 0)
        chk.eq(a.observers.length, 0)
        chk.result
      },
      test("exception in computed propagates to the writer and the graph recovers") {
        val chk = Checks()
        val o = Owner()
        given Owner = o
        val a = Var(1)
        val c = Signal.computed(if a() == 2 then sys.error("boom") else a() * 10)
        var seen: List[Int] = Nil
        Signal.effect { seen = c() :: seen }
        chk.eq(seen, List(10))

        val ex = Checks.intercept[RuntimeException](a.set(2))
        chk.eq(ex.getMessage, "boom")

        a.set(3)
        chk.eq(seen.head, 30, "graph still works after a failed propagation")
        chk.eq(c.now, 30)
        o.dispose()
        chk.result
      },
      test("exception in one effect does not stop the others") {
        val chk = Checks()
        val o = Owner()
        given Owner = o
        val a = Var(0)
        var good = 0
        Signal.effect(if a() == 1 then sys.error("bad effect"))
        Signal.effect { a(); good += 1 }
        chk.eq(good, 1)
        Checks.intercept[RuntimeException](a.set(1))
        chk.eq(good, 2, "the second effect still ran")
        o.dispose()
        chk.result
      },
      test("map and zip need no Owner: they are stateless views") {
        val chk = Checks()
        val a = Var(2)
        val b = Var("x")
        val doubled = a.map(_ * 2)
        val both = a.zip(b)
        chk.eq(doubled.now, 4)
        chk.eq(both.now, (2, "x"))
        a.set(3)
        chk.eq(doubled.now, 6)
        chk.eq(both.now, (3, "x"))
        chk.result
      },
      test("map does not memoise; computed does") {
        val chk = Checks()
        // The documented trade: `map` is a free, unowned view that re-runs `f` on every read,
        // so it cannot cut off propagation. `computed` memoises and takes an Owner.
        val o = Owner(); given Owner = o
        val a = Var(0)

        var viaMap = 0
        Signal.effect { a.map(_ % 2)(); viaMap += 1 }
        var viaComputed = 0
        val parity = Signal.computed(a() % 2)
        Signal.effect { parity(); viaComputed += 1 }

        chk.eq((viaMap, viaComputed), (1, 1))
        a.set(2) // 0 -> 2: parity unchanged
        chk.eq(viaMap, 2, "map re-fires because it tracks the source directly")
        chk.eq(viaComputed, 1, "computed cuts off on an unchanged value")
        chk.result
      },
      test("const does not participate in the graph") {
        val chk = Checks()
        val o = Owner()
        given Owner = o
        val k = Signal.const(7)
        var runs = 0
        Signal.effect { k(); runs += 1 }
        chk.eq(runs, 1)
        chk.eq(k.now, 7)
        chk.eq(k.map(_ + 1).now, 8)
        o.dispose()
        chk.result
      },
      test("lazy: an unobserved computed is not evaluated until read") {
        val chk = Checks()
        val o = Owner()
        given Owner = o
        val a = Var(1)
        var evals = 0
        val c = Signal.computed { evals += 1; a() }
        chk.eq(evals, 0, "computed is lazy")
        chk.eq(c.now, 1)
        chk.eq(evals, 1)
        a.set(2)
        chk.eq(evals, 1, "still lazy: nobody has read it since the write")
        chk.eq(c.now, 2)
        chk.eq(evals, 2)
        o.dispose()
        chk.result
      },
      test("deep chain propagates correctly") {
        val chk = Checks()
        val o = Owner()
        given Owner = o
        val a = Var(0)
        val last = (1 to 100).foldLeft[Signal[Int]](a)(
          (
            s,
            _
          ) => Signal.computed(s() + 1)
        )
        chk.eq(last.now, 100)
        a.set(5)
        chk.eq(last.now, 105)
        o.dispose()
        chk.result
      }
    ) @@ TestAspect.sequential

}
