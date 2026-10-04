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

package thicket.zio

/** How a component reports a failure it did not handle.
  *
  * `launch` requires one, so a typed error channel cannot be silently discarded: either the effect's error type is
  * `Nothing`, or the call site must say what a failure looks like to the user. That is the point of ZIO's typed errors
  * reaching the view layer at all.
  */
trait ErrorPresenter[-E] {

  def show(error: E): Unit

}

object ErrorPresenter {

  /** An effect that cannot fail needs no presenter. */
  given ErrorPresenter[Nothing] = (_: Nothing) => ()

  /** Report through a callback — a toast, an alert, a snackbar the host supplies. */
  def apply[E](f: E => Unit): ErrorPresenter[E] = (e: E) => f(e)

  /** Push the failure into a signal a screen can render. */
  def into[E](target: thicket.signals.Var[Option[E]]): ErrorPresenter[E] = (e: E) => target.set(Some(e))

  /** Last resort for prototypes: print it. Deliberately not a `given`, so choosing to ignore errors is always visible
    * at the call site.
    */
  def printing[E]: ErrorPresenter[E] = (e: E) => System.err.println(s"[Thicket] unhandled: $e")

}
