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

package thicket.renderer.gtk

import scala.scalanative.unsafe.*
import thicket.core.{AppRoot, Reconciler}
import thicket.signals.{Owner, Signal, ThreadGuard, Tracking}
import sn.gnome.gtk4.internal.*
import sn.gnome.gobject.internal.*
import sn.gnome.gio.internal.*
import sn.gnome.glib.internal.{gchar, gpointer}
import sn.gnome.adwaita.internal.*

/** Hosts an [[Element]] tree in a GTK application window.
  *
  * This is the whole app entry point for a GTK target: `GtkApp.run("id", "Title")(ui)`.
  */
object GtkApp {

  // A CFuncPtr cannot close over local state, so the mount parameters live here.
  private var appId: String                   = "dev.thicket.app"
  private var windowSize: (Int, Int)          = (420, 260)

  /** The application window, once it exists.
    *
    * A modal dialog needs a parent to be modal *to*; GTK will show one without a parent but
    * it is then a free-floating window rather than a sheet over the app.
    */
  private var mainWindow: Ptr[GtkWindow] = null

  /** Callback ids for the header-bar action buttons, so rebuilding the bar releases them
    * rather than leaking one handle-table entry per navigation.
    */
  private val actionIds = scala.collection.mutable.Map.empty[Ptr[GtkWidget], Long]
  private[gtk] def window: Ptr[GtkWindow] = mainWindow

  /** The header bar, once the window exists.
    *
    * Tests asking "what chrome does this screen have" must look here rather than at the
    * whole window. With the stack kept alive, a walk of the window also finds the dormant
    * pages beneath the top one, so a label belonging to a screen you navigated away from
    * would answer for the header bar. That made a real check pass for the wrong reason.
    */
  private var headerBar: Ptr[GtkWidget] = null

  def headerHandle: Ptr[GtkWidget] = headerBar

  /** The whole window, chrome included — the header bar is not part of `rootHandle`.
    *
    * Demos and self-tests use this to check that toolbar actions really are native widgets
    * in the platform's own chrome, rather than trusting that they were asked for.
    */
  def windowHandle: Ptr[GtkWidget] = mainWindow.asInstanceOf[Ptr[GtkWidget]]

  /** Click a header-bar action by label. For self-tests: a real `clicked` emission on the
    * real button, not a call to the handler behind it.
    */
  def clickHeaderAction(label: String): Boolean =
    actionIds.keys
      .find(b => GtkInspect.labelTexts(b).contains(label) || GtkInspect.allTexts(b).contains(label))
      .exists { b =>
        Zone {
          g_signal_emit_by_name(b.asInstanceOf[gpointer], toCString("clicked").asInstanceOf[Ptr[gchar]])
        }
        true
      }
  /** Activate a context-menu item by label, inside the given popover.
    *
    * Emits `clicked` on the real `GtkButton` the renderer built, so the item's own closure
    * runs through the handle table exactly as a user's click would — rather than the test
    * reaching past the renderer and calling the callback directly, which would prove
    * nothing about the wiring.
    */
  def clickMenuItem(popover: Ptr[GtkWidget], label: String): Boolean =
    GtkInspect
      .findAll(popover)(w => GtkInspect.allTexts(w).contains(label) && isButton(w))
      .headOption
      .exists { b =>
        Zone {
          g_signal_emit_by_name(b.asInstanceOf[gpointer], toCString("clicked").asInstanceOf[Ptr[gchar]])
        }
        true
      }

  private def isButton(w: Ptr[GtkWidget]): Boolean =
    g_type_check_instance_is_a(
      w.asInstanceOf[Ptr[GTypeInstance]],
      gtk_button_get_type()
    ).asInstanceOf[CInt] != 0

  private var build: Owner ?=> AppRoot        =
    (_: Owner) ?=> throw IllegalStateException("GtkApp.run was not given a UI")

  // A CFuncPtr cannot close over local state, so the back affordance lives here.
  private var backHandler: () => Boolean = () => false
  private val rootOwner: Owner                = Owner()

