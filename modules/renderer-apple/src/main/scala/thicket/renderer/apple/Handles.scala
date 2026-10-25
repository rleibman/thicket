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

package thicket.renderer.apple

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.scalanative.unsafe.*
import thicket.core.GcState

/** `Long` id -> closure, because a C function pointer cannot carry one (S4), and an `int64_t` context avoids the `Long`
  * <-> `Ptr` laundering a `void*` forces (S7).
  *
  * **Lock-free, deliberately.** S9 found that guarding this map with a monitor — the obvious design, and correct on the
  * JVM — throws `IllegalMonitorStateException` on Scala Native under real load, and leaks main-thread stack until the
  * process dies. See `spikes/s9-zio-bridge-ios/REPORT.md`.
  */
object Handles {

  private val nextId = new AtomicLong(1L)

  /** The closures of one callback shape, by the id the shim hands back. Every id comes from the one `nextId`, so an id
    * names one closure in one table, and [[release]] can drop it without knowing which.
    */
  final class Table[F] private[Handles] () {

    private val byId = new ConcurrentHashMap[java.lang.Long, F]()

    def register(f: F): Long = {
      val id = nextId.getAndIncrement()
      val _ = byId.put(id, f)
      id
    }

    /** Swapping the closure behind an existing id, rather than registering a second handler, is what stops repeated
      * property updates stacking handlers.
      */
    def replace(
      id: Long,
      f:  F
    ): Unit = { val _ = byId.put(id, f) }

    /** The closure, or `null` once released — what the trampolines check. */
    private[Handles] def get(id:    Long): F = byId.get(id)
    private[Handles] def remove(id: Long): Unit = { val _ = byId.remove(id) }
    def size:                              Int = byId.size

  }

  val taps = Table[() => Unit]()
  val texts = Table[String => Unit]()
  val bools = Table[Boolean => Unit]()
  val values = Table[Double => Unit]()
  val ints = Table[Int => Unit]()
  val rows = Table[(Int, Option[Shim.Handle]) => Shim.Handle]()

  /** The one register-or-replace path for a handler property (#79; GTK's `Handles.connect`, WP-16, is the model).
    *
    * The first time `handle` gets this kind of handler, `f` is registered, its id kept in `ids`, and `attach` hands the
    * id to the shim (`sui_on_tap(handle, tapTrampoline, id)` and the like). Every later time the closure behind that id
    * is swapped and the shim is not called again. `AppleRenderer` had this written out once per handler, seven times.
    */
  def connect[F](
    table:  Table[F],
    ids:    scala.collection.mutable.Map[Shim.Handle, Long],
    handle: Shim.Handle,
    f:      F
  )(
    attach: Long => Unit
  ): Unit =
    ids.get(handle) match {
      case Some(id) => table.replace(id, f)
      case None     =>
        val id = table.register(f)
        ids(handle) = id
        attach(id)
    }

  /** For the handlers that are not a property kept per handle: alert and menu actions, the pages' pop callback. */
  def register(f:    () => Unit):                                Long = taps.register(f)
  def registerInt(f: Int => Unit):                               Long = ints.register(f)
  def registerRow(f: (Int, Option[Shim.Handle]) => Shim.Handle): Long = rows.register(f)

  def release(id: Long): Unit = {
    taps.remove(id)
    texts.remove(id)
    bools.remove(id)
    values.remove(id)
    ints.remove(id)
    rows.remove(id)
  }

  /** One-shot, for `runOnUiThread`: at frame rate a table that never sheds entries is a leak with a clock on it (S8).
    */
  def registerOneShot(f: () => Unit): Long = {
    // The closure needs its own id, so the id is taken first and the closure put behind it.
    val id = nextId.getAndIncrement()
    taps.replace(
      id,
      () => {
        taps.remove(id)
        f()
      }
    )
    id
  }

  // `ints` was missing, so a Picker, SegmentedControl, DatePicker or TabView that leaked its handler
  // was invisible to every leak check built on this (review 2026-10-20, P2-5).
  def count: Int = taps.size + texts.size + bools.size + values.size + ints.size + rows.size

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
        GcState.guardedOr(null.asInstanceOf[Shim.Handle]) {
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

  val intTrampoline: Shim.IntCb =
    CFuncPtr2.fromScalaFunction(
      (
        id:    Long,
        value: CInt
      ) =>
        GcState.guarded {
          val f = ints.get(id)
          if f != null then f(value)
        }
    )

}
