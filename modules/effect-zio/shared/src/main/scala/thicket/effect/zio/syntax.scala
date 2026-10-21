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
import _root_.zio.stream.{SubscriptionRef, ZStream}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import thicket.core.{RemoteData, UiThread}
import thicket.signals.{Disposable, ErrorSink, Owner, Signal, Var}

/** The ZIO bridge: "F at the edges".
  *
  * `thicket-core` has no effect type anywhere in its API. Effects enter here, and the three things a UI actually needs
  * from an effect system are:
  *
  *   1. turn an effect into observable state ([[asSignal]]),
  *   2. run something for its side effects, tied to a component's lifetime ([[launch]]),
  *   3. keep both off the UI thread until the moment they touch it.
  *
  * Every fibre started here is owned by the enclosing `Owner`, so leaving a screen interrupts it — not by a cleanup
  * callback someone remembered to write, but by the same disposal that tears the widgets down.
  *
  * **Failures.** A typed failure (`ZIO.fail`) is the app's to handle and goes where the call says: `Failed` in the
  * signal, or the `ErrorPresenter`. A *defect* — a thrown exception, `ZIO.die` — has no typed home, so it is reported
  * to `ErrorSink` on the UI thread; it used to be dropped, leaving the screen at `Loading` with nothing logged (review
  * 2026-10-20, P1-5). Interruption is how a disposed owner stops its fibres, so it is not reported.
  *
  * **After disposal.** Interruption is asynchronous, so a result can already be on its way to the UI thread when the
  * owner is disposed. Every delivery checks the owner first, so a screen that has been left never shows its result — or
  * its error alert over the next screen.
  *
  * The `_root_.zio` imports: inside `thicket.effect.zio`, a bare `zio` would mean this package.
  */
private def onUi(f: () => Unit): Unit = UiThread.run(f)

/** Runs `f` on the UI thread, unless `owner` has been disposed by the time it gets there. */
private def deliver(owner: Owner)(f: => Unit): Unit = onUi(() => if !owner.disposed then f)

/** What to do with a cause that is not a typed failure: report it, unless it is only the interruption that disposal
  * sends.
  */
private def reportDefect(
  owner: Owner,
  cause: Cause[Any]
): Unit =
  if !cause.isInterruptedOnly then deliver(owner)(ErrorSink.report(FiberFailure(cause)))

/** Forks `effect`, owned by `owner`: disposing the owner interrupts it, and when it ends by itself the owner lets go of
  * it. It used to be held until the owner was disposed, so a screen that launched an effect per click kept one entry
  * per click for its whole life (review 2026-10-20, P2-11).
  *
  * The fibre can finish before its disposable exists; whichever side comes second releases it, exactly once.
  */
private def forkOwned[R, E, A](
  rt:          UiRuntime[R],
  effect:      ZIO[R, E, A]
)(using owner: Owner
): Unit = {
  val handle = AtomicReference[Disposable | Null](null)
  val done = AtomicBoolean(false)
  def releaseOnUi(): Unit = {
    val d = handle.getAndSet(null)
    if d != null then onUi(() => owner.release(d))
  }
  val fiber = rt.fork(effect.ensuring(ZIO.succeed { done.set(true); releaseOnUi() }))
  val d: Disposable = () => rt.interruptLater(fiber)
  owner.own(d)
  handle.set(d)
  if done.get() then releaseOnUi()
}

extension [R, E, A](effect: ZIO[R, E, A]) {

  /** Runs `effect` and exposes its progress as state.
    *
    * `Loading` until it completes, then `Done` or `Failed`. Because that is an exhaustive enum, a screen cannot forget
    * to render the loading or error case — the single most common way real UIs end up blank. A defect leaves it at
    * `Loading` and is reported to `ErrorSink`.
    */
  def asSignal(using
    rt:    UiRuntime[R],
    owner: Owner
  ): Signal[RemoteData[E, A]] = {
    val state = Var[RemoteData[E, A]](RemoteData.Loading)
    forkOwned(
      rt,
      effect.foldCause(
        cause =>
          cause.failureOption match {
            case Some(e) => deliver(owner)(state.set(RemoteData.Failed(e)))
            case None    => reportDefect(owner, cause)
          },
        a => deliver(owner)(state.set(RemoteData.Done(a)))
      )
    )
    state
  }

