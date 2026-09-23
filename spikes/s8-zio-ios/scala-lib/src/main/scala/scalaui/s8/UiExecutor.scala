package scalaui.s8

import scalaui.s8.generated.aliases.*
import scalaui.s8.generated.functions.*
import zio.*
import zio.internal.ExecutionMetrics

/** A ZIO `Executor` that runs every task on the UIKit main thread.
  *
  * This is the concrete form of what `07` §7.13 proposes: fibers do the work, and anything touching the UI is handed to
  * the platform's main thread. The mechanism is the S3 shim — `sui_run_on_main` wraps `DispatchQueue.main.async`, and
  * the task travels as a handle-table id because a C callback cannot carry a closure.
  *
  * Two constraints from earlier spikes are load-bearing here:
  *   - The task must NOT be posted to a GCD *worker* queue. `sui_run_on_main` targets the main queue specifically, and
  *     the main thread is the one thread besides Scala's own that may re-enter Scala (S1: anything else segfaults in
  *     `Allocator_Alloc`).
  *   - `Handles.registerOneShot` is used rather than `register`, because at 60 Hz a table that never sheds entries is a
  *     leak with a clock on it.
  */
object UiExecutor extends Executor {

  private var submitted: Long = 0L
  private val lock = new Object

  def submit(runnable: Runnable)(implicit unsafe: Unsafe): Boolean = {
    lock.synchronized(submitted += 1)
    val id = Handles.registerOneShot(() => runnable.run())
    sui_run_on_main(sui_main_cb(Handles.mainTrampoline), id)
    true
  }

  def metrics(implicit unsafe: Unsafe): Option[ExecutionMetrics] = None

  def submittedCount: Long = lock.synchronized(submitted)

}
