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

package thicket.effect.zio

import _root_.zio.*

/** The app's ZIO runtime, built once by the host from its `ZLayer` and then passed implicitly to every component that
  * runs effects.
  *
  * Deliberately *not* pinned to a UI executor. Forcing every fibre onto the UI thread would make ordinary CPU work
  * block the interface; the bridge instead lets ZIO schedule as it likes and posts only the signal writes back through
  * `UiThread`. That is also what keeps it safe on Apple targets, where Scala cannot run on an arbitrary foreign thread
  * (S1) — the bridge never asks it to.
  */
final class UiRuntime[R] private (
  private[zio] val runtime: Runtime[R],
  release:                  () => Unit
) {

  private[zio] def fork[E, A](effect: ZIO[R, E, A]): Fiber.Runtime[E, A] =
    Unsafe.unsafe(u => runtime.unsafe.fork(effect)(using summon[Trace], u))

  /** Fire-and-forget interruption, for teardown paths that must not block the UI thread. */
  private[zio] def interruptLater[E, A](fiber: Fiber.Runtime[E, A]): Unit = {
    val _ = Unsafe.unsafe(u => runtime.unsafe.fork(fiber.interrupt)(using summon[Trace], u))
  }

  /** Releases what the runtime's layer acquired, running its finalizers. Idempotent. Blocks until they finish, so call
    * it off the UI thread or at exit. A runtime made with [[UiRuntime.fromLayer]] also does this from a JVM/Native
    * shutdown hook; an Android process is killed without running hooks, so an Activity that owns one calls this from
    * `onDestroy`.
    */
  def shutdown(): Unit = release()

}

object UiRuntime {

  def apply[R](runtime: Runtime[R]): UiRuntime[R] = new UiRuntime(runtime, () => ())

  /** For apps whose services need no environment. */
  val default: UiRuntime[Any] = apply(Runtime.default)

  /** Builds a runtime from the app's layer. The scope stays open until [[UiRuntime.shutdown]], or until the process
    * exits — services are acquired at start-up and released then.
    *
    * It used to discard the scope, so no layer finalizer ever ran (review 2026-10-20, P2-15).
    *
    * **Throws** if the layer fails to build: a `FiberFailure` carrying the layer's error. There is no app to run
    * without its services, so this is the place to fail, at start-up, rather than on the first effect that needs one.
    */
  def fromLayer[R](layer: ZLayer[Any, Throwable, R]): UiRuntime[R] = {
    val scoped = Unsafe.unsafe(u => Runtime.unsafe.fromLayer(layer.orDie)(using summon[Trace], u))
    val released = java.util.concurrent.atomic.AtomicBoolean(false)
    val release = () => if !released.getAndSet(true) then Unsafe.unsafe(u => scoped.unsafe.shutdown()(using u))
    java.lang.Runtime.getRuntime.addShutdownHook(Thread(() => release()))
    new UiRuntime(scoped, release)
  }

}