  /** Runs `effect` for its side effects, owned by the enclosing component.
    *
    * Typed failures go through the ambient [[ErrorPresenter]], so an unhandled typed error is a *compile* error at the
    * call site rather than a silent swallow. Defects go to `ErrorSink`.
    */
  def launch(using
    rt:      UiRuntime[R],
    owner:   Owner,
    present: ErrorPresenter[E]
  ): Unit = {
    forkOwned(
      rt,
      effect.foldCause(
        cause =>
          cause.failureOption match {
            case Some(e) => deliver(owner)(present.show(e))
            case None    => reportDefect(owner, cause)
          },
        _ => ()
      )
    )
  }

}

/** Delivers the latest of a fast stream of values to the UI thread, without queueing one post per value.
  *
  * A stream can produce far faster than a frame. Posting each element made the UI thread replay every intermediate
  * value (review 2026-10-20, P2-13); this keeps only the newest and has at most one post in flight. The value written
  * is always the last one produced, so nothing a screen would show is lost — only states it would have overwritten
  * before drawing.
  */
final private class Conflated[A](
  owner: Owner,
  write: A => Unit
) {

  private val latest = AtomicReference[Option[A]](None)
  private val posted = AtomicBoolean(false)

  def offer(a: A): Unit = {
    latest.set(Some(a))
    if !posted.getAndSet(true) then
      deliver(owner) {
        posted.set(false)
        latest.getAndSet(None).foreach(write)
      }
  }

}

extension [R, E, A](stream: ZStream[R, E, A]) {

  /** Mirrors a stream into a signal, starting at `initial`. Elements that arrive faster than the UI thread can take
    * them are conflated: the signal sees the newest.
    *
    * The consuming fibre belongs to the enclosing component, so navigating away closes the subscription — the socket,
    * the database listener, whatever it is. A stream that fails or dies is reported to `ErrorSink` (there is no typed
    * channel to put it in; use [[asRemoteSignal]] for one).
    */
  def asSignal(
    initial: A
  )(using
    rt:    UiRuntime[R],
    owner: Owner
  ): Signal[A] = {
    val state = Var(initial)
    val sink = Conflated[A](owner, state.set)
    forkOwned(
      rt,
      stream.runForeach(a => ZIO.succeed(sink.offer(a))).foldCause(cause => reportDefect(owner, cause), _ => ())
    )
    state
  }

  /** As [[asSignal]], but surfacing the stream's typed failure the way `ZIO.asSignal` does. */
  def asRemoteSignal(using
    rt:    UiRuntime[R],
    owner: Owner
  ): Signal[RemoteData[E, A]] = {
    val state = Var[RemoteData[E, A]](RemoteData.Loading)
    val sink = Conflated[RemoteData[E, A]](owner, state.set)
    forkOwned(
      rt,
      stream
        .runForeach(a => ZIO.succeed(sink.offer(RemoteData.Done(a))))
        .foldCause(
          cause =>
            cause.failureOption match {
              case Some(e) => sink.offer(RemoteData.Failed(e))
              case None    => reportDefect(owner, cause)
            },
          _ => ()
        )
    )
    state
  }

}

extension [A](ref: SubscriptionRef[A]) {

  /** A `SubscriptionRef` is the natural shared-state primitive for an app: any fibre writes it, and the UI observes the
    * changes.
    *
    * Seeded with the ref's current value, read on the calling thread. That read is `Ref.get` — synchronous, no
    * suspension — so it completes on this thread without waiting on any other fibre.
    */
  def asSignal(using
    rt:    UiRuntime[Any],
    owner: Owner
  ): Signal[A] = {
    val initial = Unsafe.unsafe { u =>
      given Unsafe = u
      rt.runtime.unsafe.run(ref.get).getOrThrow()
    }
    ref.changes.asSignal(initial)
  }

}
