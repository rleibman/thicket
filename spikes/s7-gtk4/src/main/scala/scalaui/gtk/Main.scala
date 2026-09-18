package scalaui.gtk

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import sn.gnome.gtk4.internal.*
import sn.gnome.gobject.internal.*
import sn.gnome.gio.internal.*
import sn.gnome.glib.internal.gchar

/** S7: a counter built *through the draft renderer contract*, not against GTK directly.
  * The point is to find out where the contract chafes against a real toolkit.
  */
object Main:

  private val renderer  = GtkRenderer()
  private val processT0 = System.nanoTime()
  private var count    = 0

  // Built in `activate`; held here because a CFuncPtr cannot close over locals (S4).
  private var label: Ptr[GtkWidget] = null
  private var inc: Ptr[GtkWidget]   = null

  private def render(): Unit =
    renderer.update(label, Seq(Prop.Text(s"Count: $count")))

  private val onActivateFn: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit] =
    CFuncPtr2.fromScalaFunction { (app: Ptr[Byte], _: Ptr[Byte]) =>
      val window = gtk_application_window_new(app.asInstanceOf[Ptr[GtkApplication]])
      Zone(gtk_window_set_title(window.asInstanceOf[Ptr[GtkWindow]], toCString("S7 — scala-ui")))
      gtk_window_set_default_size(window.asInstanceOf[Ptr[GtkWindow]], 360, 200)

      // Header bar: proves we get real platform chrome, not a drawn imitation.
      val header = gtk_header_bar_new()
      gtk_window_set_titlebar(window.asInstanceOf[Ptr[GtkWindow]], header)

      val column = renderer.create(WidgetKind.Column, Seq(Prop.Spacing(12), Prop.Padding(24)))
      label = renderer.create(WidgetKind.Label, Seq(Prop.Text("Count: 0")))
      val row  = renderer.create(WidgetKind.Row, Seq(Prop.Spacing(8)))
      val dec  = renderer.create(WidgetKind.Button,
        Seq(Prop.Text("−"), Prop.OnTap(() => { count -= 1; render() })))
      inc = renderer.create(WidgetKind.Button,
        Seq(Prop.Text("+"), Prop.OnTap(() => { count += 1; render() })))

      renderer.insertChild(row, dec, 0)
      renderer.insertChild(row, inc, 1)
      renderer.insertChild(column, label, 0)
      renderer.insertChild(column, row, 1)
      gtk_window_set_child(window.asInstanceOf[Ptr[GtkWindow]], column)

      val m = renderer.measure(label, Constraints(Float.NaN, Float.NaN))
      println(s"[S7] measure(label) = ${m.w} x ${m.h}")

      // Accessibility: GTK assigns semantic roles to its own widgets. This is the
      // "native widgets give accessibility for free" claim (docs/05 F-02), checked.
      val btnRole = gtk_accessible_get_accessible_role(inc.asInstanceOf[Ptr[GtkAccessible]])
      val lblRole = gtk_accessible_get_accessible_role(label.asInstanceOf[Ptr[GtkAccessible]])
      println(s"[S7] accessible roles: button=${btnRole.value}, label=${lblRole.value} " +
        s"(GTK_ACCESSIBLE_ROLE_BUTTON=${GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_BUTTON.value}, " +
        s"LABEL=${GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_LABEL.value})")

      // A background Scala thread updating the UI through runOnUiThread, then firing the
      // button through GTK's own dispatch to prove the signal wiring works end to end.
      val t = new Thread(() =>
        Thread.sleep(400)
        renderer.runOnUiThread { () =>
          count += 100
          render()
          println(s"[S7] background thread updated the UI via g_idle_add; count=$count")
        }
        Thread.sleep(400)
        renderer.runOnUiThread { () =>
          gtk_widget_activate(inc)
          gtk_widget_activate(inc)
          println(s"[S7] two activations dispatched (count still $count: GTK emits " +
            "'clicked' on a later main-loop turn, not synchronously)")
        }
        Thread.sleep(400)
        renderer.runOnUiThread { () =>
          println(s"[S7] after GTK delivered the clicks: count=$count (expected 102)")
        }
      )
      t.setDaemon(true)
      t.start()

      gtk_window_present(window.asInstanceOf[Ptr[GtkWindow]])
      println(f"[S7] process start -> window presented: ${(System.nanoTime() - processT0) / 1e6}%.1f ms")
    }

  def main(args: Array[String]): Unit =
    val app = Zone(gtk_application_new(toCString("dev.scalaui.s7"), GApplicationFlags.G_APPLICATION_DEFAULT_FLAGS))
    Zone:
      g_signal_connect_data(
        app.asInstanceOf[sn.gnome.glib.internal.gpointer],
        toCString("activate").asInstanceOf[Ptr[gchar]],
        GCallback.fromPtr(CFuncPtr.toPtr(onActivateFn)),
        Handles.idToPointer(0L),
        null.asInstanceOf[GClosureNotify],
        GConnectFlags.define(0)
      )
    println(f"[S7] gtk_application_new + connect: ${(System.nanoTime() - processT0) / 1e6}%.1f ms")
    val status = g_application_run(app.asInstanceOf[Ptr[GApplication]], 0, null)
    println(s"[S7] exited with $status")
