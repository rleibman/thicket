package thicket.renderer.gtk

import scala.scalanative.unsafe.*
import thicket.core.{AppRoot, Reconciler}
import thicket.signals.{Owner, Signal, ThreadGuard}
import sn.gnome.gtk4.internal.*
import sn.gnome.gobject.internal.*
import sn.gnome.gio.internal.*
import sn.gnome.glib.internal.{gchar, gpointer}

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
    */
  var rootHandle: Ptr[GtkWidget] = null

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

        val mounted = Reconciler.mount(renderer, root.element)
        rootHandle = mounted.handle
        gtk_window_set_child(w, mounted.handle)
        gtk_window_present(w)
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