  /** The mounted root widget, available once the window has been built. Demos and tests
    * use it with [[GtkInspect]] to read the tree back out of GTK.
    *
    * With a navigation container this is the **top** page's content, which is what a test
    * asking "what is on screen" means. The pages below it stay mounted and inspectable
    * through [[pageHandles]].
    */
  var rootHandle: Ptr[GtkWidget] = null

  /** The `AdwNavigationView`, once the window exists. */
  private var navView: Ptr[AdwNavigationView] = null

  /** One mounted page per entry id, bottom first. Keyed on the id because that is what
    * `AppRoot.pages` promises is stable; keyed on the route it would conflate two visits.
    */
  private val livePages =
    scala.collection.mutable.LinkedHashMap.empty[Long, (Ptr[AdwNavigationPage], thicket.core.Mounted[Ptr[GtkWidget]], Owner)]

  /** Every mounted page's content, bottom first. The point of a native container is that
    * these stay alive, so a test can prove the screen below a push kept its state.
    */
  def pageHandles: Seq[Ptr[GtkWidget]] = livePages.valuesIterator.map(_._2.handle).toSeq

  /** True while we are pushing or popping to match `Nav`.
    *
    * `adw_navigation_view_pop` emits `popped` just as a user's back gesture does, so without
    * this the handler would call `back()` again and pop twice. The guard is what keeps one
    * user gesture equal to one `Nav.pop()`.
    */
  private var syncingFromNav = false

  /** Runs `f` on the GTK main loop. Safe from any thread. */
  def postToUi(f: () => Unit): Unit = {
    val id = Handles.register(f)
    val _  = sn.gnome.glib.internal.g_idle_add(
      sn.gnome.glib.internal.GSourceFunc(Handles.idle),
      Handles.idToPointer(id)
    )
  }

  private val onActivate: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    CFuncPtr2.fromScalaFunction { (app: Ptr[Byte], _: Ptr[Byte]) =>
      GcState.guarded {
        val window = gtk_application_window_new(app.asInstanceOf[Ptr[GtkApplication]])
        val w      = window.asInstanceOf[Ptr[GtkWindow]]
        mainWindow = w
        gtk_window_set_default_size(w, windowSize._1, windowSize._2)
        val header = gtk_header_bar_new()
        headerBar = header
        gtk_window_set_titlebar(w, header)

        val renderer = GtkRenderer()
        given Owner  = rootOwner
        val root     = build
        backHandler = () => root.back()

        // Native chrome, applied from AppRoot rather than drawn into the element tree.
        val back = gtk_button_new_from_icon_name(Zone(toCString("go-previous-symbolic")))
        gtk_header_bar_pack_start(header.asInstanceOf[Ptr[GtkHeaderBar]], back)
        Zone {
          val _ = g_signal_connect_data(
            back.asInstanceOf[gpointer],
            toCString("clicked").asInstanceOf[Ptr[gchar]],
            GCallback.fromPtr(CFuncPtr.toPtr(onBack)),
            Handles.idToPointer(0L),
            null.asInstanceOf[GClosureNotify],
            GConnectFlags.define(0)
          )
        }
        Signal.effect(gtk_widget_set_visible(back, gboolTrue(root.canGoBack())))
        Signal.effect(Zone(gtk_window_set_title(w, toCString(root.title()))))

        // Toolbar actions, packed at the trailing edge of the header bar. `AppRoot.actions`
        // has been in the contract since navigation landed and no host had ever read it —
        // an API the docs promised and nothing honoured.
        //
        // Rebuilt wholesale when the signal changes rather than diffed: a screen has a
        // handful of actions, they change only on navigation, and a keyed diff here would
        // be machinery guarding against a cost that does not exist.
        val actionButtons = scala.collection.mutable.ArrayBuffer.empty[Ptr[GtkWidget]]
        Signal.effect {
          val hb = header.asInstanceOf[Ptr[GtkHeaderBar]]
          actionButtons.foreach { b =>
            Handles.release(actionIds.getOrElse(b, 0L))
            val _ = actionIds.remove(b)
            gtk_header_bar_remove(hb, b)
          }
          actionButtons.clear()

          // Reversed: pack_end puts each new button closest to the window controls, so
          // packing in order would show them right-to-left.
          root.actions().reverse.foreach { a =>
            val button = Zone(gtk_button_new_with_label(toCString(a.label)))
            gtk_widget_set_sensitive(button, gboolTrue(a.enabled))
            val id = Handles.register(() => a.onTap())
            actionIds(button) = id
            Zone {
              val _ = g_signal_connect_data(
                button.asInstanceOf[gpointer],
                toCString("clicked").asInstanceOf[Ptr[gchar]],
                GCallback.fromPtr(Handles.clickedPtr),
                Handles.idToPointer(id),
                null.asInstanceOf[GClosureNotify],
                GConnectFlags.define(0)
              )
            }
            gtk_header_bar_pack_end(hb, button)
            actionButtons += button
          }
        }

        // Before any adw_* constructor, or it segfaults with "Unhandled signal 11" and no
        // hint that initialisation is what was missing.
        adw_init()

        val nav = adw_navigation_view_new()
        navView = nav.asInstanceOf[Ptr[AdwNavigationView]]
        Zone {
          val _ = g_signal_connect_data(
            navView.asInstanceOf[gpointer],
            toCString("popped").asInstanceOf[Ptr[gchar]],
            GCallback.fromPtr(CFuncPtr.toPtr(onPopped)),
            Handles.idToPointer(0L),
            null.asInstanceOf[GClosureNotify],
            GConnectFlags.define(0)
          )
        }
        gtk_window_set_child(w, nav)

        // `pages` rather than `element`: one AdwNavigationPage per entry, mounted once and
        // left mounted, which is what preserves the scroll position and in-flight requests
        // of the screen below a push.
        Signal.effect(syncPages(renderer, root))

        gtk_window_present(w)
      }
    }

