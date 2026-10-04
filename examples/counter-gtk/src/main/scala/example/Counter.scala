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
