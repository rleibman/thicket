package scalaui.s9

import scalaui.core.UiThread
import scalaui.s9.generated.aliases.*
import scalaui.s9.generated.functions.*

/** The Apple implementation of `scalaui.core.UiThread`, and the thing issue #2 is really asking about. It is four
  * lines, but every one of them is a spike finding.
  *
  *   - `sui_run_on_main` is `DispatchQueue.main.async`. It targets the **main queue specifically**, never a worker
  *     queue: Scala on a GCD worker segfaults inside `Allocator_Alloc`, because the GC only knows threads it created
  *     itself and 0.5.12 has no API to attach a foreign one (S1).
  *   - The task travels as an `int64_t` handle-table id, because a C function pointer cannot carry a closure (S4) and
  *     an `int64_t` context avoids the `Long`⇄`Ptr` laundering that bites through `void*` (S7).
  *   - `Handles.mainTrampoline` wraps the call in `GcState.guarded`, which is what stops the GC deadlocking against
  *     CFRunLoop. Without it the first collection triggered by a ZIO fibre hangs and then aborts (S3). Every host→Scala
  *     entry has to be guarded, and this is the only entry the bridge creates.
  *   - `registerOneShot`, not `register`: the bridge posts one of these per signal write, so a table that never sheds
  *     entries is a leak proportional to UI activity (S8).
  */
object AppleUiThread {

  def install(): Unit =
    UiThread.install { f =>
      val id = Handles.registerOneShot(() => f())
      sui_run_on_main(sui_main_cb(Handles.mainTrampoline), id)
    }

  /** Posts still in flight. Used by the self-test to tell "delivered" from "dropped". */
  def pending: Int = Handles.count

}
