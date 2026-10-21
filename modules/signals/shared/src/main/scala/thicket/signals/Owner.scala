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

/** A lifetime. Computeds and effects created under an owner are disposed with it, which is how a UI component
  * unsubscribes everything it created when it unmounts (docs/07 §7.2). Disposal is explicit: no weak references, no GC
  * dependence.
  */
final class Owner private[signals] (parent: Owner | Null) extends Disposable {

  // Insertion-ordered, so disposal can still run in reverse creation order, and a set, so a
  // child that leaves early is removed in O(1): a scan per removal made unmounting a list's
  // rows O(rows²) (review 2026-10-20, P2-11).
  private val children: mutable.LinkedHashSet[Disposable] = mutable.LinkedHashSet.empty

  /** Born disposed under a disposed parent. Otherwise nothing would ever dispose it, and the effects created under it
    * would keep writing to native handles that were already destroyed (review 2026-10-20, P1-6b).
    */
  private var isDisposed = parent != null && parent.nn.disposed

  if parent != null && !isDisposed then parent.nn.children += this

  private[signals] def forget(child: Disposable): Unit =
    if !isDisposed then children -= child

  def own(d: Disposable): Unit =
    if isDisposed then d.dispose()
    else children += d

  def disposed: Boolean = isDisposed

  /** Stops owning `d` without disposing it — for something that ended by itself, such as a finished fibre, which would
    * otherwise be held until this owner is disposed.
    */
  def release(d: Disposable): Unit = forget(d)

  /** How many things this owner currently holds. For leak checks: it should fall when they end, not only when the owner
    * does.
    */
  def childCount: Int = children.size

  /** Disposes children in reverse creation order, then itself, and unlinks from its parent. Idempotent.
    *
    * The unlink matters: a dynamic region (`Show`, `ForEach`) creates and disposes a child owner on every update, and
    * without this the parent's buffer would grow forever.
    *
    * A child that throws while disposing is reported to [[ErrorSink]] and the rest are still disposed: stopping
    * half-way would leave live effects bound to a screen that is gone (review 2026-10-20, P2-18).
    */
  def dispose(): Unit =
    if !isDisposed then {
      isDisposed = true
      val all = children.toArray
      var i = all.length - 1
      while i >= 0 do {
        try all(i).dispose()
        catch { case NonFatal(e) => ErrorSink.report(e) }
        i -= 1
      }
      children.clear()
      if parent != null then parent.nn.forget(this)
    }

}

object Owner {

  /** A new detached lifetime; caller is responsible for disposing it. */
  def apply(): Owner = new Owner(null)

  /** A lifetime nested in the current one. */
  def child(using parent: Owner): Owner = new Owner(parent)

  /** Runs `body` under a *child* of the current owner and returns that child, so nested lifetimes do not need a second
    * named `given` in the same scope.
    */
  def scoped[A](body: Owner ?=> A)(using parent: Owner): Owner = {
    val o = new Owner(parent)
    body(using o)
    o
  }

  /** Runs `body` under a fresh owner and hands both back. */
  def root[A](body: Owner ?=> A): (A, Owner) = {
    val o = Owner()
    (body(using o), o)
  }

}
