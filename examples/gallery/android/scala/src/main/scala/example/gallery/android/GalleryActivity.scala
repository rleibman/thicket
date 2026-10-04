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

package example.gallery.android

import _root_.android.app.Activity
import _root_.android.os.Bundle
import _root_.android.view.{Menu, MenuItem}
import example.gallery.Gallery
import thicket.core.{Action, AppRoot, Reconciler}
import thicket.renderer.android.AndroidRenderer
import thicket.signals.{Owner, Signal, ThreadGuard}

/** The gallery on Android — the same screen the GTK, macOS and iOS hosts mount.
  *
  * Deliberately plainer than `example.android.MainActivity`: no branding, no back-stack
  * restore, no insets handling. Those belong to an *app*, and the gallery is a conformance
  * surface. What it keeps is the part that matters here — chrome applied natively from
  * `AppRoot`, so the toolbar actions and title come from the same place on every platform.
  *
  * The theme is left entirely to the platform, on purpose: a branded accent would hide
  * whether each widget is picking up Android's own tokens, which is the one thing a
  * cross-platform catalogue is for.
  */
class GalleryActivity extends Activity {

  private val owner = Owner()
  private var app: AppRoot = null

  override def onCreate(saved: Bundle): Unit = {
    super.onCreate(saved)

    // Android's main thread owns the signal graph; a stray write from elsewhere should fail
    // loudly rather than corrupt it (docs/05 A-06).
    ThreadGuard.install(ThreadGuard.owningThread)

    val renderer = AndroidRenderer(this)
    given Owner  = owner

    app = Gallery.app()
    val mounted = Reconciler.mount(renderer, app.element)
    setContentView(mounted.handle)

    Signal.effect(setTitle(app.title()))
    Signal.effect {
      val up = app.canGoBack()
      Option(getActionBar).foreach(_.setDisplayHomeAsUpEnabled(up))
    }
    Signal.effect {
      currentActions = app.actions()
      invalidateOptionsMenu()
    }
  }

  /** The actions the current screen declares, read by [[onCreateOptionsMenu]].
    *
    * A field because Android drives menu construction on its own schedule: the app says
    * "the menu changed" and the platform asks for it back when it is ready.
    */
  private var currentActions: Seq[Action] = Nil

  override def onCreateOptionsMenu(menu: Menu): Boolean = {
    menu.clear()
    currentActions.zipWithIndex.foreach { (a, i) =>
      val item = menu.add(Menu.NONE, i, i, a.label)
      item.setEnabled(a.enabled)
      // IF_ROOM, not ALWAYS: Android decides whether an action fits on the bar or belongs
      // in the overflow, and overriding that is how a toolbar ends up clipped on a phone.
      item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }
    true
  }

  override def onOptionsItemSelected(item: MenuItem): Boolean =
    currentActions.lift(item.getItemId) match {
      case Some(a) => a.onTap(); true
      case None    => super.onOptionsItemSelected(item)
    }

  override def onDestroy(): Unit = {
    owner.dispose()
    super.onDestroy()
  }

}
