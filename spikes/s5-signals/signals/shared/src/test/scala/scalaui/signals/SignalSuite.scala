package scalaui.signals

class SignalSuite extends munit.FunSuite {

  test("var: read, set, equality cutoff") {
    val o = Owner()
    given Owner = o
    val a       = Var(1)
    var runs    = 0
    Signal.effect { a(); runs += 1 }
    assertEquals(runs, 1)
    a.set(1) // same value: no propagation
    assertEquals(runs, 1)
    a.set(2)
    assertEquals(runs, 2)
    o.dispose()
  }

  test("diamond: each computed evaluates at most once per change") {
    val o = Owner()
    given Owner = o
    val a       = Var(1)
    var bE, cE, dE = 0
    val b = Signal.computed { bE += 1; a() * 2 }
    val c = Signal.computed { cE += 1; a() + 10 }
    val d = Signal.computed { dE += 1; b() + c() }
    var seen: List[Int] = Nil
    Signal.effect { seen = d() :: seen }

    assertEquals((bE, cE, dE), (1, 1, 1))
    assertEquals(seen, List(2 + 11))

    a.set(2)
    assertEquals((bE, cE, dE), (2, 2, 2), "no node re-evaluated twice for one write")
    assertEquals(seen, List(4 + 12, 2 + 11))
    o.dispose()
  }

  test("equality cutoff stops propagation at an unchanged computed") {
    val o = Owner()
    given Owner = o
    val a       = Var(1)
    val parity  = Signal.computed(a() % 2)
    var runs    = 0
    Signal.effect { parity(); runs += 1 }
    assertEquals(runs, 1)
    a.set(3) // parity unchanged (1) -> effect must not re-run
    assertEquals(runs, 1)
    a.set(2) // parity changes to 0
    assertEquals(runs, 2)
    o.dispose()
  }

  test("dynamic dependencies: unread branch is unsubscribed") {
    val o = Owner()
    given Owner = o
    val useA    = Var(true)
    val a       = Var(1)
    val b       = Var(100)
    var runs    = 0
    val c       = Signal.computed { runs += 1; if useA() then a() else b() }
    Signal.effect { c() }

    assertEquals(c.now, 1)
    assertEquals(runs, 1)

    b.set(200) // not a dependency yet
    assertEquals(runs, 1, "writing an untracked var must not recompute")

    useA.set(false)
    assertEquals(c.now, 200)
    a.set(5) // no longer a dependency
    val afterA = runs
    a.set(6)
    assertEquals(runs, afterA, "old dependency must have been unlinked")

    b.set(300)
    assertEquals(c.now, 300)
    o.dispose()
  }

  test("batch coalesces writes into one propagation") {
    val o = Owner()
    given Owner = o
    val a, b    = Var(0)
    var runs    = 0
    val sum     = Signal.computed(a() + b())
    var seen: List[Int] = Nil
    Signal.effect { seen = sum() :: seen; runs += 1 }
    assertEquals(runs, 1)

    Signal.batch {
      a.set(1)
      b.set(2)
      assertEquals(runs, 1, "effects must not run inside the batch")
    }

    assertEquals(runs, 2, "one propagation for two writes")
    assertEquals(seen.head, 3)
    assertEquals(seen.length, 2, "no intermediate value (1,0) was ever observed")
    o.dispose()
  }

  test("untracked reads do not subscribe") {
    val o = Owner()
    given Owner = o
    val a, b    = Var(0)
    var runs    = 0
    Signal.effect {
      a()
      Signal.untracked(b())
      runs += 1
    }
    assertEquals(runs, 1)
    b.set(5)
    assertEquals(runs, 1)
    a.set(1)
    assertEquals(runs, 2)
    o.dispose()
  }

  test("disposal: effect stops firing and graph is unlinked") {
    val o = Owner()
    given Owner = o
    val a       = Var(0)
    var runs    = 0
    val c       = Signal.computed(a() + 1)
    Signal.effect { c(); runs += 1 }
    a.set(1)
    assertEquals(runs, 2)
    assertEquals(a.observers.length, 1, "computed is the only observer of the var")

    o.dispose()
    a.set(2)
    assertEquals(runs, 2, "disposed effect must not run")
    assertEquals(a.observers.length, 0, "var must hold no observers after disposal")
    assertEquals(c.asInstanceOf[Computation].deps.length, 0)
  }

