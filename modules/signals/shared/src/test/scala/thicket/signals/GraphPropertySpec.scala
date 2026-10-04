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

/** Random DAGs, random writes: the graph must always agree with a pure recomputation, and no node may evaluate more
  * than once per write.
  *
  * The first suite moved to zio-test. It was chosen as the probe deliberately: it is the only property-based suite, so
  * it is the one that exercises `Gen` — the part of zio-test that replaces a dependency (munit-scalacheck) rather than
  * merely renaming assertions — and it is cross-built to JVM, JS and Native, which is where a test framework is most
  * likely to fall over.
  */
object GraphPropertySpec extends ZIOSpecDefault {

  /** `nodes(i)` lists the indices node `i` reads: `< nVars` is a Var, otherwise computed number `index - nVars`, which
    * is always earlier than `i` (so acyclic).
    */
  final case class Spec(
    nVars: Int,
    nodes: Vector[Vector[Int]]
  ) {

    def evalPure(varValues: Vector[Int]): Vector[Int] =
      nodes.foldLeft(Vector.empty[Int]) {
        (
          acc,
          deps
        ) =>
          acc :+ deps.map(d => if d < nVars then varValues(d) else acc(d - nVars)).sum
      }

  }

  private val genSpec: Gen[Any, Spec] =
    for {
      nVars  <- Gen.int(1, 5)
      nNodes <- Gen.int(1, 12)
      nodes <- Gen.collectAll(
        (0 until nNodes).map { i =>
          Gen.listOfBounded(1, nVars + i)(Gen.int(0, nVars + i - 1)).map(_.distinct.toVector)
        }
      )
    } yield Spec(nVars, nodes.toVector)

  private def genWrites(nVars: Int): Gen[Any, List[(Int, Int)]] =
    Gen.listOfN(12)(
      for {
        i <- Gen.int(0, nVars - 1)
        v <- Gen.int(-20, 20)
      } yield (i, v)
    )

  private val genSpecAndWrites: Gen[Any, (Spec, List[(Int, Int)])] =
    genSpec.flatMap(s => genWrites(s.nVars).map((s, _)))

  /** **`sequential` is mandatory, not a preference.**
    *
    * The dependency-tracking context is two process-global `var`s — `Runtime.observer` and `Runtime.collected` —
    * because an `Option` allocation per tracked read is measurable against the 1 µs/node budget. The graph is therefore
    * owned by exactly one thread, which is what `ThreadGuard` enforces in a running app.
    *
    * zio-test runs a suite's tests in parallel by default, and that breaks the invariant immediately: two tests
    * tracking at once give a `NullPointerException` on `collected`, or a `ConcurrentModificationException` inside
    * `relink`. munit never showed this, because it runs a suite's tests one at a time. Every spec that touches the
    * graph needs this aspect.
    */
  def spec =
    suite("the signal graph")(
      test("a random DAG always equals a pure recomputation") {
        check(genSpecAndWrites) {
          (
            spec,
            writes
          ) =>
            val o = Owner()
            try {
              given Owner = o
              val vars = Vector.tabulate(spec.nVars)(i => Var(i))
              val evals = Array.fill(spec.nodes.length)(0)

              val computeds = spec.nodes.zipWithIndex.foldLeft(Vector.empty[Signal[Int]]) { case (acc, (deps, i)) =>
                acc :+ Signal.computed {
                  evals(i) += 1
                  deps.map(d => if d < spec.nVars then vars(d)() else acc(d - spec.nVars)()).sum
                }
              }

              // Observe everything, as a UI would.
              computeds.foreach(c => Signal.effect { c(); () })

              var values = Vector.tabulate(spec.nVars)(i => i)
              var ok = computeds.map(_.now) == spec.evalPure(values)

              writes.foreach {
                (
                  idx,
                  v
                ) =>
                  java.util.Arrays.fill(evals, 0)
                  vars(idx).set(v)
                  values = values.updated(idx, v)
                  val expected = spec.evalPure(values)
                  val actual = computeds.map(_.now)
                  // Glitch-freedom is the second half: agreeing with the pure recomputation is
                  // not enough if a node had to evaluate twice to get there.
                  ok = ok && actual == expected && evals.forall(_ <= 1)
              }
              assertTrue(ok)
            } finally o.dispose()
        }
      },
      test("batching a whole write set gives the same result as writing one by one") {
        check(genSpecAndWrites) {
          (
            spec,
            writes
          ) =>
            def run(batched: Boolean): Vector[Int] = {
              val o = Owner()
              try {
                given Owner = o
                val vars = Vector.tabulate(spec.nVars)(i => Var(i))
                val computeds = spec.nodes.foldLeft(Vector.empty[Signal[Int]]) {
                  (
                    acc,
                    deps
                  ) =>
                    acc :+ Signal.computed(
                      deps.map(d => if d < spec.nVars then vars(d)() else acc(d - spec.nVars)()).sum
                    )
                }
                computeds.foreach(c => Signal.effect { c(); () })
                if batched then
                  Signal.batch(
                    writes.foreach(
                      (
                        i,
                        v
                      ) => vars(i).set(v)
                    )
                  )
                else
                  writes.foreach(
                    (
                      i,
                      v
                    ) => vars(i).set(v)
                  )
                computeds.map(_.now)
              } finally o.dispose()
            }

            assertTrue(run(batched = true) == run(batched = false))
        }
      },
      test("disposal always unlinks every var") {
        check(genSpec) { spec =>
          val o = Owner()
          val vars = {
            given Owner = o
            val vs = Vector.tabulate(spec.nVars)(i => Var(i))
            val computeds = spec.nodes.foldLeft(Vector.empty[Signal[Int]]) {
              (
                acc,
                deps
              ) =>
                acc :+ Signal.computed(
                  deps.map(d => if d < spec.nVars then vs(d)() else acc(d - spec.nVars)()).sum
                )
            }
            computeds.foreach(c => Signal.effect { c(); () })
            vs
          }
          o.dispose()
          assertTrue(vars.forall(_.observers.isEmpty))
        }
      }
    ) @@ TestAspect.sequential

}
