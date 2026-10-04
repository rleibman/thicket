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

import thicket.core.Element
import thicket.core.dsl.*
import thicket.signals.Var

/** The todo app's UI, with no reference to any platform.
  *
  * This same value is mounted by the GTK renderer on Linux and by the Android renderer on
  * a phone. If the abstraction is worth anything, this file never has to know which.
  */
object TodoUi {

  final case class Item(id: Int, title: String, done: Boolean)

  final class Model {
    val items: Var[Seq[Item]] = Var(
      Seq(
        Item(1, "Structural reconciliation", true),
        Item(2, "Android renderer", true),
        Item(3, "Navigation", false)
      )
    )
    private var nextId = 4

    def add(): Unit = {
      items.update(_ :+ Item(nextId, s"New item $nextId", false))
      nextId += 1
    }

    def rotate(): Unit = items.update {
      case head +: rest => rest :+ head
      case empty        => empty
    }

    def toggleFirst(): Unit = items.update {
      case head +: rest => head.copy(done = !head.done) +: rest
      case empty        => empty
    }

    def dropLast(): Unit = items.update(_.dropRight(1))
  }

  def bullet(i: Item): String =
    (if i.done then "✓" else "•") + "  " + i.title

  /** The element tree. Built once; signals keep it current. */
  def apply(model: Model): Element =
    Column(spacing = 12, padding = 20)(
      Label("Todo"),

      // Keyed: reordering moves existing widgets rather than rebuilding them.
      Column(spacing = 4)(
        ForEach(model.items, key = (i: Item) => i.id)(item => Label(item.map(bullet)))
      ),

      // A conditional region between two static siblings.
      Show(model.items.map(_.isEmpty))(Label("Nothing left to do.")),

      Row(spacing = 8)(
        Button("Add")(model.add()),
        Button("Toggle")(model.toggleFirst()),
        Button("Rotate")(model.rotate()),
        Button("Drop")(model.dropLast())
      ),

      Label(model.items.map(xs => s"${xs.count(_.done)} of ${xs.size} done"))
    )
}
