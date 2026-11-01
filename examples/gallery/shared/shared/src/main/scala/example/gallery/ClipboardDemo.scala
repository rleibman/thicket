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

package example.gallery

import thicket.clipboard.Clipboard
import thicket.device.Availability
import thicket.haptics.{Haptics, Impact}
import thicket.signals.Var

/** The Clipboard and haptics section's state: text copied to and pasted from the system clipboard, and a tap to feel.
  *
  * Each copy is numbered, under a number drawn at launch, so a paste shows this run's latest copy and nothing older.
  */
final class ClipboardDemo(
  clipboard: Option[Clipboard],
  haptics:   Option[Haptics]
) {

  private val run = scala.util.Random.nextInt(100000)
  private var copies = 0

  val pasted: Var[String] = Var(ClipboardDemo.notPasted)

  /** The text the latest copy put on the clipboard, for a self-test. */
  var lastCopied: Option[String] = None

  val hapticsLabel: String = haptics.fold(ClipboardDemo.noHaptics)(h => ClipboardDemo.hapticsLabel(h.availability))

  /** Copies the next numbered text, and answers it. */
  def copy(): String = {
    copies += 1
    val text = ClipboardDemo.copied(run, copies)
    clipboard.foreach(_.set(text))
    lastCopied = Some(text)
    text
  }

  def paste(): Unit = clipboard.foreach(_.get(t => pasted.set(ClipboardDemo.pastedLabel(t))))

  def tap(): Unit = haptics.foreach(_.impact(Impact.Medium))

}

object ClipboardDemo {

  def copied(
    run: Int,
    n:   Int
  ): String = s"Thicket gallery $run, copy $n"

  val notPasted: String = "Pasted: nothing yet"

  def pastedLabel(text: Option[String]): String = text.fold("Pasted: the clipboard holds no text")(t => s"Pasted: $t")

  val noHaptics: String = "Haptics: not on this platform yet"

  def hapticsLabel(a: Availability): String =
    a match {
      case Availability.Available        => "Haptics: available"
      case Availability.Unavailable(why) => s"Haptics: unavailable ($why)"
      case other                         => s"Haptics: $other"
    }

}
