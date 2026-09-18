package scalaui.signals

import scala.collection.mutable

/** Evidence that the surrounding code runs inside a reactive computation, and that
  * reads performed here are therefore *tracked*. Only the library can create it, so
  * `signal()` is impossible outside `computed`/`effect`/`untracked`-free positions.
  */
final class Tracking private[signals] ()

private[signals] object State:
  inline val Clean = 0
  inline val Check = 1 // a (transitive) dependency may have changed
  inline val Dirty = 2 // a direct dependency definitely changed

/** A node others can depend on. */
private[signals] trait Source:
  /** Bumped only when the node's value actually changes (`!=`). */
  private[signals] def version: Long

  /** Bring this node up to date, recomputing if necessary. */
  private[signals] def validate(): Unit

  private[signals] def observers: mutable.ArrayBuffer[Computation]

  private[signals] final def addObserver(c: Computation): Unit =
    if !observers.contains(c) then observers += c

  private[signals] final def removeObserver(c: Computation): Unit =
    val i = observers.indexOf(c)
    if i >= 0 then observers.remove(i)

  /** Push phase: mark direct observers Dirty, transitive observers Check. */
  private[signals] final def markObserversDirty(): Unit =
    var i = 0
    while i < observers.length do
      observers(i).markDirty()
      i += 1

/** A node that derives from other nodes: `Computed` or `Effect`. */
private[signals] trait Computation extends Disposable:
  private[signals] var state: Int = State.Dirty
  private[signals] var deps: Array[Source] = Array.empty
  private[signals] var depVersions: Array[Long] = Array.empty
  private[signals] var disposed: Boolean = false

  /** Effects are scheduled when marked; computeds are pulled lazily. */
  private[signals] def isEffect: Boolean

  private[signals] def recompute(): Unit

  private[signals] def observersOfSelf: mutable.ArrayBuffer[Computation]

  private[signals] final def markDirty(): Unit =
    if !disposed && state != State.Dirty then
      val wasClean = state == State.Clean
      state = State.Dirty
      if isEffect then Runtime.schedule(this)
      if wasClean then markDownstreamCheck()

  private[signals] final def markCheck(): Unit =
    if !disposed && state == State.Clean then
      state = State.Check
      if isEffect then Runtime.schedule(this)
      markDownstreamCheck()

  private[signals] final def markDownstreamCheck(): Unit =
    val obs = observersOfSelf
    var i = 0
    while i < obs.length do
      obs(i).markCheck()
      i += 1

  /** Pull phase: validate dependencies depth-first, then recompute if needed. */
  private[signals] final def updateIfNecessary(): Unit =
    if !disposed then
      if state == State.Check then
        var i     = 0
        var dirty = false
        while i < deps.length && !dirty do
          deps(i).validate()
          if deps(i).version != depVersions(i) then dirty = true
          i += 1
        state = if dirty then State.Dirty else State.Clean
      if state == State.Dirty then recompute()

  /** Run `body` with this node as the tracking observer, then relink dependencies. */
  private[signals] final def trackDependencies[A](body: Tracking ?=> A): A =
    val prevObserver  = Runtime.observer
    val prevCollected = Runtime.collected
    Runtime.observer = this
    Runtime.collected = mutable.ArrayBuffer.empty
    try
      val result = body(using Runtime.tracking)
      relink(Runtime.collected)
      result
    catch
      case e: Throwable =>
        // Keep the graph consistent: still relink to whatever was read before the
        // failure, so a later retry sees the same edges.
        relink(Runtime.collected)
        throw e
    finally
      Runtime.observer = prevObserver
      Runtime.collected = prevCollected

  private def relink(collected: mutable.ArrayBuffer[Source]): Unit =
    val fresh = collected.distinct

    // Fast path: dependencies are unchanged, which is overwhelmingly the common
    // case. Skipping the unsubscribe/resubscribe avoids scanning observer lists,
    // which is what made a 1000-wide fan-out quadratic (see REPORT.md).
    if fresh.length == deps.length then
      var same = true
      var j    = 0
      while j < deps.length && same do
        if fresh(j) ne deps(j) then same = false
        j += 1
      if same then
        j = 0
        while j < deps.length do
          depVersions(j) = deps(j).version
          j += 1
        return

    var i = 0
    while i < deps.length do
      val d = deps(i)
      if !fresh.contains(d) then d.removeObserver(this)
      i += 1
    deps = fresh.toArray
    depVersions = new Array[Long](deps.length)
    i = 0
    while i < deps.length do
      deps(i).addObserver(this)
      depVersions(i) = deps(i).version
      i += 1

  private[signals] final def unlinkAll(): Unit =
    var i = 0
    while i < deps.length do
      deps(i).removeObserver(this)
      i += 1
    deps = Array.empty
    depVersions = Array.empty

  def dispose(): Unit =
    if !disposed then
      disposed = true
      unlinkAll()
      state = State.Clean

/** Global, single-threaded reactive runtime state. */
private[signals] object Runtime:
  val tracking: Tracking = new Tracking()

  // `null` is used deliberately in these two hot fields: an Option allocation per
  // tracked read is measurable at the 1 µs/node budget (N-05 analogue). Deviation
  // from the "no null" house style is confined to this object.
  var observer: Computation | Null                       = null
  var collected: mutable.ArrayBuffer[Source] | Null      = null

  private val pending: mutable.ArrayDeque[Computation] = mutable.ArrayDeque.empty
  private var batchDepth: Int                          = 0
  private var flushing: Boolean                        = false

  def track(s: Source): Unit =
    val c = collected
    if c != null then c += s

  def schedule(c: Computation): Unit =
    pending += c

  def batched[A](body: => A): A =
    batchDepth += 1
    try body
    finally
      batchDepth -= 1
      if batchDepth == 0 then flush()

  def maybeFlush(): Unit =
    if batchDepth == 0 then flush()

  /** Runs every scheduled effect. Errors are collected so that one failing effect
    * cannot leave the rest of the graph stale; the first is rethrown afterwards.
    */
  def flush(): Unit =
    if !flushing then
      flushing = true
      var errors: List[Throwable]   = Nil
      var failed: List[Computation] = Nil
      try
        while pending.nonEmpty do
          val c = pending.removeHead()
          try c.updateIfNecessary()
          catch
            case e: Throwable =>
              errors = e :: errors
              // Invariant: an effect in a non-Clean state must be scheduled, or a
              // later write to an already-Dirty dependency will short-circuit and
              // never reach it. A failed effect is still stale, so re-queue it for
              // the next flush (outside this loop, to avoid spinning).
              failed = c :: failed
      finally
        flushing = false
        failed.reverse.foreach(pending += _)
      errors.reverse match
        case Nil      => ()
        case e :: Nil => throw e
        case e :: more =>
          more.foreach(e.addSuppressed)
          throw e

  /** Test/benchmark hook: number of effects waiting to run. */
  def pendingCount: Int = pending.size
