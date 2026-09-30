package thicket.zio

import java.util.concurrent.ConcurrentLinkedQueue
import thicket.core.UiThread

/** A `UiThread` that really marshals, for tests that drive effects from ZIO fibres.
  *
  * `UiThread.install(f => f())` looks like the simplest possible stub and is the wrong one here. It runs the post
  * *inline on the fibre's thread*, so `Var.set` happens off the test thread — and the signal graph is single-threaded
  * by construction, with plain `var`s and no barriers, which is exactly what `ThreadGuard` exists to enforce. A test
  * thread spinning on `signal.now` is then racing on memory visibility, and it loses often enough to be seen: the
  * RemoteScreen error test failed roughly half of runs.
  *
  * So posts are queued and drained on the thread that is waiting, which is what a real host does — GTK drains them on
  * the main loop, Android on the looper — and it makes the tests exercise the marshalling rather than skip it.
  */
private[zio] object TestUiThread {

  private val queue = new ConcurrentLinkedQueue[() => Unit]()

  def install(): Unit = {
    queue.clear()
    UiThread.install(f => { val _ = queue.add(f) })
  }

  /** Run everything posted so far, on the calling thread. */
  def drain(): Unit = {
    var f = queue.poll()
    while f != null do {
      f()
      f = queue.poll()
    }
  }

  /** Spin until `cond` holds, draining posts as they arrive. The drain is the point: the condition can only become true
    * once the posted writes have run here.
    */
  def eventually(what: String)(cond: => Boolean): Unit = {
    val deadline = java.lang.System.currentTimeMillis() + 3000
    while !cond && java.lang.System.currentTimeMillis() < deadline do {
      drain()
      Thread.sleep(2)
    }
    drain()
    if !cond then throw new AssertionError(s"timed out waiting for: $what")
  }

}
