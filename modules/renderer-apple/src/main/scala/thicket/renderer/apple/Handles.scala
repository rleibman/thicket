package thicket.renderer.apple

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.scalanative.unsafe.*

/** `Long` id -> closure, because a C function pointer cannot carry one (S4), and an `int64_t` context avoids the `Long`
  * <-> `Ptr` laundering a `void*` forces (S7).
  *
  * **Lock-free, deliberately.** S9 found that guarding this map with a monitor — the obvious design, and correct on the
  * JVM — throws `IllegalMonitorStateException` on Scala Native under real load, and leaks main-thread stack until the
  * process dies. See `spikes/s9-zio-bridge-ios/REPORT.md`.
  */
object Handles {

  private val taps = new ConcurrentHashMap[java.lang.Long, () => Unit]()
  private val texts = new ConcurrentHashMap[java.lang.Long, String => Unit]()
  private val bools = new ConcurrentHashMap[java.lang.Long, Boolean => Unit]()
  private val values = new ConcurrentHashMap[java.lang.Long, Double => Unit]()
  private val rows = new ConcurrentHashMap[java.lang.Long, (Int, Option[Shim.Handle]) => Shim.Handle]()
  private val nextId = new AtomicLong(1L)

  def register(f: () => Unit): Long = {
    val id = nextId.getAndIncrement()
    taps.put(id, f)
    id
  }

  def registerText(f: String => Unit): Long = {
    val id = nextId.getAndIncrement()
    texts.put(id, f)
    id
  }

  def registerBool(f: Boolean => Unit): Long = {
    val id = nextId.getAndIncrement()
    bools.put(id, f)
    id
  }

  def registerValue(f: Double => Unit): Long = {
    val id = nextId.getAndIncrement()
    values.put(id, f)
    id
  }

  def registerRow(f: (Int, Option[Shim.Handle]) => Shim.Handle): Long = {
    val id = nextId.getAndIncrement()
    rows.put(id, f)
    id
  }

  /** Swapping the closure behind an existing id, rather than registering a second handler, is what stops repeated
    * property updates stacking handlers.
    */
  def replace(
    id: Long,
    f:  () => Unit
  ): Unit = taps.put(id, f)
  def replaceText(
    id: Long,
    f:  String => Unit
  ): Unit = texts.put(id, f)
  def replaceBool(
    id: Long,
    f:  Boolean => Unit
  ): Unit = bools.put(id, f)
  def replaceValue(
    id: Long,
    f:  Double => Unit
  ): Unit = values.put(id, f)

  def release(id: Long): Unit = {
    val _ = taps.remove(id)
    val _ = texts.remove(id)
    val _ = bools.remove(id)
    val _ = values.remove(id)
    val _ = rows.remove(id)
  }

  /** One-shot, for `runOnUiThread`: at frame rate a table that never sheds entries is a leak with a clock on it (S8).
    */
  def registerOneShot(f: () => Unit): Long = {
    val id = nextId.getAndIncrement()
    taps.put(
      id,
      () => {
        val _ = taps.remove(id)
        f()
      }
    )
    id
  }

  def count: Int = taps.size + texts.size + bools.size + values.size + rows.size

  /** Row callbacks alone. `count` also moves with every `postToUi` one-shot, which is noise when the question is
    * whether an unmounted virtual list let go of its `RowSource`.
    */
  def rowCount: Int = rows.size

  // One static trampoline per callback shape: a CFuncPtr cannot close over state, so the
  // id is the only thing that distinguishes one handler from another (S4).
  val tapTrampoline: Shim.VoidCb =
    CFuncPtr1.fromScalaFunction((id: Long) =>
      GcState.guarded {
        val f = taps.get(id)
        if f != null then f()
      }
    )

  val textTrampoline: Shim.TextCb =
    CFuncPtr2.fromScalaFunction(
      (
        id:    Long,
        value: CString
      ) =>
        GcState.guarded {
          val f = texts.get(id)
          if f != null then f(if value == null then "" else fromCString(value))
        }
    )

  /** The only trampoline that returns a value, because a table asks for a row rather than being told about one.
    * Returning `null` means "no view", which both shims treat as "leave the row empty" rather than crashing.
    */
  val rowTrampoline: Shim.RowCb =
    CFuncPtr3.fromScalaFunction(
      (
        id:       Long,
        index:    CInt,
        recycled: Shim.Handle
      ) =>
        GcState.guarded {
          val f = rows.get(id)
          if f == null then null.asInstanceOf[Shim.Handle]
          else f(index, if recycled == null then None else Some(recycled))
        }
    )

  val boolTrampoline: Shim.BoolCb =
    CFuncPtr2.fromScalaFunction(
      (
        id:    Long,
        value: CInt
      ) =>
        GcState.guarded {
          val f = bools.get(id)
          if f != null then f(value != 0)
        }
    )

  /** A slider's value arrives in the app's own units: both Apple controls take them directly, so there is nothing to
    * convert, unlike Android's integral `SeekBar`.
    */
  val valueTrampoline: Shim.ValueCb =
    CFuncPtr2.fromScalaFunction(
      (
        id:    Long,
        value: Double
      ) =>
        GcState.guarded {
          val f = values.get(id)
          if f != null then f(value)
        }
    )

}
