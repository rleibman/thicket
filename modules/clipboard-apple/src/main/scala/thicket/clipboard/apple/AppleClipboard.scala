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

package thicket.clipboard.apple

import java.nio.charset.StandardCharsets.UTF_8
import scala.scalanative.unsafe.*
import thicket.clipboard.Clipboard
import thicket.renderer.apple.AppleApp

/** The general pasteboard: `NSPasteboard` on macOS, `UIPasteboard` on iOS (#123) — the one Cmd+C and Cmd+V, or the edit
  * menu's Copy and Paste, use, shared with every other app.
  *
  * The pasteboard reads synchronously, but `get` still answers on a later turn of the run loop (through
  * `AppleApp.postToUi`), as GTK and Android do, so a caller sees the same order everywhere. The text is read when `get`
  * is called.
  *
  * On iOS 16 and later, reading text another app put there may show the system's "Allow Paste" prompt; reading this
  * app's own copy does not (`modules/clipboard/README.md` has what the simulator measured). Create it inside
  * `AppleApp.run`, and call from the main thread.
  */
final class AppleClipboard private () extends Clipboard {

  def set(text: String): Unit = {
    val b = text.getBytes(UTF_8)
    Zone {
      val p = alloc[Byte](math.max(b.length, 1))
      var i = 0
      while i < b.length do {
        p(i) = b(i)
        i += 1
      }
      val _ = Pasteboard.thk_pasteboard_set(p, b.length.toLong)
    }
  }

  def get(onResult: Option[String] => Unit): Unit = {
    val text = AppleClipboard.read()
    AppleApp.postToUi(() => onResult(text))
  }

}

object AppleClipboard {

  def apply(): AppleClipboard = new AppleClipboard()

  /** The pasteboard's text now, or `None` when it holds none. */
  private def read(): Option[String] = {
    val out = stackalloc[Ptr[Byte]]()
    val outLen = stackalloc[CLongLong]()
    if Pasteboard.thk_pasteboard_get(out, outLen) == 0 then None
    else {
      val n = (!outLen).toInt
      val p = !out
      val bytes = new Array[Byte](n)
      var i = 0
      while i < n do {
        bytes(i) = p(i)
        i += 1
      }
      Pasteboard.thk_pasteboard_free(p)
      Some(new String(bytes, UTF_8))
    }
  }

  /** The pasteboard's change count: every write, by any app, raises it. For a self-test. */
  def changeCount: Long = Pasteboard.thk_pasteboard_change_count()

}

/** The C helper in `resources/scala-native/thicket_pasteboard.c`. */
@extern
private[apple] object Pasteboard {

  def thk_pasteboard_set(
    utf8: Ptr[Byte],
    len:  CLongLong
  ): CInt = extern

  def thk_pasteboard_get(
    out:    Ptr[Ptr[Byte]],
    outLen: Ptr[CLongLong]
  ): CInt = extern

  def thk_pasteboard_free(p: Ptr[Byte]): Unit = extern

  def thk_pasteboard_change_count(): CLongLong = extern

}
