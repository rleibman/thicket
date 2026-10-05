package hello

import thicket.core.*
import thicket.core.dsl.*
import thicket.renderer.apple.AppleApp
import thicket.signals.Var

/** The smallest Thicket app: a counter, on macOS. `AppleApp.run` hands the thread to AppKit and never returns. */
object Main {

  def main(args: Array[String]): Unit = {
    val count = Var(0)
    AppleApp.run("Hello Thicket", 320, 160) {
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
