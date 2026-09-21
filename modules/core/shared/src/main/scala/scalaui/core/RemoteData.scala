package scalaui.core

import scalaui.core.dsl.*
import scalaui.signals.Signal

/** The state of a value that is being fetched: in flight, failed, or here.
  *
  * Every effect reaching the UI arrives as one of these three cases, so "I forgot to handle
  * loading" and "I forgot to handle the error" become non-exhaustive-match errors rather
  * than a blank screen. Flutter's `FutureBuilder` and Compose's `collectAsState` both let
  * you skip those branches; this does not.
  *
  * The name is Elm's, from `krisajenkins/remotedata`, where this ADT was popularised. It is
  * deliberately *not* called `RemoteData`: `cats.effect.RemoteData` is a well-known typeclass for
  * asynchronous effects, and this project plans a cats-effect bridge (docs/07 §7.13), so
  * that name would collide with something every cats user already knows.
  *
  * Nothing off the shelf has this shape. `cats.effect.kernel.Outcome[F, E, A]` is the
  * closest — three cases — but it describes a fibre that has *finished*
  * (`Succeeded | Errored | Canceled`): there is no in-flight case, `Canceled` is not
  * `Loading`, and `Succeeded` holds an `F[A]` rather than an `A`, so a view would have to
  * run an effect mid-render to read it. `Option[Either[E, A]]` is isomorphic but unreadable
  * at a match site. So this stays a local twenty-line ADT, which also keeps it free of a
  * third-party dependency in the critical path (docs/05 S-02) and cross-compiling to all
  * three backends.
  */
enum RemoteData[+E, +A] {
  case Loading
  case Failed(error: E)
  case Done(value: A)
}

object RemoteData {

  extension [E, A](self: RemoteData[E, A]) {
    def toOption: Option[A] = self match {
      case Done(a) => Some(a)
      case _       => None
    }

    def isLoading: Boolean = self match {
      case Loading => true
      case _       => false
    }

    def map[B](f: A => B): RemoteData[E, B] = self match {
      case Done(a)   => Done(f(a))
      case Failed(e) => Failed(e)
      case Loading   => Loading
    }
  }

  /** Render a signal of remote state, one branch per case.
    *
    * Takes a total function, so the compiler checks exhaustiveness at the call site — which
    * is the whole reason for preferring this over an `if (loading)` chain.
    */
  def apply[E, A](signal: Signal[RemoteData[E, A]])(view: RemoteData[E, A] => Element): Element =
    Switch(signal)(view)
}
