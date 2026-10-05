package hello

import thicket.core.*
import thicket.core.dsl.*
import thicket.renderer.gtk.GtkApp
import thicket.signals.Var

/** The smallest Thicket app: a counter, on GTK. */
object Main {

  def main(args: Array[String]): Unit = {
    val count = Var(0)
    val _ = GtkApp.run("dev.thicket.hello", 320, 160) {
      AppRoot(
        "Hello Thicket",
        Column(spacing = 12, padding = 16)(
          Label(count.map(n => s"Clicked $n times")),
          Button("Click me")(count.set(count.now + 1))
        )
      )
    }
  }

}
