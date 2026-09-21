package scalaui.signals

import scala.collection.mutable

/** Something that can be torn down. */
trait Disposable {
  def dispose(): Unit
}

object Disposable {
  val noop: Disposable = () => ()
}

/** A value that changes over time. Reads are synchronous and always up to date.
  *
  *   - [[now]] reads without subscribing (safe anywhere).
  *   - [[apply]] reads *and subscribes*, and is only callable inside a reactive
  *     computation, where a `Tracking` is in scope.
  */
trait Signal[+A] {
  /** Untracked read. */
  def now: A

  /** Tracked read: the enclosing computation re-runs when this changes. */
  def apply()(using Tracking): A

  def map[B](f: A => B)(using Owner): Signal[B] =
    Signal.computed(f(this.apply()))

  def zip[B](that: Signal[B])(using Owner): Signal[(A, B)] =
    Signal.computed((this.apply(), that.apply()))
}

object Signal {
  /** Never changes; does not participate in the graph. */
  def const[A](a: A): Signal[A] = new Signal[A] {
    def now: A                        = a
    def apply()(using Tracking): A    = a
    override def map[B](f: A => B)(using Owner): Signal[B] = const(f(a))
  }

  /** A derived value. Lazy: recomputed only when read after a dependency changed. */
  def computed[A](f: Tracking ?=> A)(using owner: Owner): Signal[A] = {
    val c = new Computed[A](() => f)
    owner.own(c)
    c
  }

  /** Runs `body` now, and again whenever a tracked dependency changes. */
  def effect(body: Tracking ?=> Unit)(using owner: Owner): Disposable = {
    val e = new Effect(() => body)
    owner.own(e) // disposes `e` immediately if the owner is already dead
    if !e.disposed then e.runInitial()
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
  private var value: A   = initial
  private var ver: Long  = 0
  private[signals] val observers: mutable.ArrayBuffer[Computation] = mutable.ArrayBuffer.empty

  private[signals] def version: Long  = ver
  private[signals] def validate(): Unit = () // roots are always current

  def now: A                     = value
  def apply()(using Tracking): A = {
    Runtime.track(this)
    value
  }

  /** Writes the value. No-op if `a == current` (equality cutoff). */
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
private final class Computed[A](fn: () => Tracking ?=> A)
    extends Signal[A]
    with Source
    with Computation {

  private var cached: Any   = ()
  private var hasValue      = false
  private var ver: Long     = 0
  private[signals] val observers: mutable.ArrayBuffer[Computation] = mutable.ArrayBuffer.empty

  private[signals] def version: Long                                   = ver
  private[signals] def observersOfSelf: mutable.ArrayBuffer[Computation] = observers
  private[signals] def isEffect: Boolean                               = false
  private[signals] def validate(): Unit                                = updateIfNecessary()

  private[signals] def recompute(): Unit = {
    val next = trackDependencies(fn())
    if !hasValue || cached != next then {
      cached = next
      ver += 1
    }
    hasValue = true
    state = State.Clean
  }

  def now: A = {
    updateIfNecessary()
    cached.asInstanceOf[A]
  }

  def apply()(using Tracking): A = {
    updateIfNecessary()
    Runtime.track(this)
    cached.asInstanceOf[A]
  }

  override def toString: String = s"Computed(${if hasValue then cached else "<unevaluated>"})"
}

/** A leaf computation run for its side effects. */
private final class Effect(body: () => Tracking ?=> Unit) extends Computation {
  private val noObservers: mutable.ArrayBuffer[Computation] = mutable.ArrayBuffer.empty

  private[signals] def observersOfSelf: mutable.ArrayBuffer[Computation] = noObservers
  private[signals] def isEffect: Boolean                                = true

  private[signals] def recompute(): Unit = {
    trackDependencies(body())
    state = State.Clean
  }

  private[signals] def runInitial(): Unit = {
    state = State.Dirty
    recompute()
  }
}