  test("nested owners: disposing the parent disposes children") {
    val parent = Owner()
    val a      = Var(0)
    var outer, inner = 0

    def underParent(using Owner): Owner = {
      Signal.effect { a(); outer += 1 }
      Owner.scoped { Signal.effect { a(); inner += 1 } }
    }

    val child = {
      given Owner = parent
      underParent
    }

    a.set(1)
    assertEquals((outer, inner), (2, 2))
    child.dispose()
    a.set(2)
    assertEquals((outer, inner), (3, 2), "only the child's effect stopped")
    parent.dispose()
    a.set(3)
    assertEquals((outer, inner), (3, 2))
    assertEquals(a.observers.length, 0)
  }

  test("owning after disposal disposes immediately") {
    val o = Owner()
    o.dispose()
    given Owner = o
    val a       = Var(0)
    var runs    = 0
    Signal.effect { a(); runs += 1 }
    assertEquals(runs, 0, "an effect created under a dead owner never runs")
    a.set(1)
    assertEquals(runs, 0)
    assertEquals(a.observers.length, 0)
  }

  test("exception in computed propagates to the writer and the graph recovers") {
    val o = Owner()
    given Owner = o
    val a       = Var(1)
    val c       = Signal.computed { if a() == 2 then sys.error("boom") else a() * 10 }
    var seen: List[Int] = Nil
    Signal.effect { seen = c() :: seen }
    assertEquals(seen, List(10))

    val ex = intercept[RuntimeException](a.set(2))
    assertEquals(ex.getMessage, "boom")

    a.set(3)
    assertEquals(seen.head, 30, "graph still works after a failed propagation")
    assertEquals(c.now, 30)
    o.dispose()
  }

  test("exception in one effect does not stop the others") {
    val o = Owner()
    given Owner = o
    val a       = Var(0)
    var good    = 0
    Signal.effect { if a() == 1 then sys.error("bad effect") }
    Signal.effect { a(); good += 1 }
    assertEquals(good, 1)
    intercept[RuntimeException](a.set(1))
    assertEquals(good, 2, "the second effect still ran")
    o.dispose()
  }

  test("map and zip") {
    val o = Owner()
    given Owner = o
    val a       = Var(2)
    val b       = Var("x")
    val doubled = a.map(_ * 2)
    val both    = a.zip(b)
    assertEquals(doubled.now, 4)
    assertEquals(both.now, (2, "x"))
    a.set(3)
    assertEquals(doubled.now, 6)
    assertEquals(both.now, (3, "x"))
    o.dispose()
  }

  test("const does not participate in the graph") {
    val o = Owner()
    given Owner = o
    val k       = Signal.const(7)
    var runs    = 0
    Signal.effect { k(); runs += 1 }
    assertEquals(runs, 1)
    assertEquals(k.now, 7)
    assertEquals(k.map(_ + 1).now, 8)
    o.dispose()
  }

  test("lazy: an unobserved computed is not evaluated until read") {
    val o = Owner()
    given Owner = o
    val a       = Var(1)
    var evals   = 0
    val c       = Signal.computed { evals += 1; a() }
    assertEquals(evals, 0, "computed is lazy")
    assertEquals(c.now, 1)
    assertEquals(evals, 1)
    a.set(2)
    assertEquals(evals, 1, "still lazy: nobody has read it since the write")
    assertEquals(c.now, 2)
    assertEquals(evals, 2)
    o.dispose()
  }

  test("deep chain propagates correctly") {
    val o = Owner()
    given Owner = o
    val a       = Var(0)
    val last    = (1 to 100).foldLeft[Signal[Int]](a)((s, _) => Signal.computed(s() + 1))
    assertEquals(last.now, 100)
    a.set(5)
    assertEquals(last.now, 105)
    o.dispose()
  }
}
