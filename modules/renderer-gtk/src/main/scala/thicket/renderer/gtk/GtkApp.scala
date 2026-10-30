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
import scala.scalanative.unsigned.*
import thicket.core.{AppRoot, GcState, Host, Reconciler}
import thicket.signals.{Disposable, Owner, Signal, Tracking}
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
  private var appId:      String = "dev.thicket.app"
  private var windowSize: (Int, Int) = (420, 260)

  /** The application window, once it exists.
    *
    * A modal dialog needs a parent to be modal *to*; GTK will show one without a parent but it is then a free-floating
    * window rather than a sheet over the app.
    */
  private var mainWindow: Ptr[GtkWindow] = null

  /** Callback ids for the header-bar action buttons, so rebuilding the bar releases them rather than leaking one
    * handle-table entry per navigation.
    */
  private val actionIds = scala.collection.mutable.Map.empty[Ptr[GtkWidget], Long]
  private[gtk] def window: Ptr[GtkWindow] = mainWindow

  /** The header bar, once the window exists.
    *
    * Tests asking "what chrome does this screen have" must look here rather than at the whole window. With the stack
    * kept alive, a walk of the window also finds the dormant pages beneath the top one, so a label belonging to a
    * screen you navigated away from would answer for the header bar. That made a real check pass for the wrong reason.
    */
  private var headerBar: Ptr[GtkWidget] = null

  def headerHandle: Ptr[GtkWidget] = headerBar

  /** The whole window, chrome included — the header bar is not part of `rootHandle`.
    *
    * Demos and self-tests use this to check that toolbar actions really are native widgets in the platform's own
    * chrome, rather than trusting that they were asked for.
    */
  def windowHandle: Ptr[GtkWidget] = mainWindow.asInstanceOf[Ptr[GtkWidget]]

  /** Click a header-bar action by label. For self-tests: a real `clicked` emission on the real button, not a call to
    * the handler behind it.
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
    * Emits `clicked` on the real `GtkButton` the renderer built, so the item's own closure runs through the handle
    * table exactly as a user's click would — rather than the test reaching past the renderer and calling the callback
    * directly, which would prove nothing about the wiring.
    */
  def clickMenuItem(
    popover: Ptr[GtkWidget],
    label:   String
  ): Boolean =
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

  private var build: Owner ?=> AppRoot =
    (_: Owner) ?=> throw IllegalStateException("GtkApp.run was not given a UI")

  // A CFuncPtr cannot close over local state, so the back affordance lives here.
  private var backHandler: () => Boolean = () => false
  private val rootOwner:   Owner = Owner()

  /** The mounted root widget, available once the window has been built. Demos and tests use it with [[GtkInspect]] to
    * read the tree back out of GTK.
    *
    * With a navigation container this is the **top** page's content, which is what a test asking "what is on screen"
    * means. The pages below it stay mounted and inspectable through [[pageHandles]].
    */
  var rootHandle: Ptr[GtkWidget] = null

  /** The `AdwNavigationView`, once the window exists. */
  private var navView: Ptr[AdwNavigationView] = null

  /** One mounted page per entry id, bottom first. Keyed on the id because that is what `AppRoot.pages` promises is
    * stable; keyed on the route it would conflate two visits.
    */
  private val livePages =
    scala.collection.mutable.LinkedHashMap
      .empty[Long, (Ptr[AdwNavigationPage], thicket.core.Mounted[Ptr[GtkWidget]], Owner)]

  /** Every mounted page's content, bottom first. The point of a native container is that these stay alive, so a test
    * can prove the screen below a push kept its state.
    */
  def pageHandles: Seq[Ptr[GtkWidget]] = livePages.valuesIterator.map(_._2.handle).toSeq

  /** Turns the navigation container's push and pop animations on or off.
    *
    * For tests. A popped page is freed when its animation ends, and the animation advances on the compositor's frame
    * clock — which can stall when the window is not being drawn, so a test waiting for a page to be released can wait
    * forever. Without the animation the pop, and the release, are immediate.
    */
  def animateTransitions(on: Boolean): Unit =
    if navView != null then adw_navigation_view_set_animate_transitions(navView, Handles.gbool(on))

  /** How many pages `AdwNavigationView` itself holds — asked of the container, not of `livePages`, so a test can see
    * the two disagree.
    */
  def containerDepth: Int =
    if navView == null then 0
    else {
      val stack = adw_navigation_view_get_navigation_stack(navView)
      val n = sn.gnome.gio.internal.g_list_model_get_n_items(stack.asInstanceOf[Ptr[sn.gnome.gio.internal.GListModel]])
      sn.gnome.gobject.internal.g_object_unref(stack.asInstanceOf[gpointer])
      n.asInstanceOf[scala.scalanative.unsigned.UInt].toInt
    }

  /** True while we are pushing or popping to match `Nav`.
    *
    * `adw_navigation_view_pop` emits `popped` just as a user's back gesture does, so without this the handler would
    * call `back()` again and pop twice. The guard is what keeps one user gesture equal to one `Nav.pop()`.
    */
  private var syncingFromNav = false

  /** Runs `f` on the GTK main loop. Safe from any thread. */
  def postToUi(f: () => Unit): Unit = {
    val id = Handles.register(f)
    val _ = sn.gnome.glib.internal.g_idle_add(
      sn.gnome.glib.internal.GSourceFunc(Handles.idle),
      Handles.idToPointer(id)
    )
  }

  /** [[postToUi]], after at least `delayMs`. */
  def postToUiAfter(delayMs: Int)(f: () => Unit): Unit = {
    val id = Handles.register(f)
    val _ = sn.gnome.glib.internal.g_timeout_add(
      delayMs.toUInt.asInstanceOf[sn.gnome.glib.internal.guint],
      sn.gnome.glib.internal.GSourceFunc(Handles.idle),
      Handles.idToPointer(id)
    )
  }

  private val onActivate: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    CFuncPtr2.fromScalaFunction {
      (
        app: Ptr[Byte],
        _:   Ptr[Byte]
      ) =>
        GcState.guarded {
          val window = gtk_application_window_new(app.asInstanceOf[Ptr[GtkApplication]])
          val w = window.asInstanceOf[Ptr[GtkWindow]]
          mainWindow = w
          gtk_window_set_default_size(w, windowSize._1, windowSize._2)
          // Shown or not, for the app's lifecycle: mapped, and on map the surface's minimised and suspended states.
          Handles.connect(window, "map", Handles.clickedPtr, Handles.register(() => { watchSurface(); updateShown() }))
          Handles.connect(window, "unmap", Handles.clickedPtr, Handles.register(() => updateShown()))
          val header = gtk_header_bar_new()
          headerBar = header
          gtk_window_set_titlebar(w, header)

          val renderer = GtkRenderer()
          liveRenderer = renderer
          given Owner = rootOwner
          val root = build
          backHandler = () => root.back()

          // Native chrome, applied from AppRoot rather than drawn into the element tree.
          val back = gtk_button_new_from_icon_name(Zone(toCString("go-previous-symbolic")))
          gtk_header_bar_pack_start(header.asInstanceOf[Ptr[GtkHeaderBar]], back)
          Handles.connect(back, "clicked", CFuncPtr.toPtr(onBack), 0L)
          Signal.effect(gtk_widget_set_visible(back, Handles.gbool(root.canGoBack())))
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
              gtk_widget_set_sensitive(button, Handles.gbool(a.enabled))
              val id = Handles.register(() => a.onTap())
              actionIds(button) = id
              Handles.connect(button, "clicked", Handles.clickedPtr, id)
              gtk_header_bar_pack_end(hb, button)
              actionButtons += button
            }
          }

          // Before any adw_* constructor, or it segfaults with "Unhandled signal 11" and no
          // hint that initialisation is what was missing.
          adw_init()

          val nav = adw_navigation_view_new()
          navView = nav.asInstanceOf[Ptr[AdwNavigationView]]
          Handles.connect(navView, "popped", CFuncPtr.toPtr(onPopped), 0L)
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
    * Pops first, then pushes, so that replacing the top of the stack does not briefly show the new page beneath the old
    * one. Pages already mounted are left completely alone — neither rebuilt nor re-pushed — which is the whole reason
    * `NavPage.content` is stable per id.
    */
  private def syncPages(
    renderer: GtkRenderer,
    root:     AppRoot
  )(using Tracking
  ): Unit = {
    // Tracked: the stack is what this effect follows.
    val pages = root.pages()
    val live = pages.view.map(_.id).toSet

    // Everything else is untracked, and that is not an optimisation. Mounting a page reads
    // whatever signals its content reads, so a tracked mount makes every one of them a
    // dependency of *this* effect — and the next re-run then disposes the page's own inner
    // effects, because they were created as this effect's children. The symptom was a list
    // that lost a row: five visible items, four rendered. `Reconciler` carries the same
    // warning for regions; it applies just as much to a host.
    syncingFromNav = true
    try
      Signal.untracked {
        val departing = livePages.keys.toSeq.reverse.filterNot(live)
        // AdwNavigationView will not pop its root page. So when the root is among the pages
        // leaving — `Nav.reset`, `Nav.restore` — popping would leave the old root, frozen,
        // under the new stack, reachable with Back, and `onPopped` would then dispose the
        // wrong owner (review 2026-10-20, P1-9). The whole stack is replaced instead.
        val rootLeaves = livePages.headOption.exists(
          (
            id,
            _
          ) => !live(id)
        )

        // Drop what left the stack, from the top down.
        departing.foreach { id =>
          livePages.remove(id).foreach {
            (
              _,
              _,
              owner
            ) =>
              // The pop (or the replace below) owns the teardown. An AdwNavigationPage holds
              // the only reference to its child, so removing the page releases it and
              // destroys the subtree underneath — calling `mounted.dispose()` here as well is
              // a double destroy. GTK does not complain at the time; it corrupts quietly and
              // kills an unrelated widget several operations later. Exactly the lesson
              // `Sheet` taught, where detaching a window's child *was* releasing it.
              //
              // Disposing the owner is still ours: it stops the effects that were driving the
              // widgets the pop just took away. The widgets' renderer state is released when
              // GTK finalises the page — see `releaseWhenFinalized`.
              if !rootLeaves then adw_navigation_view_pop(navView)
              owner.dispose()
          }
        }

        pages.filterNot(p => livePages.contains(p.id)).foreach { p =>
          // A child of the app's root owner, not a detached root: whatever disposes the app
          // disposes its pages too (review 2026-10-20, P2-19).
          val pageOwner = Owner.child(using rootOwner)
          val mounted = {
            given Owner = pageOwner
            Reconciler.mount(renderer, p.content)
          }
          val page = Zone(adw_navigation_page_new(mounted.handle, toCString(p.screen.title)))
          releaseWhenFinalized(page, mounted)
          livePages(p.id) = (page, mounted, pageOwner)
          if !rootLeaves then adw_navigation_view_push(navView, page)
        }

        if rootLeaves then {
          val stack = pages.flatMap(p => livePages.get(p.id).map(_._1))
          Zone {
            val arr = alloc[Ptr[AdwNavigationPage]](stack.length max 1)
            stack.zipWithIndex.foreach(
              (
                pg,
                i
              ) => arr(i) = pg
            )
            adw_navigation_view_replace(navView, arr, stack.length)
          }
        }
      }
    finally syncingFromNav = false

    // What "on screen" means, for tests and demos.
    rootHandle = livePages.lastOption.map(_._2._2.handle).getOrElse(null)
  }

  /** Lets the renderer release a page's widgets once GTK has finished with the page.
    *
    * A popped page is freed by GTK — the page holds the only reference to its content — and not at the pop but when its
    * transition ends. So the reconciler cannot destroy the content at the pop (that is the double destroy described in
    * `syncPages`), and nothing destroyed it later either: every callback, list binder and per-widget entry the renderer
    * held for that page stayed in its tables for the life of the process (review 2026-10-20, found by the LazyColumn
    * self-test's callback count).
    *
    * So the content gets a reference of its own, the page a weak reference whose notification arrives when the page is
    * finalised — by which time it has let go of the content — and then the content is disposed through the reconciler,
    * which releases the renderer's state and detaches the subtree, and the reference is dropped.
    */
  private def releaseWhenFinalized(
    page:    Ptr[AdwNavigationPage],
    mounted: thicket.core.Mounted[Ptr[GtkWidget]]
  ): Unit = {
    val content = mounted.handle.asInstanceOf[gpointer]
    val _ = g_object_ref(content)
    val id = Handles.register { () =>
      postToUi { () =>
        mounted.dispose()
        g_object_unref(content)
      }
    }
    g_object_weak_ref(
      page.asInstanceOf[Ptr[GObject]],
      GWeakNotify.fromPtr(Handles.weakNotifyPtr),
      Handles.idToPointer(id)
    )
  }

  /** The title of the page the **container** is actually showing.
    *
    * Deliberately asked of `AdwNavigationView` rather than of our own `livePages` map. A check against our bookkeeping
    * cannot see the container and us disagreeing, which is the entire failure mode worth testing here — and the first
    * version of that check did exactly that, so a deliberately broken sync passed it.
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
    * For self-tests. `AdwNavigationView` emits `popped` from this exactly as it does for a swipe or its own back
    * button, so the two-way sync is what is under test: the container moved first and `Nav` has to follow. Calling
    * `nav.pop()` instead would prove nothing about the wiring, which is the half that can silently rot.
    */
  def popByGesture(): Boolean =
    navView != null && {
      adw_navigation_view_pop(navView)
      true
    }

  /** A user's back gesture or the container's own back button.
    *
    * Adw has already removed its top page by the time this runs, so this forgets our record of that page *before*
    * telling `Nav`. Otherwise the sync that `Nav` triggers sees a page it still believes is mounted and pops a
    * **second** one.
    *
    * A two-deep stack hides that bug, because `AdwNavigationView` will not pop its root — so the first version of this
    * looked correct and the self-test agreed. Disabling the guard below changed nothing observable, which is how the
    * extra pop came to light: a check that cannot fail is not evidence. The stack in the self-test is three deep now.
    */
  private val onPopped: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    CFuncPtr2.fromScalaFunction {
      (
        _: Ptr[Byte],
        _: Ptr[Byte]
      ) =>
        GcState.guarded {
          if !syncingFromNav then {
            // The container moved first; match it, then let Nav catch up.
            livePages.lastOption.foreach {
              (
                id,
                entry
              ) =>
                val _ = livePages.remove(id)
                entry._3.dispose()
            }
            val _ = backHandler()
          }
        }
    }

  private val onBack: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    CFuncPtr2.fromScalaFunction {
      (
        _: Ptr[Byte],
        _: Ptr[Byte]
      ) =>
        GcState.guarded { val _ = backHandler() }
    }

  /** The window title comes from `AppRoot.title`, so it follows the top screen. */
  def run(
    id:     String,
    width:  Int = 420,
    height: Int = 260
  )(
    ui: Owner ?=> AppRoot
  ): Int = {
    // The signal graph belongs to the main loop's thread, and anything posted through
    // UiThread — an effect's result, a stream's element — is delivered there.
    Host.install(postToUi)

    appId = id
    windowSize = (width, height)
    build = ui

    val app = Zone(
      gtk_application_new(toCString(appId), GApplicationFlags.G_APPLICATION_DEFAULT_FLAGS)
    )
    application = app
    Handles.connect(app, "activate", CFuncPtr.toPtr(onActivate), 0L)
    Handles.connect(app, "shutdown", Handles.clickedPtr, Handles.register(() => runAll(shutdownHooks)))

    // From here the GTK main loop owns this thread, so Scala must not look Managed to the
    // GC while it is parked in poll() (S3).
    GcState.releaseMainThread()
    val status = g_application_run(app.asInstanceOf[Ptr[GApplication]], 0, null)
    GcState.reacquireMainThread()
    exitStatus.getOrElse(status)
  }

  private var application: Ptr[GtkApplication] = null

  // -- shown or not, and shutdown: what thicket-device-gtk turns into the app's lifecycle (docs/16 §16.3) --------

  private var isShown = false
  private var watchedSurface: Ptr[sn.gnome.gdk4.internal.GdkSurface] = null
  private val shownListeners = scala.collection.mutable.LinkedHashMap.empty[Long, Boolean => Unit]
  private val shutdownHooks = scala.collection.mutable.LinkedHashMap.empty[Long, () => Unit]
  private var nextListener = 0L

  /** Whether the window is on screen: mapped, and its surface neither minimised nor suspended. Suspended is GTK 4.12+'s
    * "not visible to the user", which a compositor reports for a window on another workspace or fully covered; without
    * a window manager (Xvfb) only hiding and showing change it.
    */
  def shown: Boolean = isShown

  /** Calls `f` on the UI thread each time [[shown]] changes, with its new value. Disposing the result removes `f`. */
  def onShownChange(f: Boolean => Unit): Disposable = keep(shownListeners, f)

  /** Calls `f` on the UI thread when the application shuts down: its window closed, or [[quit]]. */
  def onShutdown(f: () => Unit): Disposable = keep(shutdownHooks, f)

  private def keep[F](
    to: scala.collection.mutable.LinkedHashMap[Long, F],
    f:  F
  ): Disposable = {
    nextListener += 1
    val id = nextListener
    to(id) = f
    () => { val _ = to.remove(id) }
  }

  private def runAll(hooks: scala.collection.mutable.LinkedHashMap[Long, () => Unit]): Unit =
    hooks.values.toList.foreach(f => thicket.signals.ErrorSink.catching(())(f()))

  /** The surface exists once the window is realised, and is the one whose state says minimised or suspended. */
  private def watchSurface(): Unit = {
    val surface = gtk_native_get_surface(mainWindow.asInstanceOf[Ptr[GtkNative]])
    if surface != null && surface != watchedSurface then {
      watchedSurface = surface
      Handles.connect(surface, "notify::state", CFuncPtr.toPtr(onSurfaceState), 0L)
    }
  }

  private def updateShown(): Unit = {
    import sn.gnome.gdk4.internal.{GdkToplevel, GdkToplevelState, gdk_toplevel_get_state}
    val mapped =
      mainWindow != null && gtk_widget_get_mapped(mainWindow.asInstanceOf[Ptr[GtkWidget]]).asInstanceOf[CInt] != 0
    val hidden = watchedSurface != null && {
      val state = gdk_toplevel_get_state(watchedSurface.asInstanceOf[Ptr[GdkToplevel]])
      state.is(GdkToplevelState.GDK_TOPLEVEL_STATE_MINIMIZED) || state.is(GdkToplevelState.GDK_TOPLEVEL_STATE_SUSPENDED)
    }
    val now = mapped && !hidden
    if now != isShown then {
      isShown = now
      shownListeners.values.toList.foreach(f => thicket.signals.ErrorSink.catching(())(f(now)))
    }
  }

  /** GObject's `notify`: (GObject*, GParamSpec*, gpointer) -> void, one argument more than the trampolines in
    * [[Handles]] take.
    */
  private val onSurfaceState: CFuncPtr3[Ptr[Byte], Ptr[Byte], Ptr[Byte], Unit] =
    CFuncPtr3.fromScalaFunction {
      (
        _: Ptr[Byte],
        _: Ptr[Byte],
        _: Ptr[Byte]
      ) =>
        GcState.guarded(updateShown())
    }

  /** The app's renderer, once the window exists. For [[GtkInspect]]. */
  private[gtk] var liveRenderer: GtkRenderer = scala.compiletime.uninitialized
  private var exitStatus:        Option[Int] = None

  /** Ends the main loop; [[run]] then returns `status`. A self-test calls this with its failure count, so its verdict
    * is the process's exit status — it used to stay up after "ALL CHECKS PASSED", so a run under `timeout` always
    * exited 124 and nothing could gate on it (review 2026-10-20, P2-20).
    */
  def quit(status: Int): Unit = {
    exitStatus = Some(status)
    if application != null then g_application_quit(application.asInstanceOf[Ptr[GApplication]])
  }

}
