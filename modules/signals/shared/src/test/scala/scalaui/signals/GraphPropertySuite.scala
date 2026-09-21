package scalaui.signals

import org.scalacheck.{Gen, Prop}
import Prop.forAll

/** Random DAGs, random writes: the graph must always agree with a pure
  * recomputation, and no node may evaluate more than once per write.
  */
class GraphPropertySuite extends munit.ScalaCheckSuite {

  /** `nodes(i)` lists the indices node `i` reads: `< nVars` is a Var, otherwise
    * computed number `index - nVars`, which is always earlier than `i` (so acyclic).
    */
  final case class Spec(nVars: Int, nodes: Vector[Vector[Int]]) {
    def evalPure(varValues: Vector[Int]): Vector[Int] =
      nodes.foldLeft(Vector.empty[Int]) { (acc, deps) =>
        acc :+ deps.map(d => if d < nVars then varValues(d) else acc(d - nVars)).sum
      }
  }

  private val genSpec: Gen[Spec] =
    for {
      nVars  <- Gen.choose(1, 5)
      nNodes <- Gen.choose(1, 12)
      nodes <- Gen.sequence[Vector[Vector[Int]], Vector[Int]](
        (0 until nNodes).map { i =>
          Gen
            .nonEmptyListOf(Gen.choose(0, nVars + i - 1))
            .map(_.distinct.toVector)
        }
      )
    }
    yield Spec(nVars, nodes)

  private def genWrites(nVars: Int): Gen[List[(Int, Int)]] =
    Gen.listOfN(
      12,
      for {
        i <- Gen.choose(0, nVars - 1)
        v <- Gen.choose(-20, 20)
      }
      yield (i, v)
    )

  property("random DAG always equals a pure recomputation") {
    forAll(genSpec.flatMap(s => genWrites(s.nVars).map((s, _)))) { (spec, writes) =>
      val o = Owner()
      try {
        given Owner = o
        val vars    = Vector.tabulate(spec.nVars)(i => Var(i))
        val evals   = Array.fill(spec.nodes.length)(0)

        val computeds = spec.nodes.zipWithIndex.foldLeft(Vector.empty[Signal[Int]]) {
          case (acc, (deps, i)) =>
            acc :+ Signal.computed {
              evals(i) += 1
              deps.map(d => if d < spec.nVars then vars(d)() else acc(d - spec.nVars)()).sum
            }
        }

        // Observe everything, as a UI would.
        computeds.foreach(c => Signal.effect { c(); () })

        var values = Vector.tabulate(spec.nVars)(i => i)
        var ok     = computeds.map(_.now) == spec.evalPure(values)

        writes.foreach { (idx, v) =>
          java.util.Arrays.fill(evals, 0)
          vars(idx).set(v)
          values = values.updated(idx, v)
          val expected = spec.evalPure(values)
          val actual   = computeds.map(_.now)
          ok = ok && actual == expected && evals.forall(_ <= 1)

        }
        ok
      }
      finally o.dispose()
    }
  }

  property("batching a whole write set gives the same result as writing one by one") {
    forAll(genSpec.flatMap(s => genWrites(s.nVars).map((s, _)))) { (spec, writes) =>
      def run(batched: Boolean): Vector[Int] = {
        val o = Owner()
        try {
          given Owner = o
          val vars = Vector.tabulate(spec.nVars)(i => Var(i))
          val computeds = spec.nodes.foldLeft(Vector.empty[Signal[Int]]) { (acc, deps) =>
            acc :+ Signal.computed(
              deps.map(d => if d < spec.nVars then vars(d)() else acc(d - spec.nVars)()).sum
            )
          }
          computeds.foreach(c => Signal.effect { c(); () })
          if batched then Signal.batch(writes.foreach((i, v) => vars(i).set(v)))
          else writes.foreach((i, v) => vars(i).set(v))
          computeds.map(_.now)
        }
        finally o.dispose()
      }

      run(batched = true) == run(batched = false)
    }
  }

  property("disposal always unlinks every var") {
    forAll(genSpec) { spec =>
      val o    = Owner()
      val vars = {
        given Owner = o
        val vs = Vector.tabulate(spec.nVars)(i => Var(i))
        val computeds = spec.nodes.foldLeft(Vector.empty[Signal[Int]]) { (acc, deps) =>
          acc :+ Signal.computed(
            deps.map(d => if d < spec.nVars then vs(d)() else acc(d - spec.nVars)()).sum
          )
        }
        computeds.foreach(c => Signal.effect { c(); () })
        vs
      }
      o.dispose()
      vars.forall(_.observers.isEmpty)
    }
  }
}
