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

package thicket.renderer.apple

import scala.collection.mutable
import thicket.renderer.TabSelection
import thicket.signals.ErrorSink

/** `TabView` on Apple (#69): an `NSTabViewController` on AppKit, a `UITabBarController` on UIKit, each inside a view of
  * the shim's (`TabHostView`), with each `Tab` a view the controller's item holds (`TabPage`).
  *
  * The controllers own the order, the selection and which page is on screen, and the self-test asks them. What they do
  * not own is the rule GTK and Android follow when tabs come and go — the user stays on the tab they are looking at,
  * and a changed index is reported through `OnSelect` — so that is applied here, from [[TabSelection]], the same pure
  * functions the other two renderers use. The shim never reports its own or our changes as the user's choice: only a
  * click on the bar reaches `OnSelect` from Swift.
  */
final private[apple] class AppleTabs {

  type Handle = Shim.Handle

  final private class Parts {

    /** Our order of the tabs, the reconciler's; the shim's controller is told the same with each insert. */
    var tabs: Vector[Handle] = Vector.empty

    /** False until the app's selection has been shown, so a tab arriving first is not reported as a move. */
    var started: Boolean = false

    /** The app's selection, kept because `Selected` arrives at create, before any tab exists to show. */
    var wanted: Int = -1

    var onSelect: Option[Int => Unit] = None

  }

  private val views = mutable.Map.empty[Handle, Parts]
  private val owner = mutable.Map.empty[Handle, Handle] // tab -> its TabView

  def register(view: Handle): Unit = views(view) = Parts()

  def isTabView(h: Handle): Boolean = views.contains(h)
  def isTab(h:     Handle): Boolean = owner.contains(h)

  /** Entries held, for the renderer's leak count. */
  def entries: Int = views.size + owner.size

  /** The selection as the platform's controller has it. */
  private def shown(view: Handle): Int = Shim.sui_get_selected(view)

  def select(
    view:  Handle,
    index: Int
  ): Unit = {
    val p = views(view)
    p.wanted = index
    // -1, or past the end: keep showing what is shown. The shim refuses those too.
    if p.started then { if index >= 0 && index < p.tabs.length then Shim.sui_set_selected(view, index) }
    else start(view, p)
  }

  def onSelect(
    view: Handle,
    f:    Int => Unit
  ): Unit = views(view).onSelect = Some(f)

  /** The first selection: the app's once its tab exists, the first for -1. */
  private def start(
    view: Handle,
    p:    Parts
  ): Unit =
    TabSelection.initial(p.wanted, p.tabs.length).foreach { i =>
      Shim.sui_set_selected(view, i)
      p.started = true
    }

  /** After tabs came or went: keep the user on the tab that was on screen and, if its index moved, tell the app. */
  private def settle(
    view:     Handle,
    p:        Parts,
    visible:  Option[Handle],
    previous: Int
  ): Unit =
    if !p.started then start(view, p)
    else
      TabSelection.afterChange(p.tabs, visible, previous) match {
        case (-1, _)     => p.started = false
        case (i, report) =>
          Shim.sui_set_selected(view, i)
          if report then p.onSelect.foreach(f => ErrorSink.catching(())(f(i)))
      }

  def insertAfter(
    view:  Handle,
    tab:   Handle,
    after: Option[Handle]
  ): Unit = {
    val p = views(view)
    val previous = shown(view)
    val visible = if p.started then p.tabs.lift(previous) else None
    val current = p.tabs.filterNot(_ == tab)
    val at = after.map(a => current.indexOf(a) + 1).getOrElse(0)
    Shim.sui_insert_after(view, tab, after.getOrElse(null.asInstanceOf[Handle]))
    p.tabs = current.take(at) ++ (tab +: current.drop(at))
    owner(tab) = view
    settle(view, p, visible, previous)
  }

  def remove(
    view: Handle,
    tab:  Handle
  ): Unit =
    views.get(view).foreach { p =>
      val previous = shown(view)
      val visible = if p.started then p.tabs.lift(previous).filterNot(_ == tab) else None
      Shim.sui_remove_child(view, tab)
      p.tabs = p.tabs.filterNot(_ == tab)
      val _ = owner.remove(tab)
      settle(view, p, visible, previous)
    }

  /** Forget a TabView or a Tab; a Tab still in a TabView is taken out of it first, through the same rule. */
  def destroy(h: Handle): Unit = {
    owner.get(h).foreach(view => remove(view, h))
    val _ = views.remove(h)
  }

}
