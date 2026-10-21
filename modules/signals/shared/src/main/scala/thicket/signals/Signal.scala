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
import scala.util.control.NonFatal

/** Something that can be torn down. */
trait Disposable {

  def dispose(): Unit

}

/** A value that changes over time. Reads are synchronous and always up to date.
  *
  *   - [[now]] reads without subscribing (safe anywhere).
  *   - [[apply]] reads *and subscribes*, and is only callable inside a reactive computation, where a `Tracking` is in
  *     scope.
  */
trait Signal[+A] {

  /** Untracked read. */
  def now: A

  /** Tracked read: the enclosing computation re-runs when this changes. */
  def apply()(using Tracking): A

  /** A derived view of this signal.
    *
    * Deliberately *not* a graph node: it holds no state, registers nothing, and needs no `Owner`, so it can be used
    * freely in UI code without threading a lifetime through every function. `f` re-runs on each read, and reads track
    * this signal's source.
    *
    * The trade is memoisation: a `map` does not cut off propagation when `f` returns an unchanged value. Where that
    * matters — an expensive `f`, or a coarse source feeding a narrow view — use [[Signal.computed]], which memoises and
    * takes an `Owner`. This is the same split as SolidJS's derived functions vs `createMemo`.
    */
  def map[B](f: A => B): Signal[B] = new Signal.Derived(this, f)

  def zip[B](that: Signal[B]): Signal[(A, B)] =
    new Signal.Derived[A, (A, B)](this, _ => (this.now, that.now)) {
      override def apply()(using Tracking): (A, B) = (Signal.this.apply(), that.apply())
      override def now:                     (A, B) = (Signal.this.now, that.now)
    }

}

object Signal {

  /** A pass-through view of another signal. Stateless, so nothing owns or disposes it. */
  private[signals] class Derived[A, B](
    source: Signal[A],
    f:      A => B
  ) extends Signal[B] {

    def now:                     B = f(source.now)
    def apply()(using Tracking): B = f(source())

  }

  /** Never changes; does not participate in the graph. */
  def const[A](a: A): Signal[A] =
    new Signal[A] {
      def now:                        A = a
      def apply()(using Tracking):    A = a
      override def map[B](f: A => B): Signal[B] = const(f(a))
    }

  /** A derived value. Lazy: recomputed only when read after a dependency changed. */
  def computed[A](f: Tracking ?=> A)(using owner: Owner): Signal[A] = {
    val c = new Computed[A](() => f)
    owner.own(c)
    if !c.disposed then c.ownedBy = owner
    c
  }

  /** Runs `body` now, and again whenever a tracked dependency changes. */
  def effect(body: Tracking ?=> Unit)(using owner: Owner): Disposable = {
    val e = new Effect(() => body)
    owner.own(e) // disposes `e` immediately if the owner is already dead
    if !e.disposed then {
      e.ownedBy = owner
      e.runInitial()
    }
    e
  }

  /** Coalesces all writes inside `body` into a single propagation. */
  def batch[A](body: => A): A = Runtime.batched(body)

  /** Reads inside `body` do not create dependencies. */
  def untracked[A](body: Tracking ?=> A): A = {
    val prev = Runtime.collected
    Runtime.collected = null
    try body(using Runtime.tracking)
    finally Runtime.collected = prev
  }

}

/** A mutable root of the graph. */
final class Var[A] private (initial: A) extends Signal[A] with Source {

  private var value:              A = initial
  private var ver:                Long = 0
  private[signals] val observers: mutable.LinkedHashSet[Computation] = mutable.LinkedHashSet.empty

  private[signals] def version:    Long = ver
  private[signals] def validate(): Unit = () // roots are always current

  def now:                     A = value
  def apply()(using Tracking): A = {
    Runtime.track(this)
    value
  }

