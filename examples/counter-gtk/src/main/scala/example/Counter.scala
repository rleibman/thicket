package example

import scalaui.core.dsl.*
import scalaui.renderer.gtk.GtkApp
import scalaui.signals.Var

/** The first app built entirely through scala-ui. */
object Counter:

  def main(args: Array[String]): Unit =
    val _ = GtkApp.run("dev.scalaui.counter", "scala-ui counter", 380, 220):
      val count = Var(0)

      Column(spacing = 16, padding = 24)(
        Label(count.map(n => s"Count: $n")),
        Row(spacing = 8)(
          Button("−")(count.update(_ - 1)),
          Button("+")(count.update(_ + 1)),
          Button("Reset")(count.set(0))
        )
      )
