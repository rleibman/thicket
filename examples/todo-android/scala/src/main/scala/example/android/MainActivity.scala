/*
 * Copyright 2026 Roberto Leibman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package example.android

import android.os.Bundle
import example.TodoApp
import thicket.core.{AppRoot, ColorRole, NavHost, Rgb, Theme}
import thicket.renderer.ImageSource
import thicket.renderer.android.ThicketActivity
import thicket.signals.Owner

/** The Android host for [[TodoApp]] — the same app the GTK host mounts.
  *
  * Everything a host does — threads, pages, title, Up, the options menu, Back — is `ThicketActivity`'s. What is left
  * here is what belongs to *this* app: its model, its accent colour, its logo, and saving its back stack.
  */
class MainActivity extends ThicketActivity {

  private val model = TodoApp.Model()
  private var app: NavHost[TodoApp.Route] = scala.compiletime.uninitialized

  // Brand exactly one role and leave the rest to the platform. Buttons pick up the accent;
  // text, dividers, the check box and the action bar keep following the user's Android
  // theme, including dark mode.
  override protected def theme: Theme = Theme.platform.withColor(ColorRole.Accent, Rgb(0x2e, 0x6f, 0x40))

  protected def createApp(saved: Option[Bundle])(using Owner): AppRoot = {
    // The logo lives in the APK's assets, which has no filesystem path, so the host reads the
    // bytes and hands the app an ImageSource.
    app = TodoApp(model, logo = logoBytes.map(ImageSource.FromBytes(_)))
    // Restore the back stack across process death. It is a List of a route ADT, so this is
    // ordinary serialisation rather than a framework-specific save/restore protocol.
    saved.flatMap(b => Option(b.getStringArray(StackKey))).foreach { routes =>
      app.navigator.restore(routes.toList.flatMap(TodoApp.parseRoute))
    }
    app
  }

  override protected def started(): Unit =
    if getIntent != null && getIntent.getBooleanExtra("selftest", false) then
      // A thunk: "the screen" is whichever page is on top when a check runs, and the stack
      // changes underneath the test.
      SelfTest.run(model, app, () => topPage.getOrElse(container), () => container)

  override def onSaveInstanceState(out: Bundle): Unit = {
    super.onSaveInstanceState(out)
    out.putStringArray(StackKey, app.navigator.routes.now.map(TodoApp.showRoute).toArray)
  }

  /** The bundled logo, as bytes. `None` rather than a crash if it is missing: a demo that will not start because an
    * image is absent is worse than a demo without a logo.
    */
  private def logoBytes: Option[Array[Byte]] =
    try {
      val in = getAssets.open("thicket-logo.png")
      try Some(in.readAllBytes())
      finally in.close()
    } catch { case _: java.io.IOException => None }

  private val StackKey = "thicket.backstack"

}
