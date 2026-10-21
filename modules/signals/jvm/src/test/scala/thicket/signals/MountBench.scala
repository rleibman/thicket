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

/** Mount and dispose N effects that all read one shared signal, as N rows reading one theme or one filter do; each
  * under its own owner, as a row's effects are. Prints milliseconds for N and 2N, so the growth is visible: about 2x is
  * linear, about 4x quadratic.
  *
  * {{{
  * sbt "signalsJVM/Test/runMain thicket.signals.MountBench"
  * }}}
  *
  * Not a test: timings belong in a measurement, recorded where the change that moved them is (review 2026-10-20, P2-10,
  * P2-11).
  */
object MountBench {

  private def once(n: Int): (Double, Double) = {
    val shared = Var(0)
    val root = Owner()
    val rows = Array.fill(n)(Owner.child(using root))
    val t0 = System.nanoTime()
    var i = 0
    while i < n do {
      Signal.effect { val _ = shared() }(using rows(i))
      i += 1
    }
    val t1 = System.nanoTime()
    // Unmount in mount order, the order a list is cleared in, which is the worst case for a
    // scan from the front.
    i = 0
    while i < n do {
      rows(i).dispose()
      i += 1
    }
    val t2 = System.nanoTime()
    root.dispose()
    ((t1 - t0) / 1e6, (t2 - t1) / 1e6)
  }

  def main(args: Array[String]): Unit = {
    for _ <- 1 to 5 do { val _ = once(10000) } // warm-up
    for n <- Seq(10000, 20000, 40000) do {
      val runs = (1 to 5).map(_ => once(n))
      val mount = runs.map(_._1).min
      val dispose = runs.map(_._2).min
      println(f"[bench] n=$n%6d  mount $mount%8.1f ms  dispose $dispose%8.1f ms")
    }
  }

}
