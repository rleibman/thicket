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

package thicket.core

import thicket.core.dsl.*
import thicket.signals.Signal

/** Renders the top of a [[Nav]] stack.
  *
  * v0 mounts **only the top screen**: pushing unmounts the one below, popping rebuilds it. Native navigation containers
  * (`UINavigationController`, a fragment back stack, `GtkStack`) keep the whole stack alive, which is what preserves
  * scroll position and in-flight state across a push. Moving to those containers is a renderer change behind this same
  * API — the app-facing shape does not move.
  */
final class NavHost[R] private (
  nav:       Nav[R],
  screenFor: R => Screen
) extends AppRoot {

  /** The element to mount. Contains the current screen's content and nothing else: title and actions are platform
    * chrome, applied by the host from [[screen]].
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

  /** Built content, keyed on entry id.
    *
    * `pages` is a signal, so it recomputes on every push and pop. Rebuilding a screen's element each time would defeat
    * the exercise twice over: the host could no longer tell "same page", and each rebuild would create a fresh set of
    * signals and effects for a screen already on display. So content is built once per id and pruned when the id leaves
    * the stack.
    */
  private val built = scala.collection.mutable.LinkedHashMap.empty[Long, NavPage]

  /** The live stack, bottom first — see `AppRoot.pages`. */
  val pages: Signal[Seq[NavPage]] =
    nav.stack.map { entries =>
      val live = entries.view.map(_.id).toSet
      built.filterInPlace(
        (
          id,
          _
        ) => live(id)
      )
      // Reversed: `Nav` keeps the top first, because that is what an app asks about, while a
      // navigation container stacks from the root upwards.
      entries.reverse.map { e =>
        built.getOrElseUpdate(
          e.id, {
            val s = screenFor(e.route)
            NavPage(e.id, s, s.content)
          }
        )
      }
    }

  /** The current screen, for the host to apply chrome from. */
  val screen: Signal[Screen] = nav.stack.map(es => screenFor(es.head.route))

  val title:     Signal[String] = screen.map(_.title)
  val actions:   Signal[Seq[Action]] = screen.map(_.actions)
  val canGoBack: Signal[Boolean] = nav.canGoBack

  /** Platform back: pop, or report that there is nowhere to go. */
  def back(): Boolean = nav.pop()

  /** The stack itself, for hosts that persist it and for tests that drive it. */
  def navigator: Nav[R] = nav

  def push(route: R): Unit = nav.push(route)

}

object NavHost {

  /** `screenFor` is a plain function from the route ADT, so the compiler checks that every route has a screen. There is
    * no route registry to forget to update.
    */
  def apply[R](nav: Nav[R])(screenFor: R => Screen): NavHost[R] = new NavHost(nav, screenFor)

}
