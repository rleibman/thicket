package hello

import scala.scalanative.unsafe.exported
import thicket.core.*
import thicket.core.dsl.*
import thicket.renderer.apple.{AppleApp, GcState}
import thicket.signals.Var

/** The smallest Thicket app: a counter, on iOS.
  *
  * No `main`: iOS requires the UIScene lifecycle, and a scene delegate cannot live in the static library Scala Native
  * produces, so the Swift host in `ios-app/` owns `main` and calls this once its window exists.
  */
object Hello {

  /** Called by the Swift host, on the main thread. `guarded` because this is an entry into Scala from a thread the run
    * loop owns.
    */
  @exported("thicket_hello_start")
  def start(): Unit =
    GcState.guarded {
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
