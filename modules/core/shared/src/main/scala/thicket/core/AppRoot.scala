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

import thicket.signals.Signal

/** What a platform host needs from an app, beyond the element tree.
  *
  * Titles, back affordances and toolbar actions are *chrome*: a GTK header bar, an Android action bar, a
  * `UINavigationItem`. Putting them in the element tree would mean the reconciler drawing an imitation of each
  * platform's bar, which is exactly the trade this project exists to avoid. So they travel beside the tree and each
  * host applies them natively.
  */
trait AppRoot {

  /** The element to mount, for a host with **no** native navigation container: the top screen only.
    *
    * A host that implements [[pages]] instead must not mount this as well — they are two renderings of the same stack.
    */
  def element:   Element
  def title:     Signal[String]
  def canGoBack: Signal[Boolean]
  def actions:   Signal[Seq[Action]]

  /** The live stack, **bottom first**, for a host that has a native navigation container.
    *
    * Navigation turned out to be the fourth thing §12.2a rejected as a widget kind, and for the usual reason: no
    * toolkit models it as a view you place beside others. `UINavigationController` is a view *controller* that owns the
    * screen, `FragmentManager` is a manager rather than a view, and AppKit has no push idiom at all — a Mac app uses a
    * sidebar or separate windows. Only `AdwNavigationView` is a widget, and one out of four is not a catalogue entry.
    *
    * So it lives here instead of in the element tree, and each host does what is native to it: push and pop real pages
    * where the platform has them, mount [[element]] and nothing else where it does not. An app sees neither choice.
    */
  def pages: Signal[Seq[NavPage]]

  /** Handle a platform back gesture — Android's Back button, a header-bar arrow, an iOS swipe. Returns false when there
    * is nowhere to go, which tells the host to defer to the platform (finish the activity, close the window).
    */
  def back(): Boolean

}

object AppRoot {

  /** A single-screen app, with no navigation. */
  def apply(
    title0:  String,
    content: Element
  ): AppRoot =
    new AppRoot {
      val element = content
      // One screen, so the "stack" is that screen. A host with a native container can treat
      // every app uniformly rather than special-casing the single-screen case.
      val pages:     Signal[Seq[NavPage]] = Signal.const(Seq(NavPage(0L, Screen(title0, content), content)))
      val title:     Signal[String] = Signal.const(title0)
      val canGoBack: Signal[Boolean] = Signal.const(false)
      val actions:   Signal[Seq[Action]] = Signal.const(Nil)
      def back():    Boolean = false
    }

}
