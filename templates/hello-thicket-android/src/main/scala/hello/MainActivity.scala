package hello

import android.os.Bundle
import thicket.core.*
import thicket.core.dsl.*
import thicket.renderer.android.ThicketActivity
import thicket.signals.{Owner, Var}

/** The smallest Thicket app: a counter, on Android. The same tree as the GTK template's. */
class MainActivity extends ThicketActivity {

  protected def createApp(saved: Option[Bundle])(using Owner): AppRoot = {
    val count = Var(0)
    AppRoot(
      "Hello Thicket",
      // `.safeArea()`: Android 15+ draws every app edge to edge, so without it the content
      // starts under the status bar and the title bar, where it can be neither seen nor tapped.
      Column(spacing = 12, padding = 16)(
        Label(count.map(n => s"Clicked $n times")),
        Button("Click me")(count.set(count.now + 1))
      ).safeArea()
    )
  }

}
