package scalaui.signals.bench

import scalaui.signals.*

/** Cross-platform microbenchmarks. The same source runs on JVM, JS and Native so the
  * three numbers are directly comparable; JMH is JVM-only and would not be.
  *
  * Reported figure is nanoseconds per *node update*: wall time divided by
  * (writes x nodes actually re-evaluated per write).
  */
object Bench {

  private def timeMs(body: => Unit): Double = {
    val t0 = System.nanoTime()
    body
    (System.nanoTime() - t0) / 1e6
  }

  /** Median of `reps` timed runs, after `warmups` untimed ones. */
  private def measure(reps: Int, warmups: Int)(body: => Unit): Double = {
    var i = 0
    while i < warmups do { body; i += 1 }
    val samples = Array.fill(reps)(timeMs(body))
    java.util.Arrays.sort(samples)
    samples(samples.length / 2)
  }

  private var sink: Int = 0

  /** N computeds in a line; only the tail is observed. Every write re-evaluates all N. */
  private def chain(n: Int, writes: Int): Double = {
    val o = Owner()
    try {
      given Owner = o
      val root    = Var(0)
      val tail    = (1 to n).foldLeft[Signal[Int]](root)((s, _) => Signal.computed(s() + 1))
      Signal.effect { sink = tail() }
      val ms = measure(reps = 5, warmups = 3) {
        var i = 0
        while i < writes do { root.set(i); i += 1 }
      }
      (ms * 1e6) / (writes.toDouble * n)
    }
    finally o.dispose()
  }

  /** One var, N independent computeds, each with its own effect. */
  private def fanOut(n: Int, writes: Int): Double = {
    val o = Owner()
    try {
      given Owner = o
      val root    = Var(0)
      var k       = 0
      while k < n do {
        val c = Signal.computed(root() + 1)
        Signal.effect { sink = c() }
        k += 1
      }
      val ms = measure(reps = 5, warmups = 3) {
        var i = 0
        while i < writes do { root.set(i); i += 1 }
      }
      (ms * 1e6) / (writes.toDouble * n)
    }
    finally o.dispose()
  }

  /** Lazy pull: deep chain with no observer; cost of `now` after one write. */
  private def pullOnly(n: Int, writes: Int): Double = {
    val o = Owner()
    try {
      given Owner = o
      val root    = Var(0)
      val tail    = (1 to n).foldLeft[Signal[Int]](root)((s, _) => Signal.computed(s() + 1))
      val ms = measure(reps = 5, warmups = 3) {
        var i = 0
        while i < writes do { root.set(i); sink = tail.now; i += 1 }
      }
      (ms * 1e6) / (writes.toDouble * n)
    }
    finally o.dispose()
  }

  /** Writes that change nothing must be near-free (equality cutoff). */
  private def noopWrites(writes: Int): Double = {
    val o = Owner()
    try {
      given Owner = o
      val root    = Var(0)
      val tail    = (1 to 100).foldLeft[Signal[Int]](root)((s, _) => Signal.computed(s() + 1))
      Signal.effect { sink = tail() }
      val ms = measure(reps = 5, warmups = 3) {
        var i = 0
        while i < writes do { root.set(7); i += 1 }
      }
      (ms * 1e6) / writes.toDouble
    }
    finally o.dispose()
  }

  /** How deep a chain can be evaluated before the stack runs out. Validation is
    * recursive (see REPORT.md), so this is a hard limit, not a performance figure.
    * Printed incrementally: on Scala Native an overflow may kill the process rather
    * than raise, so the last printed depth is the answer.
    */
  private def depthProbe(): Unit = {
    val ladder   = List(100, 500, 1000, 2000, 3000, 4000, 6000, 8000, 12000, 16000)
    var continue = true
    ladder.foreach { n =>
      if continue then {
        val o = Owner()
        try {
          given Owner = o
          val root    = Var(0)
          val tail    = (1 to n).foldLeft[Signal[Int]](root)((s, _) => Signal.computed(s() + 1))
          sink = tail.now
          root.set(1)
          sink = tail.now
          println(s"  depth $n: ok")
        }
        catch {
          // JVM/Native raise StackOverflowError; Scala.js raises a JS RangeError.
          case t: Throwable =>
            println(s"  depth $n: FAILED (${t.getClass.getSimpleName})")
            continue = false
        }
        finally o.dispose()
      }
    }
  }

  def main(args: Array[String]): Unit = {
    val platform = if args.length > 0 then args(0) else "unknown"
    println(s"# scala-ui signals benchmark — platform=$platform")
    println("# max evaluable chain depth (default stack):")
    depthProbe()
    println(f"chain(1000 nodes, 200 writes)      ${chain(1000, 200)}%8.1f ns/node-update")
    println(f"fanOut(1000 nodes, 200 writes)     ${fanOut(1000, 200)}%8.1f ns/node-update")
    println(f"pullOnly(1000 nodes, 200 writes)   ${pullOnly(1000, 200)}%8.1f ns/node-update")
    println(f"noopWrites(100000)                 ${noopWrites(100000)}%8.1f ns/write")
    println(s"# sink=$sink")
  }
}
