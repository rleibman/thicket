package example

import scala.scalanative.unsafe.*
import thicket.core.AppRoot
import thicket.core.dsl.*
import thicket.renderer.apple.{AppleApp, Handles}

/** A `postToUi` loop and nothing else, for #22.
  *
  * The #18 self-test crashed the iOS app while re-posting a wait thousands of times. This isolates that shape: each hop
  * posts the next, with no widgets involved beyond one label.
  *
  * Every hop checks a **canary**: a counter in a heap object captured by the posted closure, which must equal the hop
  * number. A closure that is still pending is reachable only through `Handles`' table, so if the collector does not
  * treat that table as live, the counter's memory is freed and reused and the canary stops matching — before anything
  * necessarily crashes. Every `step` hops it also prints the main thread's stack position (a stack leak would show as a
  * value that keeps falling) and the size of the handle table.
  *
  * Modes (`THICKET_POSTTEST=`):
  *   - `chain` — post, and nothing else;
  *   - `chain-alloc` — also allocate on every hop, so collections happen while the chain is in flight;
  *   - `chain-gc` — also force a collection on every hop, which amplifies anything the collector gets wrong;
  *   - `byname` — the #18 self-test's first wait, its condition forwarded by-name through every hop.
  */
object PostProbe {

  private val hops = 200000
  private val step = 20000

  def build(mode: String): AppRoot = {
    // Where the host's entry into Scala sits on the main thread's stack, to compare with
    // where a posted callback sits: on iOS the host owns `main` and calls in from deep
    // inside UIKit's launch, while dispatch callbacks run nearer the base of the stack.
    println(s"[posttest] stack at entry from the host=0x${stackPosition().toHexString}")
    AppleApp.postToUi(() => start(mode))
    AppRoot("postToUi", Column()(Label(s"postToUi: $mode")))
  }

  /** The address of a byte on the current stack frame: the same number every hop, if nothing leaks stack. */
  private def stackPosition(): Long = {
    val p = stackalloc[Byte]()
    p.toLong
  }

  /** A heap object only the pending closure refers to. */
  final private class Canary(var count: Int)

  private var mismatches = 0
  private var firstMismatch = ""

  private def verify(
    c:        Canary,
    expected: Int
  ): Unit =
    if c.count != expected then {
      mismatches += 1
      if firstMismatch.isEmpty then firstMismatch = s"hop $expected read ${c.count}"
      c.count = expected
    }

  private def report(
    t0:   Long,
    done: Int
  ): Unit =
    println(
      s"[posttest] DONE $done hops in ${(System.nanoTime() - t0) / 1000000} ms, " +
        s"canary mismatches=$mismatches${if firstMismatch.isEmpty then "" else s" (first: $firstMismatch)"}"
    )

  private def start(mode: String): Unit =
    mode match {
      case "byname" => byName()
      case _        => chain(mode)
    }

  private def chain(mode: String): Unit = {
    val alloc = mode == "chain-alloc" || mode == "chain-gc"
    val gc = mode == "chain-gc"
    val t0 = System.nanoTime()
    val sp0 = stackPosition()
    println(s"[posttest] mode=$mode hops=$hops sp0=0x${sp0.toHexString}")
    var keep: Array[Int] = null

    def hop(
      n: Int,
      c: Canary
    ): Unit = {
      verify(c, n)
      c.count += 1
      if alloc then keep = Array.fill(64)(n)
      if gc then System.gc()
      if n % step == 0 then
        println(
          s"[posttest] hop=$n ms=${(System.nanoTime() - t0) / 1000000} sp-drift=${sp0 - stackPosition()} " +
            s"handles=${Handles.count} mismatches=$mismatches"
        )
      if n >= hops then report(t0, n)
      else AppleApp.postToUi(() => hop(n + 1, c))
    }

    hop(0, Canary(0))
    val _ = keep
  }

  /** The #18 self-test's first wait, in shape: the condition is passed on by-name at every hop. */
  private def byName(): Unit = {
    val t0 = System.nanoTime()
    val c = Canary(0)
    var evaluations = 0
    def eventually(tries: Int)(cond: => Boolean)(k: Boolean => Unit): Unit =
      if cond then k(true)
      else if tries <= 0 then k(false)
      else AppleApp.postToUi(() => eventually(tries - 1)(cond)(k))
    println(s"[posttest] mode=byname hops=$hops")
    eventually(hops) {
      verify(c, evaluations)
      c.count += 1
      evaluations += 1
      if evaluations % step == 0 then
        println(s"[posttest] hop=$evaluations ms=${(System.nanoTime() - t0) / 1000000} mismatches=$mismatches")
      false
    }(_ => report(t0, evaluations))
  }

}
