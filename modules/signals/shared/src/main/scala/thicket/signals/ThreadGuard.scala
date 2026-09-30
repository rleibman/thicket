package thicket.signals

/** Enforces the single-threaded contract (docs/05 A-06).
  *
  * The default is [[off]]: a library cannot know which thread is "the" UI thread. The framework installs
  * [[owningThread]] (or its own UI-thread guard) during start-up, once, on the thread that owns the graph.
  */
trait ThreadGuard {

  /** Throws `IllegalStateException` if `op` is being performed on the wrong thread. */
  def check(op: String): Unit

}

object ThreadGuard {

  /** Allows everything. The default, and the only sensible guard on Scala.js. */
  val off: ThreadGuard = _ => ()

  /** A fresh guard that binds to the first thread which touches it and rejects every other one. On Scala.js this is
    * [[off]], since there are no other threads.
    */
  def owningThread: ThreadGuard = ThreadGuardPlatform.owningThread

  private var current: ThreadGuard = off

  def install(g: ThreadGuard): Unit = current = g
  def installed:               ThreadGuard = current
  def check(op:  String):      Unit = current.check(op)

}
