package scalaui.signals

import zio.test.*

/** An accumulator for assertions made *during* a test rather than only at its end.
  *
  * zio-test wants one `TestResult` per test. Most tests here are not shaped that way: they
  * drive a mutable graph and check it after each step, because the interesting property is
  * usually *how many times* something happened between two writes, which cannot be observed
  * from the end state alone.
  *
  * So rather than restructure every test around a single final assertion — which would lose
  * exactly the intermediate observations that make these tests worth having — `Checks`
  * collects them and hands back their conjunction.
  *
  * {{{
  * test("...") {
  *   val c = Checks()
  *   c.eq(runs, 1)
  *   a.set(2)
  *   c.eq(runs, 2, "a changed value propagates")
  *   c.result
  * }
  * }}}
  *
  * Failures still report the actual and expected values, because each step goes through
  * `Assertion.equalTo` rather than a bare boolean.
  */
final class Checks {

  private var acc: TestResult = assertCompletes

  /** Assert `actual == expected`, keeping both values in the failure message. */
  def eq[A](actual: A, expected: A, hint: String = ""): Unit = {
    val step = assert(actual)(Assertion.equalTo(expected))
    acc = acc && (if hint.isEmpty then step else step ?? hint)
  }

  /** Assert `actual != expected`. */
  def ne[A](actual: A, expected: A, hint: String = ""): Unit = {
    val step = assert(actual)(Assertion.not(Assertion.equalTo(expected)))
    acc = acc && (if hint.isEmpty then step else step ?? hint)
  }

  /** Assert a plain condition.
    *
    * `hint` is optional because zio-test's `assertTrue` is a macro: it reports the source
    * of the expression that failed, so a bare condition is not as mute here as munit's
    * `assert` was. A hint is still worth adding when the expression alone does not say
    * *why* it should hold.
    */
  def yes(cond: Boolean, hint: String = ""): Unit = {
    val step = assertTrue(cond)
    acc = acc && (if hint.isEmpty then step else step ?? hint)
  }

  def no(cond: Boolean, hint: String = ""): Unit = yes(!cond, hint)

  def result: TestResult = acc
}

object Checks {
  def apply(): Checks = new Checks

  /** Run `body`, expecting it to throw `E`, and return what it threw.
    *
    * zio-test's own way of saying this is `assertZIO(ZIO.attempt(...).exit)(fails(...))`,
    * which wraps a synchronous throw in an effect only to unwrap it again. These tests are
    * synchronous by design — the core has no effect type — so they catch directly.
    */
  def intercept[E <: Throwable](body: => Any)(using ct: scala.reflect.ClassTag[E]): E =
    try {
      val _ = body
      throw new AssertionError(s"expected ${ct.runtimeClass.getName}, but nothing was thrown")
    } catch {
      case e: Throwable if ct.runtimeClass.isInstance(e) => e.asInstanceOf[E]
    }
}
