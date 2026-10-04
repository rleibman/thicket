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

import _root_.zio.*

/** The app's ZIO runtime, built once by the host from its `ZLayer` and then passed implicitly to every component that
  * runs effects.
  *
  * Deliberately *not* pinned to a UI executor. Forcing every fibre onto the UI thread would make ordinary CPU work
  * block the interface; the bridge instead lets ZIO schedule as it likes and posts only the signal writes back through
  * `UiThread`. That is also what keeps it safe on Apple targets, where Scala cannot run on an arbitrary foreign thread
  * (S1) — the bridge never asks it to.
  */
final class UiRuntime[R] private (private[zio] val runtime: Runtime[R]) {

  private[zio] def fork[E, A](effect: ZIO[R, E, A]): Fiber.Runtime[E, A] =
    Unsafe.unsafe(implicit u => runtime.unsafe.fork(effect))

  /** Fire-and-forget interruption, for teardown paths that must not block the UI thread. */
  private[zio] def interruptLater[E, A](fiber: Fiber.Runtime[E, A]): Unit = {
    val _ = Unsafe.unsafe(implicit u => runtime.unsafe.fork(fiber.interrupt))
  }

}

object UiRuntime {

  def apply[R](runtime: Runtime[R]): UiRuntime[R] = new UiRuntime(runtime)

  /** For apps whose services need no environment. */
  val default: UiRuntime[Any] = new UiRuntime(Runtime.default)

  /** Builds a runtime from the app's layer. The scope stays open for the process lifetime, which is what an app wants:
    * services are acquired at start-up and released at exit.
    */
  def fromLayer[R](layer: ZLayer[Any, Throwable, R]): UiRuntime[R] = {
    val rt = Unsafe.unsafe(implicit u => Runtime.unsafe.fromLayer(layer.orDie))
    new UiRuntime(rt)
  }

}
