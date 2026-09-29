package example

import thicket.core.AppRoot
import thicket.core.dsl.*
import thicket.renderer.gtk.GtkApp
import thicket.signals.Var

/** The smallest complete Thicket app. */
object Counter {

  def main(args: Array[String]): Unit = {
    val _ = GtkApp.run("dev.thicket.counter", 380, 220) {
      val count = Var(0)

      AppRoot(
        "Thicket counter",
        Column(spacing = 16, padding = 24)(
          Label(count.map(n => s"Count: $n")),
          Row(spacing = 8)(
            Button("−")(count.update(_ - 1)),
            Button("+")(count.update(_ + 1)),
            Button("Reset")(count.set(0))
          )
        )
      )
    }
  }
}
