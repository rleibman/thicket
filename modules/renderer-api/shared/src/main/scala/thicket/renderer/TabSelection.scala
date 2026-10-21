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

package thicket.renderer

/** The rules a renderer follows for a `TabView`'s selection while its tabs come and go, as pure functions.
  *
  * Every native tab container keeps the user on the tab they are looking at when others are inserted or removed — and
  * when that changes the tab's *index*, the app, which holds the selection as an index, has to be told. GTK and Android
  * each implemented this inline against their own widgets, and only a display could test either (review 2026-10-20,
  * P3-4). Here it is once, with unit tests; the renderers do the widget calls.
  */
object TabSelection {

  /** Where the user is after the list of tabs changed, and whether the app must be told.
    *
    *   - The tab that was visible is still there: stay on it. Report its index if it moved.
    *   - It was removed: show the nearest remaining one — the same index, or the last if that is past the end — and
    *     report it, since the app's index now names a different tab.
    *   - There are no tabs left: nothing is selected, and there is nothing to report.
    *
    * @param tabs
    *   the tabs as they are now
    * @param visible
    *   the tab that was on screen before the change, if any
    * @param previous
    *   its index before the change, or -1
    * @return
    *   the index to show (-1 for none) and whether to report it to the app
    */
  def afterChange[T](
    tabs:     Seq[T],
    visible:  Option[T],
    previous: Int
  ): (Int, Boolean) =
    visible.map(tabs.indexOf).filter(_ >= 0) match {
      case Some(i)               => (i, i != previous)
      case None if tabs.nonEmpty => (previous.max(0).min(tabs.length - 1), true)
      case None                  => (-1, false)
    }

  /** The index to report when the shown tab is at `now` and the app was last told `known`: `now` if it differs, and
    * nothing before the app's own selection has been shown (`known` empty) — a 0 reported while tabs are still arriving
    * would overwrite what the app asked for.
    */
  def moved(
    known: Option[Int],
    now:   Int
  ): Option[Int] =
    known.filter(_ => now >= 0).filterNot(_ == now).map(_ => now)

  /** Which tab to show when the first tabs arrive: the app's choice once it exists; the first when the app chose none
    * (-1), as every native container shows one; nothing yet while the wanted tab has not arrived.
    */
  def initial(
    wanted: Int,
    count:  Int
  ): Option[Int] =
    if wanted >= 0 && wanted < count then Some(wanted)
    else if wanted < 0 && count > 0 then Some(0)
    else None

}