  /** Bring the container in line with `AppRoot.pages`.
    *
    * Pops first, then pushes, so that replacing the top of the stack does not briefly show
    * the new page beneath the old one. Pages already mounted are left completely alone —
    * neither rebuilt nor re-pushed — which is the whole reason `NavPage.content` is stable
    * per id.
    */
  private def syncPages(
    renderer:     GtkRenderer,
    root:         AppRoot
  )(using Tracking
  ): Unit = {
    // Tracked: the stack is what this effect follows.
    val pages = root.pages()
    val live  = pages.view.map(_.id).toSet

    // Everything else is untracked, and that is not an optimisation. Mounting a page reads
    // whatever signals its content reads, so a tracked mount makes every one of them a
    // dependency of *this* effect — and the next re-run then disposes the page's own inner
    // effects, because they were created as this effect's children. The symptom was a list
    // that lost a row: five visible items, four rendered. `Reconciler` carries the same
    // warning for regions; it applies just as much to a host.
    syncingFromNav = true
    try Signal.untracked {
      // Drop what left the stack, from the top down.
      livePages.keys.toSeq.reverse.filterNot(live).foreach { id =>
        livePages.remove(id).foreach { (_, _, owner) =>
          // The pop owns the teardown. An AdwNavigationPage holds the only reference to its
          // child, so popping it releases the page and destroys the subtree underneath —
          // calling `mounted.dispose()` here as well is a double destroy. GTK does not
          // complain at the time; it corrupts quietly and kills an unrelated widget several
          // operations later. Exactly the lesson `Sheet` taught, where detaching a window's
          // child *was* releasing it.
          //
          // Disposing the owner is still ours: it stops the effects that were driving the
          // widgets the pop just took away.
          adw_navigation_view_pop(navView)
          owner.dispose()
        }
      }

      pages.filterNot(p => livePages.contains(p.id)).foreach { p =>
        val pageOwner = Owner()
        val mounted = {
          given Owner = pageOwner
          Reconciler.mount(renderer, p.content)
        }
        val page = Zone(adw_navigation_page_new(mounted.handle, toCString(p.screen.title)))
        livePages(p.id) = (page, mounted, pageOwner)
        adw_navigation_view_push(navView, page)
      }
    }
    finally syncingFromNav = false

    // What "on screen" means, for tests and demos.
    rootHandle = livePages.lastOption.map(_._2._2.handle).getOrElse(null)
  }

