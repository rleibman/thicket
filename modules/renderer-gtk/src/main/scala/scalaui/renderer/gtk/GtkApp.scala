package scalaui.renderer.gtk

import scala.scalanative.unsafe.*
import scalaui.core.{AppRoot, Reconciler}
import scalaui.signals.{Owner, Signal, ThreadGuard}
import sn.gnome.gtk4.internal.*
import sn.gnome.gobject.internal.*
import sn.gnome.gio.internal.*
import sn.gnome.glib.internal.{gchar, gpointer}

/** Hosts an [[Element]] tree in a GTK application window.
  *
  * This is the whole app entry point for a GTK target: `GtkApp.run("id", "Title")(ui)`.
  */
object GtkApp:

  // A CFuncPtr cannot close over local state, so the mount parameters live here.
  private var appId: String                   = "dev.scalaui.app"
  private var windowSize: (Int, Int)          = (420, 260)
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
  def postToUi(f: () => Unit): Unit =
    val id = Handles.register(f)
    val _  = sn.gnome.glib.internal.g_idle_add(
      sn.gnome.glib.internal.GSourceFunc(Handles.idle),
      Handles.idToPointer(id)
    )

  private val onActivate: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    CFuncPtr2.fromScalaFunction { (app: Ptr[Byte], _: Ptr[Byte]) =>
      GcState.guarded:
        val window = gtk_application_window_new(app.asInstanceOf[Ptr[GtkApplication]])
        val w      = window.asInstanceOf[Ptr[GtkWindow]]
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
        Zone:
          val _ = g_signal_connect_data(
            back.asInstanceOf[gpointer],
            toCString("clicked").asInstanceOf[Ptr[gchar]],
            GCallback.fromPtr(CFuncPtr.toPtr(onBack)),
            Handles.idToPointer(0L),
            null.asInstanceOf[GClosureNotify],
            GConnectFlags.define(0)
          )
        Signal.effect(gtk_widget_set_visible(back, gboolTrue(root.canGoBack())))
        Signal.effect(Zone(gtk_window_set_title(w, toCString(root.title()))))

        val mounted = Reconciler.mount(renderer, root.element)
        rootHandle = mounted.handle
        gtk_window_set_child(w, mounted.handle)
        gtk_window_present(w)
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
  ): Int =
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
    Zone:
      val _ = g_signal_connect_data(
        app.asInstanceOf[gpointer],
        toCString("activate").asInstanceOf[Ptr[gchar]],
        GCallback.fromPtr(CFuncPtr.toPtr(onActivate)),
        Handles.idToPointer(0L),
        null.asInstanceOf[GClosureNotify],
        GConnectFlags.define(0)
      )

    // From here the GTK main loop owns this thread, so Scala must not look Managed to the
    // GC while it is parked in poll() (S3).
    GcState.releaseMainThread()
    g_application_run(app.asInstanceOf[Ptr[GApplication]], 0, null)
