package thicket.zio

import _root_.zio.*
import _root_.zio.stream.{SubscriptionRef, ZStream}
import thicket.core.{RemoteData, UiThread}
import thicket.signals.{Disposable, Owner, Signal, Var}

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
  * Note the `_root_.zio` imports throughout: `thicket.zio` shadows the real `zio` package inside this file.
  */
private def onUi(f: () => Unit): Unit = UiThread.run(f)

private def ownFiber[R, E, A](
  rt:          UiRuntime[R],
  fiber:       Fiber.Runtime[E, A]
)(using owner: Owner
): Unit =
  owner.own(new Disposable {
    def dispose(): Unit = rt.interruptLater(fiber)
  })

extension [R, E, A](effect: ZIO[R, E, A]) {

  /** Runs `effect` and exposes its progress as state.
    *
    * `Loading` until it completes, then `Done` or `Failed`. Because that is an exhaustive enum, a screen cannot forget
    * to render the loading or error case — the single most common way real UIs end up blank.
    */
  def asSignal(using
    rt:    UiRuntime[R],
    owner: Owner
  ): Signal[RemoteData[E, A]] = {
    val state = Var[RemoteData[E, A]](RemoteData.Loading)
    val fiber = rt.fork(
      effect.foldCause(
        cause => cause.failureOption.fold(())(e => onUi(() => state.set(RemoteData.Failed(e)))),
        a => onUi(() => state.set(RemoteData.Done(a)))
      )
    )
    ownFiber(rt, fiber)
    state
  }

  /** Runs `effect` for its side effects, owned by the enclosing component.
    *
    * Failures go through the ambient [[ErrorPresenter]], so an unhandled typed error is a *compile* error at the call
    * site rather than a silent swallow.
    */
  def launch(using
    rt:      UiRuntime[R],
    owner:   Owner,
    present: ErrorPresenter[E]
  ): Unit = {
    val fiber = rt.fork(
      effect.foldCause(
        cause => cause.failureOption.fold(())(e => onUi(() => present.show(e))),
        _ => ()
      )
    )
    ownFiber(rt, fiber)
  }

}

extension [R, E, A](stream: ZStream[R, E, A]) {

  /** Mirrors a stream into a signal, starting at `initial`.
    *
    * The consuming fibre belongs to the enclosing component, so navigating away closes the subscription — the socket,
    * the database listener, whatever it is.
    */
  def asSignal(
    initial: A
  )(using
    rt:    UiRuntime[R],
    owner: Owner
  ): Signal[A] = {
    val state = Var(initial)
    val fiber = rt.fork(stream.runForeach(a => ZIO.succeed(onUi(() => state.set(a)))).ignore)
    ownFiber(rt, fiber)
    state
  }

  /** As [[asSignal]], but surfacing the stream's failure the way `ZIO.asSignal` does. */
  def asRemoteSignal(using
    rt:    UiRuntime[R],
    owner: Owner
  ): Signal[RemoteData[E, A]] = {
    val state = Var[RemoteData[E, A]](RemoteData.Loading)
    val fiber = rt.fork(
      stream
        .runForeach(a => ZIO.succeed(onUi(() => state.set(RemoteData.Done(a)))))
        .foldCause(
          cause => cause.failureOption.fold(())(e => onUi(() => state.set(RemoteData.Failed(e)))),
          _ => ()
        )
    )
    ownFiber(rt, fiber)
    state
  }

}

extension [A](ref: SubscriptionRef[A]) {

  /** A `SubscriptionRef` is the natural shared-state primitive for an app: any fibre writes it, and the UI observes the
    * changes.
    */
  def asSignal(using
    rt:    UiRuntime[Any],
    owner: Owner
  ): Signal[A] = {
    val initial = Unsafe.unsafe(implicit u => rt.runtime.unsafe.run(ref.get).getOrThrow())
    ref.changes.asSignal(initial)
  }

}
