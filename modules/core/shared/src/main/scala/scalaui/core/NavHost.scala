package scalaui.core

import scalaui.core.dsl.*
import scalaui.signals.Signal

/** Renders the top of a [[Nav]] stack.
  *
  * v0 mounts **only the top screen**: pushing unmounts the one below, popping rebuilds it.
  * Native navigation containers (`UINavigationController`, a fragment back stack,
  * `GtkStack`) keep the whole stack alive, which is what preserves scroll position and
  * in-flight state across a push. Moving to those containers is a renderer change behind
  * this same API — the app-facing shape does not move.
  */
final class NavHost[R] private (nav: Nav[R], screenFor: R => Screen) extends AppRoot {

  /** The element to mount. Contains the current screen's content and nothing else: title
    * and actions are platform chrome, applied by the host from [[screen]].
    */
  val element: Element =
    // Keyed on the entry id, so a push always mounts a fresh screen and a pop cannot
    // resurrect the outgoing one.
    Column()(
      ForEach(nav.stack.map(_.take(1)), key = (e: Entry[R]) => e.id) { entry =>
        // Safe untracked read: the route is fixed for the lifetime of an entry id, so the
        // content never needs to react to it.
        screenFor(entry.now.route).content
      }
    )

  /** The current screen, for the host to apply chrome from. */
  val screen: Signal[Screen] = nav.stack.map(es => screenFor(es.head.route))

  val title: Signal[String]        = screen.map(_.title)
  val actions: Signal[Seq[Action]] = screen.map(_.actions)
  val canGoBack: Signal[Boolean]   = nav.canGoBack

  /** Platform back: pop, or report that there is nowhere to go. */
  def back(): Boolean = nav.pop()

  /** The stack itself, for hosts that persist it and for tests that drive it. */
  def navigator: Nav[R] = nav

  def push(route: R): Unit = nav.push(route)
}

object NavHost {
  /** `screenFor` is a plain function from the route ADT, so the compiler checks that every
    * route has a screen. There is no route registry to forget to update.
    */
  def apply[R](nav: Nav[R])(screenFor: R => Screen): NavHost[R] =
    new NavHost(nav, screenFor)
}