  /** Writes the value. No-op if `a == current` (equality cutoff). Effects that depend on it run before this returns.
    *
    * **Throws** `IllegalStateException` on a thread other than the graph's, once a host has installed
    * `ThreadGuard.owningThread` (every host does): signals are single-threaded, and a write from elsewhere has to be
    * posted through `thicket.core.UiThread`. Also rethrows, to this caller, an exception an effect it triggered threw;
    * the effect stays subscribed and the rest of the graph is unaffected.
    */
  def set(a: A): Unit = {
    ThreadGuard.check("Var.set")
    if value != a then {
      value = a
      ver += 1
      markObserversDirty()
      Runtime.maybeFlush()
    }
  }

  def update(f: A => A): Unit = set(f(value))

  override def toString: String = s"Var($value)"

}

object Var {

  def apply[A](initial: A): Var[A] = new Var(initial)

}

/** A derived node: both a `Source` (others depend on it) and a `Computation`. */
final private class Computed[A](fn: () => Tracking ?=> A) extends Signal[A] with Source with Computation {

  private var cached: Any = ()
  private var hasValue = false
  // The last run's exception, rethrown by every read until a dependency changes. Kept
  // rather than thrown once, so a reader that arrives later sees the failure too instead
  // of a stale value.
  private var failure:            Throwable | Null = null
  private var ver:                Long = 0
  private[signals] val observers: mutable.LinkedHashSet[Computation] = mutable.LinkedHashSet.empty

  private[signals] def version:         Long = ver
  private[signals] def observersOfSelf: mutable.LinkedHashSet[Computation] = observers
  private[signals] def isEffect:        Boolean = false
  private[signals] def validate():      Unit = updateIfNecessary()

  /** Clean *before* running, so a failure leaves it Clean: the next change to a dependency then marks it Dirty and
    * tells its observers, which a node left Dirty would not (`markDirty` stops at an already-Dirty node).
    */
  private[signals] def recompute(): Unit = {
    state = State.Clean
    try {
      val next = trackDependencies(fn())
      if failure != null || !hasValue || cached != next then {
        cached = next
        ver += 1
      }
      failure = null
      hasValue = true
    } catch {
      case NonFatal(e) =>
        failure = e
        ver += 1
        throw e
    }
  }

  private def current: A = {
    val f = failure
    if f != null then throw f
    cached.asInstanceOf[A]
  }

  /** A computed whose owner is gone has no graph to stay current in, so reading it evaluates `fn` without tracking. It
    * used to return its never-computed placeholder, `()`, cast to `A` (review 2026-10-20, P1-6c).
    */
  def now: A =
    if disposed && !hasValue then Signal.untracked(fn())
    else {
      updateIfNecessary()
      current
    }

  /** Tracked *before* it is brought up to date, so a reader stays subscribed even when this throws — otherwise the
    * reader would lose the edge and never hear that the failure was fixed.
    */
  def apply()(using Tracking): A =
    if disposed && !hasValue then fn()
    else {
      Runtime.track(this)
      updateIfNecessary()
      current
    }

  override def toString: String = s"Computed(${if hasValue then cached else "<unevaluated>"})"

}

/** A leaf computation run for its side effects. */
final private class Effect(body: () => Tracking ?=> Unit) extends Computation {

  private val noObservers: mutable.LinkedHashSet[Computation] = mutable.LinkedHashSet.empty

  private[signals] def observersOfSelf: mutable.LinkedHashSet[Computation] = noObservers
  private[signals] def isEffect:        Boolean = true

  /** Clean before running, for the same reason as `Computed.recompute`: an effect that throws — on its first run or any
    * later one — stays subscribed to what it read and runs again when that changes. One left Dirty was dead for good
    * (review 2026-10-20, P1-6a).
    */
  private[signals] def recompute(): Unit = {
    state = State.Clean
    trackDependencies(body())
  }

  private[signals] def runInitial(): Unit = {
    state = State.Dirty
    recompute()
  }

}
