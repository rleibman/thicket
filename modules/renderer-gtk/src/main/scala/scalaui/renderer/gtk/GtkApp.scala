package scalaui.renderer.gtk

import scala.scalanative.unsafe.*
import scalaui.core.{Element, Reconciler}
import scalaui.signals.{Owner, ThreadGuard}
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
  private var windowTitle: String             = "scala-ui"
  private var windowSize: (Int, Int)          = (420, 260)
  private var build: Owner ?=> Element        =
    (_: Owner) ?=> throw IllegalStateException("GtkApp.run was not given a UI")
  private val rootOwner: Owner                = Owner()

  private val onActivate: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    CFuncPtr2.fromScalaFunction { (app: Ptr[Byte], _: Ptr[Byte]) =>
      GcState.guarded:
        val window = gtk_application_window_new(app.asInstanceOf[Ptr[GtkApplication]])
        val w      = window.asInstanceOf[Ptr[GtkWindow]]
        Zone(gtk_window_set_title(w, toCString(windowTitle)))
        gtk_window_set_default_size(w, windowSize._1, windowSize._2)
        gtk_window_set_titlebar(w, gtk_header_bar_new())

        val renderer = GtkRenderer()
        given Owner  = rootOwner
        val mounted  = Reconciler.mount(renderer, build)
        gtk_window_set_child(w, mounted.handle)
        gtk_window_present(w)
    }

  def run(id: String, title: String, width: Int = 420, height: Int = 260)(
      ui: Owner ?=> Element
  ): Int =
    // The signal graph belongs to whichever thread reaches it first; on GTK that is the
    // main loop's thread. Installing the guard makes a stray cross-thread write fail loudly
    // instead of corrupting the graph (docs/05 A-06).
    ThreadGuard.install(ThreadGuard.owningThread)

    appId = id
    windowTitle = title
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