  /** The title of the page the **container** is actually showing.
    *
    * Deliberately asked of `AdwNavigationView` rather than of our own `livePages` map. A
    * check against our bookkeeping cannot see the container and us disagreeing, which is
    * the entire failure mode worth testing here — and the first version of that check did
    * exactly that, so a deliberately broken sync passed it.
    */
  def visiblePageTitle: String =
    if navView == null then ""
    else {
      val page = adw_navigation_view_get_visible_page(navView)
      if page == null then ""
      else fromCString(adw_navigation_page_get_title(page).asInstanceOf[CString])
    }

  /** Pop the way a user does — through the container, not through `Nav`.
    *
    * For self-tests. `AdwNavigationView` emits `popped` from this exactly as it does for a
    * swipe or its own back button, so the two-way sync is what is under test: the container
    * moved first and `Nav` has to follow. Calling `nav.pop()` instead would prove nothing
    * about the wiring, which is the half that can silently rot.
    */
  def popByGesture(): Boolean =
    navView != null && {
      adw_navigation_view_pop(navView)
      true
    }

  /** A user's back gesture or the container's own back button.
    *
    * Adw has already removed its top page by the time this runs, so this forgets our record
    * of that page *before* telling `Nav`. Otherwise the sync that `Nav` triggers sees a page
    * it still believes is mounted and pops a **second** one.
    *
    * A two-deep stack hides that bug, because `AdwNavigationView` will not pop its root — so
    * the first version of this looked correct and the self-test agreed. Disabling the guard
    * below changed nothing observable, which is how the extra pop came to light: a check
    * that cannot fail is not evidence. The stack in the self-test is three deep now.
    */
  private val onPopped: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    CFuncPtr2.fromScalaFunction { (_: Ptr[Byte], _: Ptr[Byte]) =>
      GcState.guarded {
        if !syncingFromNav then {
          // The container moved first; match it, then let Nav catch up.
          livePages.lastOption.foreach { (id, entry) =>
            val _ = livePages.remove(id)
            entry._3.dispose()
          }
          val _ = backHandler()
        }
      }
    }

  private val onBack: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    CFuncPtr2.fromScalaFunction { (_: Ptr[Byte], _: Ptr[Byte]) =>
      GcState.guarded { val _ = backHandler() }
    }

  private def gboolTrue(b: Boolean): sn.gnome.glib.internal.gboolean =
    (if b then 1 else 0).asInstanceOf[sn.gnome.glib.internal.gboolean]

  /** The window title comes from `AppRoot.title`, so it follows the top screen. */
  def run(id: String, width: Int = 420, height: Int = 260)(
      ui: Owner ?=> AppRoot
  ): Int = {
    // The signal graph belongs to whichever thread reaches it first; on GTK that is the
    // main loop's thread. Installing the guard makes a stray cross-thread write fail loudly
    // instead of corrupting the graph (docs/05 A-06).
    ThreadGuard.install(ThreadGuard.owningThread)

    appId = id
    windowSize = (width, height)
    build = ui

    val app = Zone(
      gtk_application_new(toCString(appId), GApplicationFlags.G_APPLICATION_DEFAULT_FLAGS)
    )
    Zone {
      val _ = g_signal_connect_data(
        app.asInstanceOf[gpointer],
        toCString("activate").asInstanceOf[Ptr[gchar]],
        GCallback.fromPtr(CFuncPtr.toPtr(onActivate)),
        Handles.idToPointer(0L),
        null.asInstanceOf[GClosureNotify],
        GConnectFlags.define(0)
      )
    }

    // From here the GTK main loop owns this thread, so Scala must not look Managed to the
    // GC while it is parked in poll() (S3).
    GcState.releaseMainThread()
    g_application_run(app.asInstanceOf[Ptr[GApplication]], 0, null)
  }
}
