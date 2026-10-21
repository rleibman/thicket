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

package thicket.renderer.android

import _root_.android.app.Activity
import _root_.android.os.{Build, Bundle, Handler, Looper}
import _root_.android.transition.{Slide, TransitionManager}
import _root_.android.view.{Menu, MenuItem, View}
import _root_.android.widget.FrameLayout
import _root_.android.window.{OnBackInvokedCallback, OnBackInvokedDispatcher}
import scala.annotation.nowarn
import thicket.core.{Action, AppRoot, Host, Mounted, Reconciler, Theme}
import thicket.signals.{Owner, Signal, Tracking}

/** The Android host: an `Activity` that mounts an [[AppRoot]] and applies its chrome the platform's way.
  *
  * An app subclasses it and supplies [[createApp]]; everything else a host does is here. It used to be in each example
  * — the todo app's `MainActivity` had it, the gallery's `GalleryActivity` had a thinner copy without back handling or
  * Up — so an app had to copy about 120 lines out of an example (review 2026-10-20, P3-3).
  *
  *   - **Threads.** `Host.install` on the main thread: the graph belongs to it, and `UiThread` posts reach it through
  *     the main `Looper`.
  *   - **Pages.** One child view per `AppRoot.pages` entry, all kept mounted, the top one visible; a push slides in
  *     with the platform's `Slide`. Each page's owner is a child of the Activity's, so when the Activity is destroyed —
  *     on rotation, say — every page's effects stop with it (P2-19).
  *   - **Chrome.** Title, Up and the options menu follow the top screen; Back and Up pop, and Back at the root finishes
  *     the Activity.
  */
abstract class ThicketActivity extends Activity {

  /** The app to show, built under the Activity's owner. `saved` is what [[onSaveInstanceState]] stored, if anything. */
  protected def createApp(saved: Option[Bundle])(using Owner): AppRoot

  /** Installed before anything is mounted. The default follows the user's Android theme entirely. */
  protected def theme: Theme = Theme.platform

  /** Called once the first pages are on screen: a self-test starts here. */
  protected def started(): Unit = ()

  /** The Activity's lifetime: every page, and everything the app made, is owned under it. */
  protected val rootOwner: Owner = Owner()

  // Set once in onCreate; read by everything after.
  protected var root:      AppRoot = scala.compiletime.uninitialized
  protected var renderer:  AndroidRenderer = scala.compiletime.uninitialized
  protected var container: FrameLayout = scala.compiletime.uninitialized

  /** One mounted page per entry id, in stack order. */
  private val livePages = scala.collection.mutable.LinkedHashMap.empty[Long, (View, Mounted[View], Owner)]

  /** The view of the page on top, once there is one. */
  def topPage: Option[View] = livePages.lastOption.map(_._2._1)

  /** How many pages are mounted, for tests. */
  def pageCount: Int = livePages.size

  /** The renderer, for a self-test that mounts something of its own. */
  def renderer0: AndroidRenderer = renderer

  override def onCreate(saved: Bundle): Unit = {
    super.onCreate(saved)
    val main = Handler(Looper.getMainLooper)
    Host.install(f => { val _ = main.post(() => f()) })
    Theme.install(theme)
    renderer = AndroidRenderer(this)
    given Owner = rootOwner
    root = createApp(Option(saved))

    // Not Fragments: `android.app.Fragment` is deprecated and the AndroidX one would be a
    // dependency. A FrameLayout and `android.transition` are platform API, and enough.
    container = FrameLayout(this)
    setContentView(container)
    Signal.effect(syncPages())

    Signal.effect(setTitle(root.title()))
    Signal.effect {
      val up = root.canGoBack()
      Option(getActionBar).foreach(_.setDisplayHomeAsUpEnabled(up))
    }
    // Android builds the menu on its own schedule: say it changed, and it asks.
    Signal.effect {
      currentActions = root.actions()
      invalidateOptionsMenu()
    }
    registerBackHandler()
    started()
  }

  /** Bring the container in line with `AppRoot.pages`; only the top page is visible. */
  private def syncPages()(using Tracking): Unit = {
    val pages = root.pages()
    val live = pages.view.map(_.id).toSet
    // Untracked: mounting reads the page's own signals, and a tracked mount would make them
    // dependencies of this effect, whose next run would then dispose the page's effects.
    Signal.untracked {
      livePages.keys.toSeq.reverse.filterNot(live).foreach { id =>
        livePages.remove(id).foreach {
          (
            view,
            mounted,
            owner
          ) =>
            container.removeView(view)
            mounted.dispose()
            owner.dispose()
        }
      }
      pages.filterNot(p => livePages.contains(p.id)).foreach { p =>
        val pageOwner = Owner.child(using rootOwner)
        val mounted = {
          given Owner = pageOwner
          Reconciler.mount(renderer, p.content)
        }
        livePages(p.id) = (mounted.handle, mounted, pageOwner)
        container.addView(mounted.handle)
      }
      TransitionManager.beginDelayedTransition(container, Slide())
      val top = livePages.lastOption.map(_._1)
      livePages.foreach(
        (
          id,
          entry
        ) => entry._1.setVisibility(if top.contains(id) then View.VISIBLE else View.GONE)
      )
    }
  }

  private var currentActions: Seq[Action] = Nil

  override def onCreateOptionsMenu(menu: Menu): Boolean = {
    menu.clear()
    currentActions.zipWithIndex.foreach {
      (
        a,
        i
      ) =>
        val item = menu.add(Menu.NONE, i, i, a.label)
        item.setEnabled(a.enabled)
        // IF_ROOM, not ALWAYS: Android decides what fits on the bar and what overflows.
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }
    true
  }

  /** Up first: `android.R.id.home` is a platform id and could collide with an action index. */
  override def onOptionsItemSelected(item: MenuItem): Boolean =
    if item.getItemId == _root_.android.R.id.home then {
      val _ = root.back()
      true
    } else
      currentActions.lift(item.getItemId) match {
        case Some(a) => a.onTap(); true
        case None    => super.onOptionsItemSelected(item)
      }

  /** Android 13+'s dispatcher, which also drives the predictive back gesture. */
  private def registerBackHandler(): Unit =
    if Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU then
      getOnBackInvokedDispatcher.registerOnBackInvokedCallback(
        OnBackInvokedDispatcher.PRIORITY_DEFAULT,
        new OnBackInvokedCallback {
          def onBackInvoked(): Unit = if !root.back() then finish()
        }
      )

  /** Pre-Android-13 fallback; the dispatcher above supersedes it where available. */
  @nowarn("cat=deprecation")
  override def onBackPressed(): Unit =
    if !root.back() then super.onBackPressed()

  override def onDestroy(): Unit = {
    rootOwner.dispose()
    super.onDestroy()
  }

}
