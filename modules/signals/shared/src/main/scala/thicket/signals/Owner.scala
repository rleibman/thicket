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

/** A lifetime. Computeds and effects created under an owner are disposed with it, which is how a UI component
  * unsubscribes everything it created when it unmounts (docs/07 §7.2). Disposal is explicit: no weak references, no GC
  * dependence.
  */
final class Owner private[signals] (parent: Owner | Null) extends Disposable {

  private val children: mutable.ArrayBuffer[Disposable] = mutable.ArrayBuffer.empty
  private var isDisposed = false

  if parent != null then parent.nn.children += this

  private def forget(child: Disposable): Unit =
    if !isDisposed then {
      val i = children.indexOf(child)
      if i >= 0 then children.remove(i)
    }

  def own(d: Disposable): Unit =
    if isDisposed then d.dispose()
    else children += d

  def disposed: Boolean = isDisposed

  /** Disposes children in reverse creation order, then itself, and unlinks from its parent. Idempotent.
    *
    * The unlink matters: a dynamic region (`Show`, `ForEach`) creates and disposes a child owner on every update, and
    * without this the parent's buffer would grow forever.
    */
  def dispose(): Unit =
    if !isDisposed then {
      isDisposed = true
      var i = children.length - 1
      while i >= 0 do {
        children(i).dispose()
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
